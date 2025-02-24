package org.usvm.api.util

import bench.JcLambdaFeature
import org.jacodb.api.jvm.JcClassOrInterface
import org.jacodb.api.jvm.JcClasspath
import org.jacodb.api.jvm.ext.allSuperHierarchySequence
import org.jacodb.approximation.Approximations
import org.jacodb.approximation.JcEnrichedVirtualField
import org.jacodb.approximation.JcEnrichedVirtualMethod
import org.jacodb.impl.bytecode.JcClassOrInterfaceImpl
import org.jacodb.impl.bytecode.JcMethodImpl
import org.jacodb.impl.cfg.MethodNodeBuilder
import org.jacodb.impl.features.JcFeaturesChain
import org.jacodb.impl.features.classpaths.JcUnknownClass
import org.jacodb.impl.features.classpaths.UnknownClassMethodsAndFields
import org.jacodb.impl.features.classpaths.UnknownClasses
import org.jacodb.impl.types.AnnotationInfo
import org.jacodb.impl.types.MethodInfo
import org.jacodb.impl.types.ParameterInfo
import org.usvm.api.internal.InitHelper
import org.usvm.jvm.util.toByteArray
import org.usvm.machine.state.concreteMemory.JcConcreteEffectStorage
import org.usvm.machine.state.concreteMemory.isInstrumentedClinit
import org.usvm.machine.state.concreteMemory.isInstrumentedInit
import org.usvm.machine.state.concreteMemory.isLambdaTypeName
import org.usvm.machine.state.concreteMemory.javaName
import org.usvm.machine.state.concreteMemory.setStaticFieldValue
import org.usvm.machine.state.concreteMemory.staticFields
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.security.CodeSource
import java.security.SecureClassLoader
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import org.usvm.jvm.util.replace

/**
 * Loads known classes using [ClassLoader.getSystemClassLoader], or defines them using bytecode from jacodb if they are unknown.
 */
// TODO: make this 'class'
internal object JcConcreteMemoryClassLoader : SecureClassLoader(ClassLoader.getSystemClassLoader()) {

    var webApplicationClass: JcClassOrInterface? = null
    lateinit var cp: JcClasspath
    private val loadedClasses = hashMapOf<String, Class<*>>()
    private val initializedStatics = hashSetOf<Class<*>>()
    private lateinit var effectStorage: JcConcreteEffectStorage

    private val File.isJar
        get() = this.extension == "jar"

    private val File.URL
        get() = this.toURI().toURL()

    private fun File.matchResource(locURI: URI, name: String): Boolean {
        check(name.isNotEmpty())
        val relativePath by lazy { locURI.relativize(this.toURI()).toString() }
        return this.name == name
                || relativePath == name
                || relativePath.endsWith(name)
    }

    private fun JarEntry.matchResource(name: String, single: Boolean): Boolean {
        check(name.isNotEmpty())
        val entryName = this.name
        return entryName == name
                || entryName.endsWith(name)
                || !single && entryName.contains(name)
    }

    private fun findResourcesInFolder(
        locFile: File,
        name: String,
        single: Boolean
    ): List<URL>? {
        check(locFile.isDirectory)
        val result = mutableListOf<URL>()

        val locURI = locFile.toURI()
        val queue: Queue<File> = LinkedList()
        var current: File? = locFile
        while (current != null) {
            if (current.matchResource(locURI, name)) {
                result.add(current.URL)
                if (single)
                    break
            }

            if (current.isDirectory)
                queue.addAll(current.listFiles()!!)

            current = queue.poll()
        }

        if (result.isNotEmpty())
            return result

        return null
    }

    private fun findResourcesInJar(locFile: File, name: String, single: Boolean): List<URL>? {
        val jar = JarFile(locFile)
        val jarPath = "jar:file:${locFile.absolutePath}!".replace("\\", "/")
        if (single) {
            for (current in jar.entries()) {
                if (current.matchResource(name, true))
                    return listOf(URI("$jarPath/${current.name}").toURL())
            }
        } else {
            val result = jar.entries().toList().mapNotNull {
                if (it.matchResource(name, false))
                    URI("$jarPath/${it.name}").toURL()
                else null
            }
            if (result.isNotEmpty())
                return result
        }

        return null
    }

    private fun tryGetResource(locFile: File, name: String): List<URL>? {
        check(locFile.isFile)
        return if (locFile.name == name) listOf(locFile.URL) else null
    }

    private fun internalFindResources(name: String?, single: Boolean): Enumeration<URL>? {
        if (name.isNullOrEmpty())
            return null

        val result = mutableListOf<URL>()
        for (loc in cp.locations) {
            val locFile = loc.jarOrFolder
            val resources =
                if (locFile.isJar) findResourcesInJar(locFile, name, single)
                else if (locFile.isDirectory) findResourcesInFolder(locFile, name, single)
                else tryGetResource(locFile, name)
            if (resources != null) {
                if (single)
                    return Collections.enumeration(resources)
                result += resources
            }
        }

        if (result.isNotEmpty())
            return Collections.enumeration(result)

        return null
    }

    fun setEffectStorage(effectStorage: JcConcreteEffectStorage) {
        this.effectStorage = effectStorage
    }

    fun initializedStatics(): Set<Class<*>> {
        return initializedStatics
    }

    private val afterClinitAction: java.util.function.Function<String, Void?> =
        java.util.function.Function { className: String ->
            val clazz = loadedClasses[className] ?: return@Function null
            initializedStatics.add(clazz)
            effectStorage.addStatics(clazz)
            null
        }

