package org.usvm.machine.state.concreteMemory

import bench.JcLambdaFeature
import org.jacodb.api.jvm.ClassSource
import org.jacodb.api.jvm.JcArrayType
import org.jacodb.api.jvm.JcByteCodeLocation
import org.jacodb.api.jvm.JcClassOrInterface
import org.jacodb.api.jvm.JcClassType
import org.jacodb.api.jvm.JcField
import org.jacodb.api.jvm.JcMethod
import org.jacodb.api.jvm.JcPrimitiveType
import org.jacodb.api.jvm.JcType
import org.jacodb.api.jvm.JcTypedField
import org.jacodb.api.jvm.JcTypedMethod
import org.jacodb.api.jvm.RegisteredLocation
import org.jacodb.api.jvm.cfg.JcRawCallInst
import org.jacodb.api.jvm.cfg.JcRawStaticCallExpr
import org.jacodb.api.jvm.ext.allSuperHierarchy
import org.jacodb.api.jvm.ext.isEnum
import org.jacodb.api.jvm.ext.isSubClassOf
import org.jacodb.api.jvm.ext.packageName
import org.jacodb.api.jvm.ext.superClasses
import org.jacodb.api.jvm.ext.toType
import org.jacodb.api.jvm.throwClassNotFound
import org.jacodb.approximation.Approximations
import org.jacodb.approximation.JcEnrichedVirtualField
import org.jacodb.approximation.JcEnrichedVirtualMethod
import org.jacodb.approximation.OriginalClassName
import org.jacodb.impl.features.classpaths.JcUnknownClass
import org.jacodb.impl.features.classpaths.JcUnknownType
import org.usvm.api.internal.ClinitHelper
import org.usvm.api.util.JcConcreteMemoryClassLoader
import org.usvm.api.util.Reflection.getFieldValue
import org.usvm.api.util.Reflection.toJavaClass
import org.usvm.api.util.Reflection.toJavaExecutable
import org.usvm.instrumentation.util.isStatic
import org.usvm.instrumentation.util.toJavaClass
import org.usvm.instrumentation.util.setFieldValue as setFieldValueUnsafe
import org.usvm.machine.JcContext
import org.usvm.util.name
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod

@Suppress("RecursivePropertyAccessor")
internal val JcClassType.allFields: List<JcTypedField>
    get() = declaredFields + (superType?.allFields ?: emptyList())