    private val afterInitAction: java.util.function.Function<Any, Void?> =
        java.util.function.Function { newObj: Any ->
            effectStorage.addNewObject(newObj)
            null
        }

    private fun initInitHelper(type: Class<*>) {
        check(type.name == InitHelper::class.java.name)
        // Forcing `<clinit>` of `InitHelper`
        type.declaredFields.first().get(null)
        // Initializing static fields
        val staticFields = type.staticFields
        staticFields
            .find { it.name == InitHelper::afterClinitAction.javaName }!!
            .setStaticFieldValue(afterClinitAction)
        staticFields
            .find { it.name == InitHelper::afterInitAction.javaName }!!
            .setStaticFieldValue(afterInitAction)
    }

    override fun loadClass(name: String?): Class<*> {
        if (name == null)
            throw ClassNotFoundException()

        val loadedClass = loadedClasses[name]
        if (loadedClass != null)
            return loadedClass

        if (name.isLambdaTypeName)
            return loadLambdaClass(name)

        val jcClass = cp.findClassOrNull(name) ?: throw ClassNotFoundException(name)
        return defineClassRecursively(jcClass)
    }

    fun isLoaded(jcClass: JcClassOrInterface): Boolean {
        return loadedClasses.containsKey(jcClass.name)
    }

    private fun loadLambdaClass(name: String): Class<*> {
        val type = JcLambdaFeature.lambdaClassByName(name) ?: super.loadClass(name)
        loadedClasses[name] = type
        return type
    }

    fun loadClass(jcClass: JcClassOrInterface): Class<*> {
        if (jcClass.name.isLambdaTypeName)
            return loadLambdaClass(jcClass.name)

        return defineClassRecursively(jcClass)
    }

    private fun defineClass(name: String, code: ByteArray): Class<*> {
        return defineClass(name, ByteBuffer.wrap(code), null as CodeSource?)
    }

    override fun getResource(name: String?): URL? {
        try {
            return internalFindResources(name, true)?.nextElement()
        } catch (e: Throwable) {
            error("Failed getting resource ${e.message}")
        }
    }

    override fun getResources(name: String?): Enumeration<URL> {
        try {
            return internalFindResources(name, false) ?: Collections.emptyEnumeration()
        } catch (e: Throwable) {
            error("Failed getting resources ${e.message}")
        }
    }

    private fun typeIsRuntimeGenerated(jcClass: JcClassOrInterface): Boolean {
        return jcClass.name == "org.mockito.internal.creation.bytebuddy.inject.MockMethodDispatcher"
    }

    private fun defineClassRecursively(jcClass: JcClassOrInterface): Class<*> =
        defineClassRecursively(jcClass, hashSetOf())
            ?: error("Can't define class $jcClass")

    private fun JcClasspath.featuresChainWithoutApproximations(): JcFeaturesChain {
        val featuresChainField = this.javaClass.getDeclaredField("featuresChain")
        featuresChainField.isAccessible = true
        val featuresChain = featuresChainField.get(this) as JcFeaturesChain
        val features = featuresChain.features.filterNot { it is Approximations }
        return JcFeaturesChain(features)
    }

    private fun getBytecode(jcClass: JcClassOrInterface): ByteArray {
        val instrumentedMethods = jcClass.declaredMethods.filter { it.isInstrumentedClinit || it.isInstrumentedInit }
        if (instrumentedMethods.isEmpty())
            return jcClass.bytecode()

        return jcClass.withAsmNode { asmNode ->
            for (method in instrumentedMethods) {
                val isApproximated = method is JcEnrichedVirtualMethod
                if (isApproximated && asmNode.methods.none { it.name == method.name && it.desc == method.description })
                    continue

                val rawInstList = if (isApproximated) {
                    val parameters = method.parameters.map {
                        ParameterInfo(it.type.typeName, it.index, it.access, it.name, emptyList())
                    }
                    val info = MethodInfo(method.name, method.description, method.signature, method.access, emptyList(), emptyList(), parameters)
                    val featuresChain = jcClass.classpath.featuresChainWithoutApproximations()
                    val newMethod = JcMethodImpl(info, featuresChain, jcClass)
                    newMethod.rawInstList
                } else { method.rawInstList }

                val newMethodNode = MethodNodeBuilder(method, rawInstList).build()
                val oldMethodNode = asmNode.methods.find { it.name == method.name && it.desc == method.description }
                asmNode.methods.replace(oldMethodNode, newMethodNode)
            }

            asmNode.toByteArray(jcClass.classpath)
        }
    }

    private fun defineClassRecursively(
        jcClass: JcClassOrInterface,
        visited: MutableSet<JcClassOrInterface>
    ): Class<*>? {
        val className = jcClass.name
        return loadedClasses.getOrPut(className) {
            if (!visited.add(jcClass))
                return null

            if (jcClass.declaration.location.isRuntime || typeIsRuntimeGenerated(jcClass))
                return@getOrPut super.loadClass(className)

            if (jcClass is JcUnknownClass)
                throw ClassNotFoundException(className)

            val notVisitedSupers = jcClass.allSuperHierarchySequence.filterNot { it in visited }
            notVisitedSupers.forEach { defineClassRecursively(it, visited) }

            val bytecode = getBytecode(jcClass)
            val loadedClass = defineClass(className, bytecode)
            if (loadedClass.name == InitHelper::class.java.name)
                initInitHelper(loadedClass)

            return@getOrPut loadedClass
        }
    }
}