internal val Class<*>.safeDeclaredFields: List<Field>
    get() {
        return try {
            declaredFields.toList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

@Suppress("RecursivePropertyAccessor")
internal val Class<*>.allFields: List<Field>
    get() = safeDeclaredFields + (superclass?.allFields ?: emptyList())

internal val JcClassType.allInstanceFields: List<JcTypedField>
    get() = allFields.filter { !it.isStatic }

internal val JcClassType.declaredInstanceFields: List<JcTypedField>
    get() = declaredFields.filter { !it.isStatic }

internal val Class<*>.allInstanceFields: List<Field>
    get() = allFields.filter { !Modifier.isStatic(it.modifiers) }

internal val JcClassOrInterface.staticFields: List<JcField>
    get() = declaredFields.filter { it.isStatic }

internal val Class<*>.staticFields: List<Field>
    get() = safeDeclaredFields.filter { Modifier.isStatic(it.modifiers) }

internal fun Field.getFieldValue(obj: Any): Any? {
    check(!isStatic)
    isAccessible = true
    return get(obj)
}

internal fun Field.getStaticFieldValue(): Any? {
    check(isStatic)
    isAccessible = true
    return get(null)
//     TODO: null!! #CM #Valya
//    return getFieldValueUnsafe(null)
}

internal fun Field.setStaticFieldValue(value: Any?) {
    check(value !is PhysicalAddress)
//    isAccessible = true
//    set(null, value)
    setFieldValueUnsafe(null, value)
}

internal val Field.isFinal: Boolean
    get() = Modifier.isFinal(modifiers)

internal fun JcField.getFieldValue(obj: Any): Any? {
    if (this is JcEnrichedVirtualField) {
        val javaField = obj.javaClass.allInstanceFields.find { it.name == name }!!
        return javaField.getFieldValue(obj)
    }

    return this.getFieldValue(JcConcreteMemoryClassLoader, obj)
}

internal fun Field.setFieldValue(obj: Any, value: Any?) {
    check(value !is PhysicalAddress)
    isAccessible = true
    set(obj, value)
}

internal val kotlin.reflect.KProperty<*>.javaName: String
    get() = this.javaField?.name ?: error("No java name for field $this")

internal val kotlin.reflect.KFunction<*>.javaName: String
    get() = this.javaMethod?.name ?: error("No java name for method $this")

@Suppress("UNCHECKED_CAST")
internal fun <Value> Any.getArrayValue(index: Int): Value {
    return when (this) {
        is IntArray -> this[index] as Value
        is ByteArray -> this[index] as Value
        is CharArray -> this[index] as Value
        is LongArray -> this[index] as Value
        is FloatArray -> this[index] as Value
        is ShortArray -> this[index] as Value
        is DoubleArray -> this[index] as Value
        is BooleanArray -> this[index] as Value
        is Array<*> -> this[index] as Value
        else -> error("getArrayValue: unexpected array $this")
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <Value> Any.setArrayValue(index: Int, value: Value) {
    check(value !is PhysicalAddress)
    when (this) {
        is IntArray -> this[index] = value as Int
        is ByteArray -> this[index] = value as Byte
        is CharArray -> this[index] = value as Char
        is LongArray -> this[index] = value as Long
        is FloatArray -> this[index] = value as Float
        is ShortArray -> this[index] = value as Short
        is DoubleArray -> this[index] = value as Double
        is BooleanArray -> this[index] = value as Boolean
        is Array<*> -> (this as Array<Value>)[index] = value
        else -> error("setArrayValue: unexpected array $this")
    }
}

internal val JcField.toJavaField: Field?
    get() = enclosingClass.toJavaClass(JcConcreteMemoryClassLoader).allFields.find { it.name == name }

internal val JcMethod.toJavaMethod: Executable?
    get() = this.toJavaExecutable(JcConcreteMemoryClassLoader)

internal val JcMethod.toTypedMethod: JcTypedMethod
    get() = enclosingClass.toType().declaredMethods.find { this == it.method }!!

internal val JcEnrichedVirtualMethod.approximationMethod: JcMethod?
    get() {
        val originalClassName = OriginalClassName(enclosingClass.name)
        val approximationClassName =
            Approximations.findApproximationByOriginOrNull(originalClassName)
                ?: return null
        return enclosingClass.classpath.findClassOrNull(approximationClassName)
            ?.declaredMethods
            ?.find { it.name == this.name }
    }

internal val JcType.isInstanceApproximation: Boolean
    get() {
        if (this !is JcClassType)
            return false

        val originalType = toJavaClass(JcConcreteMemoryClassLoader)
        val originalFieldNames = originalType.allInstanceFields.map { it.name }
        return this.allInstanceFields.any { !originalFieldNames.contains(it.field.name) }
    }

internal val JcType.isStaticApproximation: Boolean
    get() {
        if (this !is JcClassType)
            return false

        val originalType = toJavaClass(JcConcreteMemoryClassLoader)
        val originalFieldNames = originalType.staticFields.map { it.name }
        return this.jcClass.staticFields.any { !originalFieldNames.contains(it.name) }
    }

@Suppress("RecursivePropertyAccessor")
internal val JcType.isEnum: Boolean
    get() = this is JcClassType && (this.jcClass.isEnum || this.superType?.isEnum == true)

internal val JcType.isEnumArray: Boolean
    get() = this is JcArrayType && this.elementType.let { it is JcClassType && it.jcClass.isEnum }

internal val JcType.internalName: String
    get() = if (this is JcClassType) this.name else this.typeName

internal val Class<*>.isProxy: Boolean
    get() = Proxy.isProxyClass(this)

internal val String.isLambdaTypeName: Boolean
    get() = contains("\$\$Lambda\$")

internal fun getLambdaCanonicalTypeName(typeName: String): String {
    check(typeName.isLambdaTypeName)
    return typeName.split('/')[0]
}

internal val Class<*>.isLambda: Boolean
    get() = typeName.isLambdaTypeName

internal val Class<*>.isThreadLocal: Boolean
    get() = ThreadLocal::class.java.isAssignableFrom(this)

internal val Class<*>.isByteBuffer: Boolean
    get() = ByteBuffer::class.java.isAssignableFrom(this)

internal val Class<*>.hasStatics: Boolean
    get() = staticFields.isNotEmpty()

internal val JcClassOrInterface.isLambda: Boolean
    get() = name.contains("\$\$Lambda\$")

internal val JcClassOrInterface.isException: Boolean
    get() = superClasses.any { it.name == "java.lang.Throwable" }

internal val JcMethod.isExceptionCtor: Boolean
    get() = isConstructor && enclosingClass.isException

internal val JcMethod.isInstrumentedClinit: Boolean
    get() = isClassInitializer && rawInstList.any {
        it is JcRawCallInst && it.callExpr is JcRawStaticCallExpr
                && it.callExpr.methodName == ClinitHelper::afterClinit.javaName
    }

internal val Class<*>.notTracked: Boolean
    get() = this.isPrimitive || isImmutable || this.isEnum

internal val JcType.notTracked: Boolean
    get() =
        this is JcPrimitiveType ||
                this is JcClassType &&
                (this.jcClass.isEnum || jcClass.isImmutable)

private val immutableTypes = setOf(
    "jdk.internal.loader.ClassLoaders\$AppClassLoader",
    "java.security.AllPermission",
    "java.net.NetPermission",
)

private val packagesWithImmutableTypes = setOf(
    "java.lang", "java.lang.reflect", "java.lang.invoke"
)

internal val Class<*>.isClassLoader: Boolean
    get() = ClassLoader::class.java.isAssignableFrom(this)

private fun typeNameIsInternal(name: String): Boolean {
    return name.startsWith("org.usvm.") ||
            name.startsWith("runtime.LibSLRuntime") ||
            name.startsWith("runtime.LibSLGlobals") ||
            name.startsWith("generated.") ||
            name.startsWith("stub.") ||
            name.startsWith("org.jacodb.")
}

internal val Class<*>.isInternalType: Boolean
    get() = typeNameIsInternal(name)

internal val JcClassOrInterface.isInternalType: Boolean
    get() = typeNameIsInternal(name)

internal val Class<*>.isImmutable: Boolean
    get() = immutableTypes.contains(name)
            || isClassLoader
            || packageName in packagesWithImmutableTypes
            || isInternalType

private val JcClassOrInterface.isClassLoader: Boolean
    get() = allSuperHierarchy.any { it.name == "java.lang.ClassLoader" }

private val JcClassOrInterface.isImmutable: Boolean
    get() = immutableTypes.contains(this.name)
            || isClassLoader
            || packageName in packagesWithImmutableTypes
            || isInternalType

internal val Class<*>.isSolid: Boolean
    get() = notTracked || this.isArray && this.componentType.notTracked

//private val JcType.isSolid: Boolean
//    get() = notTracked || isImmutable || this is JcArrayType && this.elementType.notTracked

class LambdaClassSource(
    override val location: RegisteredLocation,
    override val className: String,
    private val fileName: String
) : ClassSource {
    override val byteCode by lazy {
        location.jcLocation?.resolve(fileName) ?: className.throwClassNotFound()
    }
}

internal fun Class<*>.toJcType(ctx: JcContext): JcType? {
    try {
        if (isProxy) {
            val interfaces = interfaces
            if (interfaces.size == 1)
                return ctx.cp.findTypeOrNull(interfaces[0].typeName)

            return null
        }

        if (isLambda) {
            val cachedType = ctx.cp.findTypeOrNull(name)
            if (cachedType != null && cachedType !is JcUnknownType)
                return cachedType

            // TODO: add dynamic load of classes into jacodb
            val db = ctx.cp.db
            val vfs = db.javaClass.allInstanceFields.find { it.name == "classesVfs" }!!.getFieldValue(db)!!
            val lambdasDir = System.getProperty("lambdasDir")
            val loc = ctx.cp.registeredLocations.find {
                it.jcLocation?.jarOrFolder?.absolutePath == lambdasDir
            }!!
            val addMethod = vfs.javaClass.methods.find { it.name == "addClass" }!!
            val fileName = getLambdaCanonicalTypeName(name)
            val source = LambdaClassSource(loc, name, fileName)
            addMethod.invoke(vfs, source)

            val type = ctx.cp.findTypeOrNull(name)
            check(type is JcClassType)
            JcLambdaFeature.addLambdaClass(this, type.jcClass)
            return type
        }

        return ctx.cp.findTypeOrNull(this.typeName)
    } catch (e: Throwable) {
        return null
    }
}

internal val JcClassOrInterface.isSpringFilter: Boolean
    get() {
        val filterType = classpath.findClassOrNull("jakarta.servlet.Filter") ?: return false
        return isSubClassOf(filterType)
    }

internal val JcClassOrInterface.isSpringFilterChain: Boolean
    get() {
        val filterType = classpath.findClassOrNull("jakarta.servlet.FilterChain") ?: return false
        return isSubClassOf(filterType)
    }

internal val JcClassOrInterface.isSpringHandlerInterceptor: Boolean
    get() {
        val filterType = classpath.findClassOrNull("org.springframework.web.servlet.HandlerInterceptor") ?: return false
        return isSubClassOf(filterType)
    }

internal val JcClassOrInterface.isSpringController: Boolean
    get() = annotations.any {
        it.name == "org.springframework.stereotype.Controller"
                || it.name == "org.springframework.web.bind.annotation.RestController"
    }

internal fun JcContext.classesOfLocations(locations: List<JcByteCodeLocation>): Sequence<JcClassOrInterface> {
    return locations
        .asSequence()
        .flatMap { it.classNames ?: emptySet() }
        .mapNotNull { cp.findClassOrNull(it) }
        .filterNot { it is JcUnknownClass }
}
