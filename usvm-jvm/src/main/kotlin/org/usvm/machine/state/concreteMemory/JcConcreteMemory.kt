package org.usvm.machine.state.concreteMemory

import io.ksmt.expr.KBitVec16Value
import io.ksmt.expr.KBitVec32Value
import io.ksmt.expr.KBitVec64Value
import io.ksmt.expr.KBitVec8Value
import io.ksmt.expr.KFp32Value
import io.ksmt.expr.KFp64Value
import io.ksmt.utils.asExpr
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.runBlocking
import org.jacodb.api.jvm.JcArrayType
import org.jacodb.api.jvm.JcClassOrInterface
import org.jacodb.api.jvm.JcClassType
import org.jacodb.api.jvm.JcField
import org.jacodb.api.jvm.JcMethod
import org.jacodb.api.jvm.JcPrimitiveType
import org.jacodb.api.jvm.JcRefType
import org.jacodb.api.jvm.JcType
import org.jacodb.api.jvm.JcTypeVariable
import org.jacodb.api.jvm.JcTypedField
import org.jacodb.api.jvm.ext.annotation
import org.jacodb.api.jvm.ext.boolean
import org.jacodb.api.jvm.ext.byte
import org.jacodb.api.jvm.ext.char
import org.jacodb.api.jvm.ext.double
import org.jacodb.api.jvm.ext.findClass
import org.jacodb.api.jvm.ext.findClassOrNull
import org.jacodb.api.jvm.ext.findFieldOrNull
import org.jacodb.api.jvm.ext.findType
import org.jacodb.api.jvm.ext.findTypeOrNull
import org.jacodb.api.jvm.ext.float
import org.jacodb.api.jvm.ext.humanReadableSignature
import org.jacodb.api.jvm.ext.int
import org.jacodb.api.jvm.ext.isAssignable
import org.jacodb.api.jvm.ext.isEnum
import org.jacodb.api.jvm.ext.long
import org.jacodb.api.jvm.ext.objectType
import org.jacodb.api.jvm.ext.short
import org.jacodb.api.jvm.ext.toType
import org.jacodb.api.jvm.ext.void
import org.jacodb.approximation.Approximations
import org.jacodb.approximation.JcEnrichedVirtualField
import org.jacodb.approximation.JcEnrichedVirtualMethod
import org.jacodb.approximation.OriginalClassName
import org.jacodb.impl.features.classpaths.JcUnknownType
import org.jacodb.impl.features.hierarchyExt
import org.jacodb.impl.fs.LazyClassSourceImpl
import org.usvm.NULL_ADDRESS
import org.usvm.UBoolExpr
import org.usvm.UBoolSort
import org.usvm.UConcreteHeapAddress
import org.usvm.UConcreteHeapRef
import org.usvm.UExpr
import org.usvm.UHeapRef
import org.usvm.UIndexedMocker
import org.usvm.UIteExpr
import org.usvm.UNullRef
import org.usvm.USort
import org.usvm.USymbol
import org.usvm.api.SymbolicIdentityMap
import org.usvm.api.SymbolicList
import org.usvm.api.SymbolicMap
import org.usvm.api.encoder.EncoderFor
import org.usvm.api.encoder.ObjectEncoder
import org.usvm.api.readArrayIndex
import org.usvm.api.readField
import org.usvm.api.util.JcConcreteMemoryClassLoader
import org.usvm.api.util.JcTestInterpreterDecoderApi
import org.usvm.api.util.JcTestStateResolver
import org.usvm.api.util.JcTestStateResolver.ResolveMode
import org.usvm.api.util.Reflection.allocateInstance
import org.usvm.api.util.Reflection.getFieldValue
import org.usvm.api.util.Reflection.invoke
import org.usvm.api.util.Reflection.setFieldValue
import org.usvm.api.util.Reflection.toJavaClass
import org.usvm.collection.array.UArrayIndexLValue
import org.usvm.collection.array.UArrayRegion
import org.usvm.collection.array.UArrayRegionId
import org.usvm.collection.array.length.UArrayLengthLValue
import org.usvm.collection.array.length.UArrayLengthsRegion
import org.usvm.collection.array.length.UArrayLengthsRegionId
import org.usvm.collection.field.UFieldLValue
import org.usvm.collection.field.UFieldsRegion
import org.usvm.collection.field.UFieldsRegionId
import org.usvm.collection.map.length.UMapLengthLValue
import org.usvm.collection.map.length.UMapLengthRegion
import org.usvm.collection.map.length.UMapLengthRegionId
import org.usvm.collection.map.primitive.UMapRegionId
import org.usvm.collection.map.ref.URefMapEntryLValue
import org.usvm.collection.map.ref.URefMapRegion
import org.usvm.collection.map.ref.URefMapRegionId
import org.usvm.collection.set.primitive.USetRegionId
import org.usvm.collection.set.ref.UAllocatedRefSetWithInputElements
import org.usvm.collection.set.ref.UInputRefSetWithInputElements
import org.usvm.collection.set.ref.URefSetEntries
import org.usvm.collection.set.ref.URefSetEntryLValue
import org.usvm.collection.set.ref.URefSetRegion
import org.usvm.collection.set.ref.URefSetRegionId
import org.usvm.constraints.UTypeConstraints
import org.usvm.isFalse
import org.usvm.isTrue
import org.usvm.machine.JcConcreteInvocationResult
import org.usvm.machine.JcContext
import org.usvm.machine.JcMethodCall
import org.usvm.machine.USizeSort
import org.usvm.machine.interpreter.JcExprResolver
import org.usvm.machine.interpreter.JcLambdaCallSite
import org.usvm.machine.interpreter.JcLambdaCallSiteMemoryRegion
import org.usvm.machine.interpreter.JcLambdaCallSiteRegionId
import org.usvm.machine.interpreter.statics.JcStaticFieldLValue
import org.usvm.machine.interpreter.statics.JcStaticFieldRegionId
import org.usvm.machine.interpreter.statics.JcStaticFieldsMemoryRegion
import org.usvm.machine.interpreter.statics.staticFieldsInitializedFlagField
import org.usvm.machine.state.JcMethodResult
import org.usvm.machine.state.JcState
import org.usvm.machine.state.newStmt
import org.usvm.machine.state.skipMethodInvocationWithValue
import org.usvm.machine.state.throwExceptionWithoutStackFrameDrop
import org.usvm.memory.UMemory
import org.usvm.memory.UMemoryRegion
import org.usvm.memory.UMemoryRegionId
import org.usvm.memory.URegistersStack
import org.usvm.mkSizeExpr
import org.usvm.sizeSort
import org.usvm.util.Maybe
import org.usvm.util.jcTypeOf
import org.usvm.util.name
import org.usvm.util.typedField
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

//region Physical Address

private data class PhysicalAddress(
    val obj: Any?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PhysicalAddress

        return obj === other.obj
    }

    override fun hashCode(): Int {
        return System.identityHashCode(obj)
    }

    val isNull: Boolean by lazy {
        obj == null
    }
}

//endregion

//region Child Kind

private interface ChildKind

private data class FieldChildKind(
    val field: Field
) : ChildKind

private data class ArrayIndexChildKind(
    val index: Int,
) : ChildKind

//endregion

//region Cell

private data class Cell(
    val address: PhysicalAddress?
) {
    val isConcrete = address != null
    val isSymbolic = address == null

    companion object {
        operator fun invoke(): Cell {
            return Cell(null)
        }
    }
}

//endregion

//region Helpers And Extensions

@Suppress("RecursivePropertyAccessor")
private val JcClassType.allFields: List<JcTypedField>
    get() = declaredFields + (superType?.allFields ?: emptyList())

@Suppress("RecursivePropertyAccessor")
private val Class<*>.allFields: Array<Field>
    get() = declaredFields + (superclass?.allFields ?: emptyArray())

val JcClassType.allInstanceFields: List<JcTypedField>
    get() = allFields.filter { !it.isStatic }

private val JcClassType.declaredInstanceFields: List<JcTypedField>
    get() = declaredFields.filter { !it.isStatic }

private val Class<*>.allInstanceFields: List<Field>
    get() = allFields.filter { !Modifier.isStatic(it.modifiers) }

private fun Field.getFieldValue(obj: Any): Any? {
    isAccessible = true
    return get(obj)
}

private fun JcField.getFieldValue(obj: Any): Any? {
    if (this is JcEnrichedVirtualField) {
        val javaField = obj.javaClass.allInstanceFields.find { it.name == name }!!
        return javaField.getFieldValue(obj)
    }

    return this.getFieldValue(JcConcreteMemoryClassLoader, obj)
}

private fun Field.setFieldValue(obj: Any, value: Any?) {
    isAccessible = true
    set(obj, value)
}

@Suppress("UNCHECKED_CAST")
private fun <Value> Any.getArrayValue(index: Int): Value {
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
private fun <Value> Any.setArrayValue(index: Int, value: Value) {
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

private val JcField.toJavaField: Field?
    get() = enclosingClass.toType().toJavaClass(JcConcreteMemoryClassLoader).allFields.find { it.name == name }

private fun JcEnrichedVirtualMethod.getMethod(ctx: JcContext): JcMethod? {
    val originalClassName = OriginalClassName(enclosingClass.name)
    val approximationClassName =
        Approximations.findApproximationByOriginOrNull(originalClassName)
            ?: return null
    return ctx.cp.findClassOrNull(approximationClassName)
        ?.declaredMethods
        ?.find { it.name == this.name }
}

@Suppress("RecursivePropertyAccessor")
private val JcType.isEnum: Boolean
    get() = this is JcClassType && (this.jcClass.isEnum || this.superType?.isEnum == true)

private val JcType.isEnumArray: Boolean
    get() = this is JcArrayType && this.elementType.let { it is JcClassType && it.jcClass.isEnum }

private val JcType.internalName: String
    get() = if (this is JcClassType) this.name else this.typeName

private val notTrackedTypes = setOf(
    "java.lang.Class",
)

private val Class<*>.isProxy: Boolean
    get() = Proxy.isProxyClass(this)

private val Class<*>.isLambda: Boolean
    get() = typeName.contains('/') && typeName.contains("\$\$Lambda\$")

private val Class<*>.notTracked: Boolean
    get() =
        this.isPrimitive ||
                this.isEnum ||
                notTrackedTypes.contains(this.name)

private val JcType.notTracked: Boolean
    get() =
        this is JcPrimitiveType ||
                this is JcClassType &&
                (this.jcClass.isEnum || notTrackedTypes.contains(this.name))

private val immutableTypes = setOf(
    "java.lang.Integer",
    "java.lang.Byte",
    "java.lang.Short",
    "java.lang.Long",
    "java.lang.Float",
    "java.lang.Double",
    "java.lang.Boolean",
    "java.lang.Character",
    "java.lang.String",
    "jdk.internal.loader.ClassLoaders\$AppClassLoader",
    "java.security.AllPermission",
    "java.net.NetPermission",
    "java.lang.reflect.Method",
    "java.lang.Class",
)

private val Class<*>.isImmutable: Boolean
    get() = immutableTypes.contains(this.name) || ClassLoader::class.java.isAssignableFrom(this)

private val JcType.isImmutable: Boolean
    get() = this is JcClassType && immutableTypes.contains(this.name)

private val Class<*>.isSolid: Boolean
    get() =
        notTracked ||
                immutableTypes.contains(this.name) ||
                this.isArray && this.componentType.notTracked

private val JcType.isSolid: Boolean
    get() =
        notTracked ||
                this is JcClassType && immutableTypes.contains(this.name) ||
                this is JcArrayType && this.elementType.notTracked

private fun Class<*>.toJcType(ctx: JcContext): JcType? {
    return ctx.cp.findTypeOrNull(this.typeName)
}

//endregion

private typealias childMapType = MutableMap<ChildKind, Cell>
private typealias childrenType = MutableMap<PhysicalAddress, childMapType>
private typealias parentMapType = MutableMap<PhysicalAddress, ChildKind>
private typealias parentsType = MutableMap<PhysicalAddress, parentMapType>

//region Concrete Memory Bindings

private class JcConcreteMemoryBindings(
    private val ctx: JcContext,
    private val typeConstraints: UTypeConstraints<JcType>,
    private val physToVirt: MutableMap<PhysicalAddress, UConcreteHeapAddress>,
    private val virtToPhys: MutableMap<UConcreteHeapAddress, PhysicalAddress>,
    var isWritable: Boolean,
    private val children: MutableMap<PhysicalAddress, childMapType>,
    private val parents: MutableMap<PhysicalAddress, parentMapType>,
    private val fullyConcretes: MutableSet<PhysicalAddress>,
    private val newAddresses: MutableSet<UConcreteHeapAddress>,
    private val backtrackChanges: MutableMap<PhysicalAddress, PhysicalAddress>,
) {
    constructor(
        ctx: JcContext,
        typeConstraints: UTypeConstraints<JcType>
    ) : this(
        ctx,
        typeConstraints,
        mutableMapOf(),
        mutableMapOf(),
        true,
        mutableMapOf(),
        mutableMapOf(),
        mutableSetOf(),
        mutableSetOf(),
        mutableMapOf(),
    )

    init {
        JcConcreteMemoryClassLoader.cp = ctx.cp
    }

    //region Primitives

    fun typeOf(address: UConcreteHeapAddress): JcType {
        return typeConstraints.typeOf(address)
    }

    fun contains(address: UConcreteHeapAddress): Boolean {
        return virtToPhys.contains(address)
    }

    fun tryVirtToPhys(address: UConcreteHeapAddress): Any? {
        return virtToPhys[address]?.obj
    }

    fun virtToPhys(address: UConcreteHeapAddress): Any {
        return virtToPhys[address]?.obj!!
    }

    fun tryFullyConcrete(address: UConcreteHeapAddress): Any? {
        val phys = virtToPhys[address]
        if (phys != null && checkConcreteness(phys)) {
            return phys.obj
        }
        return null
    }

    fun tryPhysToVirt(obj: Any): UConcreteHeapAddress? {
        return physToVirt[PhysicalAddress(obj)]
    }

    fun makeNonWritable() {
        isWritable = false
    }

    //endregion

    //region Concreteness Tracking

    private fun hasFullyConcreteParent(phys: PhysicalAddress): Boolean {
        val tested = mutableSetOf<PhysicalAddress>()
        val queue: Queue<PhysicalAddress> = LinkedList()
        var contains = false
        var child: PhysicalAddress? = phys

        while (!contains && child != null) {
            if (tested.add(child)) {
                contains = fullyConcretes.contains(child)
                parents[child]?.forEach {
                    val parent = it.key
                    queue.add(parent)
                }
            }
            child = queue.poll()
        }

        return contains
    }

    private fun addToParents(parent: PhysicalAddress, child: PhysicalAddress, childKind: ChildKind) {
        check(parent.obj != null)
        check(child.obj != null)
        val parentMap = parents.getOrPut(child) { mutableMapOf() }
        parentMap[parent] = childKind
    }

    private fun addChild(parent: PhysicalAddress, child: PhysicalAddress, childKind: ChildKind, update: Boolean) {
        if (parent != child) {
            if (child.isNull && update) {
                children[parent]?.remove(childKind)
            } else if (!child.isNull) {
                val childMap = children[parent]
                if (childMap != null && update) {
                    childMap[childKind] = Cell(child)
                } else if (childMap != null) {
                    val cell = childMap[childKind]
                    if (cell != null) {
                        check(!cell.isConcrete || cell.address == child)
                    } else {
                        childMap[childKind] = Cell(child)
                    }
                } else {
                    val newChildMap = mutableMapOf<ChildKind, Cell>()
                    newChildMap[childKind] = Cell(child)
                    children[parent] = newChildMap
                }

                if (update && hasFullyConcreteParent(parent) && !checkConcreteness(child))
                    removeFromFullyConcretesRec(parent)

                addToParents(parent, child, childKind)
            }
        }
    }

    private fun trackChild(parent: PhysicalAddress, child: PhysicalAddress, childKind: ChildKind) {
        addChild(parent, child, childKind, false)
    }

    private fun trackChild(parent: Any?, child: Any?, childKind: ChildKind) {
        check(parent !is PhysicalAddress && child !is PhysicalAddress)
        trackChild(PhysicalAddress(parent), PhysicalAddress(child), childKind)
    }

    private fun setChild(parent: PhysicalAddress, child: PhysicalAddress, childKind: ChildKind) {
        addChild(parent, child, childKind, true)
    }

    private fun setChild(parent: Any?, child: Any?, childKind: ChildKind) {
        check(parent !is PhysicalAddress && child !is PhysicalAddress)
        setChild(PhysicalAddress(parent), PhysicalAddress(child), childKind)
    }

    private fun checkConcreteness(phys: PhysicalAddress): Boolean {
        val tracked = mutableSetOf<PhysicalAddress>()
        return checkConcretenessRec(phys, tracked)
    }

    private fun checkConcretenessRec(phys: PhysicalAddress, tracked: MutableSet<PhysicalAddress>): Boolean {
        // TODO: cache not fully concrete objects #CM
        if (fullyConcretes.contains(phys) || !tracked.add(phys))
            return true

        var allConcrete = true
        children[phys]?.forEach {
            if (allConcrete) {
                val child = it.value.address
                allConcrete = child != null && checkConcretenessRec(child, tracked)
            }
        }

        if (allConcrete) fullyConcretes.add(phys)

        return allConcrete
    }

    fun reTrackObject(obj: Any?) {
        if (obj == null)
            return

        val queue: Queue<PhysicalAddress> = LinkedList()
        val tracked = mutableSetOf<PhysicalAddress>()
        var phys: PhysicalAddress? = PhysicalAddress(obj)

        while (phys != null) {
            if (tracked.add(phys)) {
                val current = phys.obj ?: return
                val type = current.javaClass
                when {
                    type.isSolid -> continue
                    type.isArray -> {
                        val elemType = type.componentType
                        if (elemType.notTracked) continue
                        when (current) {
                            is Array<*> -> {
                                current.forEachIndexed { i, v ->
                                    val child = PhysicalAddress(v)
                                    if (!elemType.isSolid)
                                        queue.add(child)
                                    setChild(phys!!, child, ArrayIndexChildKind(i))
                                }
                            }
                            else -> error("reTrack: unexpected array $current")
                        }
                    }

                    else -> {
                        for (field in type.allInstanceFields) {
                            try {
                                val fieldType = field.type
                                if (fieldType.notTracked) continue
                                val childObj = field.getFieldValue(current)
                                val child = PhysicalAddress(childObj)
                                if (!fieldType.isSolid)
                                    queue.add(child)
                                setChild(phys, child, FieldChildKind(field))
                            } catch (e: Exception) {
                                error("ReTrack class ${type.name} failed on field ${field.name}, cause: ${e.message}")
                            }
                        }
                    }
                }
            }
            phys = queue.poll()
        }
    }

    private fun checkTrackCopy(dstArrayType: Class<*>, dstFromIdx: Int, dstToIdx: Int): Boolean {
        check(dstFromIdx <= dstToIdx)
        val elemType = dstArrayType.componentType
        return !elemType.notTracked
    }

    private fun trackCopy(updatedDstArray: Array<*>, dstArrayType: Class<*>, dstFromIdx: Int, dstToIdx: Int) {
        if (!checkTrackCopy(dstArrayType, dstFromIdx, dstToIdx)) return

        for (i in dstFromIdx..<dstToIdx) {
            setChild(updatedDstArray, updatedDstArray[i], ArrayIndexChildKind(i))
        }
    }

    private fun removeFromFullyConcretesRec(phys: PhysicalAddress) {
        val queue: Queue<PhysicalAddress> = LinkedList()
        val removed = mutableSetOf<PhysicalAddress>()
        var child: PhysicalAddress? = phys

        while (child != null) {
            if (removed.add(phys)) {
                fullyConcretes.remove(child)
                parents[child]?.forEach {
                    val parent = it.key
                    queue.add(parent)
                }
            }
            child = queue.poll()
        }
    }

    private fun markSymbolic(phys: PhysicalAddress) {
        parents[phys]?.forEach {
            val parent = it.key
            children[parent]!![it.value] = Cell()
        }
    }

    fun symbolicMembers(address: UConcreteHeapAddress): List<ChildKind> {
        check(virtToPhys.contains(address))
        val phys = virtToPhys[address]!!
        val symbolicMembers = mutableListOf<ChildKind>()
        children[phys]?.forEach {
            val child = it.value.address
            if (child == null || !checkConcreteness(child))
                symbolicMembers.add(it.key)
        }

        return symbolicMembers
    }

    //endregion

    //region Allocation

    private val forbiddenTypes = setOf<String>(
//        "java.net.NetPermission",
//        "org.springframework.boot.BootstrapRegistryInitializer"
    )

    private fun shouldAllocate(type: JcType): Boolean {
        return !forbiddenTypes.contains(type.internalName)
    }

    private val interningTypes = setOf<JcType>(
        ctx.stringType,
        ctx.classType
    )

    fun allocate(address: UConcreteHeapAddress, obj: Any, type: JcType) {
        check(address != NULL_ADDRESS)
        check(!virtToPhys.containsKey(address))
        val physicalAddress = PhysicalAddress(obj)
        virtToPhys[address] = physicalAddress
        physToVirt[physicalAddress] = address
        typeConstraints.allocate(address, type)
        newAddresses.add(address)
    }

    private fun allocate(obj: Any, type: JcType, static: Boolean): UConcreteHeapAddress {
        if (interningTypes.contains(type)) {
            val address = tryPhysToVirt(obj)
            if (address != null) {
                return address
            }
        }

        val address =
            if (type.isEnum || type.isEnumArray || static)
                ctx.addressCounter.freshStaticAddress()
            else ctx.addressCounter.freshAllocatedAddress()
        allocate(address, obj, type)
        return address
    }

    private fun allocateIfShould(obj: Any, type: JcType): UConcreteHeapAddress? {
        if (shouldAllocate(type)) {
            return allocate(obj, type, false)
        }
        return null
    }

    private fun allocateIfShould(type: JcType, static: Boolean): UConcreteHeapAddress? {
        if (shouldAllocate(type)) {
            val obj = createDefault(type) ?: return null
            return allocate(obj, type, static)
        }
        return null
    }

    fun allocate(obj: Any, type: JcType): UConcreteHeapAddress? {
        return allocateIfShould(obj, type)
    }

    class LambdaInvocationHandler : InvocationHandler {

        private var methodName: String? = null
        private var actualMethod: JcMethod? = null
        private var closureArgs: List<Any?> = listOf()

        fun init(actualMethod: JcMethod, methodName: String, args: List<Any?>) {
            check(actualMethod !is JcEnrichedVirtualMethod)
            this.methodName = methodName
            this.actualMethod = actualMethod
            closureArgs = args
        }

        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            if (methodName != null && methodName == method.name) {
                val allArgs =
                    if (args == null) closureArgs
                    else closureArgs + args
                return actualMethod!!.invoke(JcConcreteMemoryClassLoader, null, allArgs)
            }

            val newArgs = args ?: arrayOf()
            return InvocationHandler.invokeDefault(proxy, method, *newArgs)
        }
    }

    private fun createProxy(type: JcClassType): Any {
        check(type.jcClass.isInterface)
        return Proxy.newProxyInstance(
            JcConcreteMemoryClassLoader,
            arrayOf(type.toJavaClass(JcConcreteMemoryClassLoader)),
            LambdaInvocationHandler()
        )
    }

    private fun createDefault(type: JcType): Any? {
        try {
            return when (type) {
                is JcArrayType -> type.allocateInstance(JcConcreteMemoryClassLoader, 1)
                is JcClassType -> {
                    if (type.jcClass.isInterface) createProxy(type)
                    else type.allocateInstance(JcConcreteMemoryClassLoader)
                }

                is JcPrimitiveType -> null
                else -> error("JcConcreteMemoryBindings.allocateDefault: unexpected type $type")
            }
        } catch (e: Exception) {
            error("failed to allocate ${type.internalName}")
        }
    }

    fun allocateDefaultConcrete(type: JcType): UConcreteHeapAddress? {
        return allocateIfShould(type, false)
    }

    fun allocateDefaultStatic(type: JcType): UConcreteHeapAddress? {
        return allocateIfShould(type, true)
    }

    //endregion

    //region Reading

    fun readClassField(address: UConcreteHeapAddress, field: Field): Any? {
        val obj = virtToPhys(address)
        val value = field.getFieldValue(obj)

        val type = field.type
        if (!type.notTracked)
            trackChild(obj, value, FieldChildKind(field))

        return value
    }

    fun readArrayIndex(address: UConcreteHeapAddress, index: Int): Any? {
        val obj = virtToPhys(address)
        val value =
            when (obj) {
                is IntArray -> obj[index]
                is ByteArray -> obj[index]
                is CharArray -> obj[index]
                is LongArray -> obj[index]
                is FloatArray -> obj[index]
                is ShortArray -> obj[index]
                is DoubleArray -> obj[index]
                is BooleanArray -> obj[index]
                is Array<*> -> obj[index]
                is String -> obj[index]
                else -> error("JcConcreteMemoryBindings.readArrayIndex: unexpected array $obj")
            }

        val arrayType = typeConstraints.typeOf(address)
        arrayType as JcArrayType
        val elemType = arrayType.elementType
        if (!elemType.notTracked)
            trackChild(obj, value, ArrayIndexChildKind(index))

        return value
    }

    // TODO: need "GetAllArrayData"?

    fun readArrayLength(address: UConcreteHeapAddress): Int {
        return when (val obj = virtToPhys(address)) {
            is IntArray -> obj.size
            is ByteArray -> obj.size
            is CharArray -> obj.size
            is LongArray -> obj.size
            is FloatArray -> obj.size
            is ShortArray -> obj.size
            is DoubleArray -> obj.size
            is BooleanArray -> obj.size
            is Array<*> -> obj.size
            is String -> obj.length
            else -> error("JcConcreteMemoryBindings.readArrayLength: unexpected array $obj")
        }
    }

    fun readMapValue(address: UConcreteHeapAddress, key: Any?): Any? {
        val obj = virtToPhys(address)
        obj as Map<*, *>
        return obj[key]
    }

    fun readMapLength(address: UConcreteHeapAddress): Int {
        val obj = virtToPhys(address)
        obj as Map<*, *>
        return obj.size
    }

    fun checkSetContains(address: UConcreteHeapAddress, element: Any?): Boolean {
        val obj = virtToPhys(address)
        obj as Set<*>
        return obj.contains(element)
    }

    fun readInvocationHandler(address: UConcreteHeapAddress): LambdaInvocationHandler {
        val obj = virtToPhys(address)
        check(Proxy.isProxyClass(obj.javaClass))
        return Proxy.getInvocationHandler(obj) as LambdaInvocationHandler
    }

    //endregion

    //region Writing

    fun writeClassField(address: UConcreteHeapAddress, field: Field, value: Any?): Boolean {
        if (isWritable) {
            val obj = virtToPhys(address)
            field.setFieldValue(obj, value)

            if (!field.type.notTracked)
                setChild(obj, value, FieldChildKind(field))
        }
        return isWritable
    }

    fun <Value> writeArrayIndex(address: UConcreteHeapAddress, index: Int, value: Value): Boolean {
        if (isWritable) {
            val obj = virtToPhys(address)
            obj.setArrayValue(index, value)

            val arrayType = typeConstraints.typeOf(address)
            arrayType as JcArrayType
            val elemType = arrayType.elementType
            if (!elemType.notTracked)
                setChild(obj, value, ArrayIndexChildKind(index))
        }
        return isWritable
    }

    @Suppress("UNCHECKED_CAST")
    fun <Value> initializeArray(address: UConcreteHeapAddress, contents: List<Pair<Int, Value>>): Boolean {
        val success = isWritable || newAddresses.contains(address)
        if (success) {
            val obj = virtToPhys(address)
            val arrayType = obj.javaClass
            check(arrayType.isArray)
            val elemType = arrayType.componentType
            when (obj) {
                is IntArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Int
                    }
                }

                is ByteArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Byte
                    }
                }

                is CharArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Char
                    }
                }

                is LongArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Long
                    }
                }

                is FloatArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Float
                    }
                }

                is ShortArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Short
                    }
                }

                is DoubleArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Double
                    }
                }

                is BooleanArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Boolean
                    }
                }

                is Array<*> -> {
                    obj as Array<Value>
                    for ((index, value) in contents) {
                        obj[index] = value
                        if (!elemType.notTracked)
                            setChild(obj, value, ArrayIndexChildKind(index))
                    }
                }

                else -> error("JcConcreteMemoryBindings.initializeArray: unexpected array $obj")
            }
        }
        return success
    }

    fun writeArrayLength(address: UConcreteHeapAddress, length: Int): Boolean {
        val arrayType = typeConstraints.typeOf(address)
        arrayType as JcArrayType
        val oldObj = virtToPhys[address]
        val newObj = arrayType.allocateInstance(JcConcreteMemoryClassLoader, length)
        virtToPhys.remove(address)
        physToVirt.remove(oldObj)
        typeConstraints.remove(address)
        allocate(address, newObj, arrayType)

        return true
    }

    @Suppress("UNCHECKED_CAST")
    fun writeMapValue(address: UConcreteHeapAddress, key: Any?, value: Any?): Boolean {
        if (isWritable) {
            val obj = virtToPhys(address)
            obj as MutableMap<Any?, Any?>
            obj[key] = value
        }
        return isWritable
    }

    @Suppress("UNCHECKED_CAST")
    fun writeMapLength(address: UConcreteHeapAddress, length: Int): Boolean {
        val obj = virtToPhys(address)
        obj as Map<Any?, Any?>
        check(obj.size == length)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    fun changeSetContainsElement(address: UConcreteHeapAddress, element: Any?, contains: Boolean): Boolean {
        if (isWritable) {
            val obj = virtToPhys(address)
            obj as MutableSet<Any?>
            if (contains)
                obj.add(element)
            else
                obj.remove(element)
        }
        return isWritable
    }

    //endregion

    //region Copying

    @Suppress("UNCHECKED_CAST")
    fun arrayCopy(
        srcAddress: UConcreteHeapAddress,
        dstAddress: UConcreteHeapAddress,
        fromSrcIdx: Int,
        fromDstIdx: Int,
        toDstIdx: Int
    ): Boolean {
        if (isWritable) {
            val srcArray = virtToPhys(srcAddress)
            val dstArray = virtToPhys(dstAddress)
            val toSrcIdx = toDstIdx - fromDstIdx + fromSrcIdx
            val dstArrayType = dstArray.javaClass
            val dstArrayElemType = dstArrayType.componentType
            when {
                srcArray is IntArray && dstArray is IntArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is ByteArray && dstArray is ByteArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is CharArray && dstArray is CharArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is LongArray && dstArray is LongArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is FloatArray && dstArray is FloatArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is ShortArray && dstArray is ShortArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is DoubleArray && dstArray is DoubleArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is BooleanArray && dstArray is BooleanArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is Array<*> && dstArray is Array<*> -> {
                    dstArray as Array<Any?>
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                    trackCopy(dstArray, dstArrayType, fromDstIdx, toDstIdx)
                }

                else -> error("JcConcreteMemoryBindings.arrayCopy: unexpected arrays $srcArray, $dstArray")
            }
        }
        return isWritable
    }

    //endregion

    //region Map Merging

    @Suppress("UNCHECKED_CAST")
    fun mapMerge(srcAddress: UConcreteHeapAddress, dstAddress: UConcreteHeapAddress): Boolean {
        if (isWritable) {
            val srcMap = virtToPhys(srcAddress) as MutableMap<Any, Any>
            val dstMap = virtToPhys(dstAddress) as MutableMap<Any, Any>
            dstMap.putAll(srcMap)
        }

        return isWritable
    }

    //endregion

    //region Set Union

    @Suppress("UNCHECKED_CAST")
    fun setUnion(srcAddress: UConcreteHeapAddress, dstAddress: UConcreteHeapAddress): Boolean {
        if (isWritable) {
            val srcSet = virtToPhys(srcAddress) as MutableSet<Any>
            val dstSet = virtToPhys(dstAddress) as MutableSet<Any>
            dstSet.addAll(srcSet)
        }

        return isWritable
    }

    //endregion

    //region Backtracking

    private fun cloneObject(obj: Any): Any {
        // TODO: (1) do not clone java.lang and java.lang.reflect
        // TODO: (2) do not clone Proxy and Lambda, but go to it's children and clone them (closure)
        // TODO: (3) do not clone ByteBuffer (mapping on memory)
        // TODO: (4) do not clone objects without fields (marker objects)
        // TODO: (5) use getFieldValue and setFieldValue from instrumentation.Reflection
        val type = obj.javaClass
        val jcType = type.toJcType(ctx) ?: return obj
        when {
            type.isImmutable -> return obj
            // TODO: clone lambda and maybe proxy #CM
            type.isProxy || type.isLambda -> return obj
            jcType is JcArrayType -> {
                return when (obj) {
                    is IntArray -> obj.clone()
                    is ByteArray -> obj.clone()
                    is CharArray -> obj.clone()
                    is LongArray -> obj.clone()
                    is FloatArray -> obj.clone()
                    is ShortArray -> obj.clone()
                    is DoubleArray -> obj.clone()
                    is BooleanArray -> obj.clone()
                    is Array<*> -> obj.clone()
                    else -> error("cloneObject: unexpected array $obj")
                }
            }
            jcType is JcClassType -> {
                try {
                    val newObj = jcType.allocateInstance(JcConcreteMemoryClassLoader)
                    for (field in type.allInstanceFields) {
                        val value = field.getFieldValue(obj)
                        field.setFieldValue(newObj, value)
                    }
                    return newObj
                } catch (e: Exception) {
                    println("cloneObject failed on class ${type.name}")
                    return obj
                }
            }
            else -> return obj
        }
    }

    fun addObjectToBacktrack(obj: Any) {
        val type = obj.javaClass
        if (type.isImmutable)
            return

        val oldPhys = PhysicalAddress(obj)
        val clonedPhys = PhysicalAddress(cloneObject(obj))
        backtrackChanges[oldPhys] = clonedPhys
    }

    fun addObjectToBacktrackRec(obj: Any) {
        val queue: Queue<Any?> = LinkedList()
        queue.add(obj)
        // TODO: if object already in backtrackChanges, do not proceed #CM
        while (queue.isNotEmpty()) {
            val current = queue.poll() ?: continue
            if (backtrackChanges.containsKey(PhysicalAddress(current)))
                continue

            addObjectToBacktrack(current)
            val type = current.javaClass
            when {
                type.isImmutable -> continue
                current is Array<*> -> queue.addAll(current)
                else -> {
                    for (field in type.allInstanceFields) {
                        try {
                            queue.add(field.getFieldValue(current))
                        } catch (e: Throwable) {
                            println("addObjectToBacktrackRec failed on class ${type.name}")
                            continue
                        }
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun applyBacktrack() {
        for ((oldPhys, clonedPhys) in backtrackChanges) {
            val oldObj = oldPhys.obj ?: continue
            val obj = clonedPhys.obj ?: continue
            val type = obj.javaClass
            check(oldObj.javaClass == type)
            when {
                type.isImmutable -> continue
                type.isArray -> {
                    when {
                        obj is IntArray && oldObj is IntArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is ByteArray && oldObj is ByteArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is CharArray && oldObj is CharArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is LongArray && oldObj is LongArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is FloatArray && oldObj is FloatArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is ShortArray && oldObj is ShortArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is DoubleArray && oldObj is DoubleArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is BooleanArray && oldObj is BooleanArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is Array<*> && oldObj is Array<*> -> {
                            oldObj as Array<Any?>
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        else -> error("applyBacktrack: unexpected array $obj")
                    }
                }

                else -> {
                    for (field in type.allInstanceFields) {
                        try {
                            val value = field.getFieldValue(obj)
                            field.setFieldValue(oldObj, value)
                        } catch (e: Exception) {
                            error("applyBacktrack class ${type.name} failed on field ${field.name}, cause: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    //endregion

    //region Removing

    fun remove(address: UConcreteHeapAddress) {
        val phys = virtToPhys.remove(address)!!
        removeFromFullyConcretesRec(phys)
        markSymbolic(phys)
    }

    //endregion

    //region Copy

    private fun copyChildren(): MutableMap<PhysicalAddress, childMapType> {
        val newChildren = mutableMapOf<PhysicalAddress, childMapType>()
        for ((parent, childMap) in children) {
            val newChildMap = childMap.toMutableMap()
            newChildren[parent] = newChildMap
        }

        return newChildren
    }

    private fun copyParents(): MutableMap<PhysicalAddress, parentMapType> {
        val newParents = mutableMapOf<PhysicalAddress, parentMapType>()
        for ((child, parentMap) in parents) {
            val newParentMap = parentMap.toMutableMap()
            newParents[child] = newParentMap
        }

        return newParents
    }

    private fun copyBacktrackChanges(): MutableMap<PhysicalAddress, PhysicalAddress> {
        check(backtrackChanges.isEmpty())
        return backtrackChanges.toMutableMap();
    }

    fun copy(typeConstraints: UTypeConstraints<JcType>): JcConcreteMemoryBindings {
        newAddresses.clear()
        return JcConcreteMemoryBindings(
            ctx,
            typeConstraints,
            physToVirt.toMutableMap(),
            virtToPhys.toMutableMap(),
            isWritable,
            copyChildren(),
            copyParents(),
            fullyConcretes.toMutableSet(),
            mutableSetOf(),
            copyBacktrackChanges(),
        )
    }

    //endregion
}

//endregion

//region Concrete Regions

interface JcConcreteRegion

//region Concrete Fields Region

private class JcConcreteFieldRegion<Sort : USort>(
    private val regionId: UFieldsRegionId<JcField, Sort>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: UFieldsRegion<JcField, Sort>,
    private val marshall: Marshall
) : UFieldsRegion<JcField, Sort>, JcConcreteRegion {

    private val jcField by lazy { regionId.field }
    private val javaField by lazy { jcField.toJavaField }
    private val isApproximation by lazy { javaField == null }
//    private val isPrimitiveApproximation by lazy { isApproximation && jcField.name == "value" }
    private val sort by lazy { regionId.sort }
    private val typedField: JcTypedField by lazy { jcField.typedField }
    private val fieldType: JcType by lazy { typedField.type }
    private val isSyntheticClassField: Boolean by lazy { jcField == ctx.classTypeSyntheticField }

    private fun writeToBase(
        key: UFieldLValue<JcField, Sort>,
        value: UExpr<Sort>,
        guard: UBoolExpr
    ) {
        baseRegion = baseRegion.write(key, value, guard) as UFieldsRegion<JcField, Sort>
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(key: UFieldLValue<JcField, Sort>): UExpr<Sort> {
        check(jcField == key.field)
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            if (isSyntheticClassField) {
                val type = bindings.virtToPhys(address) as Class<*>
                val jcType = ctx.cp.findTypeOrNull(type.typeName)!!
                jcType as JcRefType
                val allocated = bindings.allocateDefaultConcrete(jcType)!!
                return ctx.mkConcreteHeapRef(allocated) as UExpr<Sort>
            }

            if (!isApproximation) {
                val fieldObj = bindings.readClassField(address, javaField!!)
                return marshall.objToExpr(fieldObj, fieldType) // TODO: use reflect type? #CM
            }

            marshall.encode(address)
        }

        return baseRegion.read(key)
    }

    override fun write(
        key: UFieldLValue<JcField, Sort>,
        value: UExpr<Sort>,
        guard: UBoolExpr
    ): UMemoryRegion<UFieldLValue<JcField, Sort>, Sort> {
        check(jcField == key.field)
        val ref = key.ref
        if (!isSyntheticClassField && ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            if (!isApproximation) {
                val objValue = marshall.tryExprToObj(value, fieldType)
                val writeIsConcrete = objValue.hasValue && guard.isTrue
                if (writeIsConcrete && bindings.writeClassField(address, javaField!!, objValue.value))
                    return this
            }

            marshall.unmarshallClass(address)
        }

        writeToBase(key, value, guard)

        return this
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallField(ref: UConcreteHeapRef, obj: Any) {
        val lvalue = UFieldLValue(sort, ref, jcField)
        val fieldObj = jcField.getFieldValue(obj)
        val rvalue = marshall.objToExpr<USort>(fieldObj, fieldType) as UExpr<Sort>
        writeToBase(lvalue, rvalue, ctx.trueExpr)
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteFieldRegion<Sort> {
        return JcConcreteFieldRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Array Region

private class JcConcreteArrayRegion<Sort : USort>(
    private val regionId: UArrayRegionId<JcType, Sort, USizeSort>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: UArrayRegion<JcType, Sort, USizeSort>,
    private val marshall: Marshall
) : UArrayRegion<JcType, Sort, USizeSort>, JcConcreteRegion {

    private val indexType by lazy { ctx.cp.int }
    private val sort by lazy { regionId.sort }

    private fun writeToBase(
        key: UArrayIndexLValue<JcType, Sort, USizeSort>,
        value: UExpr<Sort>,
        guard: UBoolExpr
    ) {
        baseRegion = baseRegion.write(key, value, guard) as UArrayRegion<JcType, Sort, USizeSort>
    }

    override fun memcpy(
        srcRef: UHeapRef,
        dstRef: UHeapRef,
        type: JcType,
        elementSort: Sort,
        fromSrcIdx: UExpr<USizeSort>,
        fromDstIdx: UExpr<USizeSort>,
        toDstIdx: UExpr<USizeSort>,
        operationGuard: UBoolExpr
    ): UArrayRegion<JcType, Sort, USizeSort> {
        if (srcRef is UConcreteHeapRef &&
            bindings.contains(srcRef.address) &&
            dstRef is UConcreteHeapRef &&
            bindings.contains(dstRef.address)
        ) {
            val fromSrcIdxObj = marshall.tryExprToObj(fromSrcIdx, indexType)
            val fromDstIdxObj = marshall.tryExprToObj(fromDstIdx, indexType)
            val toDstIdxObj = marshall.tryExprToObj(toDstIdx, indexType)
            val isConcreteCopy =
                fromSrcIdxObj.hasValue && fromDstIdxObj.hasValue && toDstIdxObj.hasValue && operationGuard.isTrue
            val success =
                isConcreteCopy &&
                    bindings.arrayCopy(
                        srcRef.address,
                        dstRef.address,
                        fromSrcIdxObj.value as Int,
                        fromDstIdxObj.value as Int,
                        toDstIdxObj.value as Int + 1 // Incrementing 'toDstIdx' index to make it exclusive
                    )
            if (success) {
                return this
            }
        }

        if (srcRef is UConcreteHeapRef)
            marshall.unmarshallArray(srcRef.address)

        if (dstRef is UConcreteHeapRef)
            marshall.unmarshallArray(dstRef.address)

        baseRegion = baseRegion.memcpy(srcRef, dstRef, type, elementSort, fromSrcIdx, fromDstIdx, toDstIdx, operationGuard)

        return this
    }

    override fun initializeAllocatedArray(
        address: UConcreteHeapAddress,
        arrayType: JcType,
        sort: Sort,
        content: Map<UExpr<USizeSort>, UExpr<Sort>>,
        operationGuard: UBoolExpr
    ): UArrayRegion<JcType, Sort, USizeSort> {
        if (bindings.contains(address)) {
            if (operationGuard.isTrue) {
                val jcArrayType =
                    if (arrayType is JcArrayType) arrayType
                    else bindings.typeOf(address) as JcArrayType
                val elemType = jcArrayType.elementType
                val elems = content.mapNotNull { (index, value) ->
                    val idx = marshall.tryExprToObj(index, ctx.cp.int)
                    val elem = marshall.tryExprToObj(value, elemType)
                    if (idx.hasValue && elem.hasValue) (idx.value as Int) to elem.value
                    else null
                }
                if (elems.size == content.size && bindings.initializeArray(address, elems)) {
                    return this
                }
            }
            marshall.unmarshallArray(address)
        }

        baseRegion = baseRegion.initializeAllocatedArray(address, arrayType, sort, content, operationGuard)

        return this
    }

    override fun read(key: UArrayIndexLValue<JcType, Sort, USizeSort>): UExpr<Sort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val indexObj = marshall.tryExprToObj(key.index, indexType)
            if (indexObj.hasValue) {
                val valueObj = bindings.readArrayIndex(address, indexObj.value as Int)
                val elemType = (bindings.typeOf(address) as JcArrayType).elementType
                return marshall.objToExpr(valueObj, elemType)
            }

            // TODO: do not unmarshall, optimize via GetAllArrayData #CM
            marshall.unmarshallArray(address)
        }

        return baseRegion.read(key)
    }

    override fun write(
        key: UArrayIndexLValue<JcType, Sort, USizeSort>,
        value: UExpr<Sort>,
        guard: UBoolExpr
    ): UMemoryRegion<UArrayIndexLValue<JcType, Sort, USizeSort>, Sort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val arrayType = bindings.typeOf(address) as JcArrayType
            val valueObj = marshall.tryExprToObj(value, arrayType.elementType)
            val indexObj = marshall.tryExprToObj(key.index, indexType)
            val isConcreteWrite = valueObj.hasValue && indexObj.hasValue && guard.isTrue
            if (isConcreteWrite && bindings.writeArrayIndex(ref.address, indexObj.value as Int, valueObj.value)) {
                return this
            }

            marshall.unmarshallArray(ref.address)
        }

        writeToBase(key, value, guard)

        return this
    }

    private fun unmarshallContentsCommon(
        address: UConcreteHeapAddress,
        descriptor: JcType,
        elements: Map<UExpr<USizeSort>, UExpr<Sort>>
    ) {
        baseRegion = baseRegion.initializeAllocatedArray(address, descriptor, sort, elements, ctx.trueExpr)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: Array<*>, desc: JcType) {
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, ctx.cp.objectType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: ByteArray) {
        val elemType = ctx.cp.byte
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: ShortArray) {
        val elemType = ctx.cp.short
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: CharArray) {
        val elemType = ctx.cp.char
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: IntArray) {
        val elemType = ctx.cp.int
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: LongArray) {
        val elemType = ctx.cp.long
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: FloatArray) {
        val elemType = ctx.cp.float
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: DoubleArray) {
        val elemType = ctx.cp.double
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: BooleanArray) {
        val elemType = ctx.cp.boolean
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteArrayRegion<Sort> {
        return JcConcreteArrayRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Array Length Region

private class JcConcreteArrayLengthRegion(
    private val regionId: UArrayLengthsRegionId<JcType, USizeSort>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: UArrayLengthsRegion<JcType, USizeSort>,
    private val marshall: Marshall
) : UArrayLengthsRegion<JcType, USizeSort>, JcConcreteRegion {

    private val lengthType by lazy { ctx.cp.int }

    override fun read(key: UArrayLengthLValue<JcType, USizeSort>): UExpr<USizeSort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val lengthObj = bindings.readArrayLength(ref.address)
            return marshall.objToExpr(lengthObj, lengthType)
        } else {
            return baseRegion.read(key)
        }
    }

    override fun write(
        key: UArrayLengthLValue<JcType, USizeSort>,
        value: UExpr<USizeSort>,
        guard: UBoolExpr
    ): UMemoryRegion<UArrayLengthLValue<JcType, USizeSort>, USizeSort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val lengthObj = marshall.tryExprToObj(value, lengthType)
            val isConcreteWrite = lengthObj.hasValue && guard.isTrue
            if (isConcreteWrite && bindings.writeArrayLength(address, lengthObj.value as Int))
                return this

            marshall.unmarshallArray(address)
        }

        baseRegion = baseRegion.write(key, value, guard) as UArrayLengthsRegion<JcType, USizeSort>

        return this
    }

    private fun unmarshallLengthCommon(ref: UConcreteHeapRef, size: Int) {
        val key = UArrayLengthLValue(ref, regionId.arrayType, regionId.sort)
        val length = marshall.objToExpr<USizeSort>(size, lengthType)
        baseRegion = baseRegion.write(key, length, ctx.trueExpr) as UArrayLengthsRegion<JcType, USizeSort>
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: Array<*>) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: ByteArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: ShortArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: CharArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: IntArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: LongArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: FloatArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: DoubleArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: BooleanArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteArrayLengthRegion {
        return JcConcreteArrayLengthRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Ref Map Region

private class JcConcreteRefMapRegion<ValueSort : USort>(
    private val regionId: URefMapRegionId<JcType, ValueSort>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: URefMapRegion<JcType, ValueSort>,
    private val marshall: Marshall
) : URefMapRegion<JcType, ValueSort>, JcConcreteRegion {

    private val mapType = regionId.mapType
    private val valueSort = regionId.sort

    private fun writeToBase(
        key: URefMapEntryLValue<JcType, ValueSort>,
        value: UExpr<ValueSort>,
        guard: UBoolExpr
    ) {
        baseRegion = baseRegion.write(key, value, guard) as URefMapRegion<JcType, ValueSort>
    }

    override fun merge(
        srcRef: UHeapRef,
        dstRef: UHeapRef,
        mapType: JcType,
        sort: ValueSort,
        keySet: URefSetRegion<JcType>,
        operationGuard: UBoolExpr
    ): URefMapRegion<JcType, ValueSort> {
        if (srcRef is UConcreteHeapRef &&
            bindings.contains(srcRef.address) &&
            dstRef is UConcreteHeapRef &&
            bindings.contains(dstRef.address)
        ) {
            val isConcreteCopy = operationGuard.isTrue
            if (isConcreteCopy && bindings.mapMerge(srcRef.address, dstRef.address)) {
                return this
            }
        }

        if (srcRef is UConcreteHeapRef)
            marshall.unmarshallMap(srcRef.address, mapType)

        if (dstRef is UConcreteHeapRef)
            marshall.unmarshallMap(dstRef.address, mapType)

        baseRegion = baseRegion.merge(srcRef, dstRef, mapType, sort, keySet, operationGuard)

        return this
    }

    override fun read(key: URefMapEntryLValue<JcType, ValueSort>): UExpr<ValueSort> {
        // TODO: use key.mapType ? #CM
        val ref = key.mapRef
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val objType = ctx.cp.objectType
            val keyObj = marshall.tryExprToObj(key.mapKey, objType)
            if (keyObj.hasValue) {
                val valueObj = bindings.readMapValue(address, keyObj.value)
                return marshall.objToExpr(valueObj, objType)
            }

            marshall.unmarshallMap(address, mapType)
        }

        return baseRegion.read(key)
    }

    override fun write(
        key: URefMapEntryLValue<JcType, ValueSort>,
        value: UExpr<ValueSort>,
        guard: UBoolExpr
    ): UMemoryRegion<URefMapEntryLValue<JcType, ValueSort>, ValueSort> {
        val ref = key.mapRef
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val valueObj = marshall.tryExprToObj(value, ctx.cp.objectType)
            val keyObj = marshall.tryExprToObj(key.mapKey, ctx.cp.objectType)
            val isConcreteWrite = valueObj.hasValue && keyObj.hasValue && guard.isTrue
            if (isConcreteWrite && bindings.writeMapValue(address, keyObj.value, valueObj.value)) {
                return this
            }

            marshall.unmarshallMap(address, key.mapType)
        }

        writeToBase(key, value, guard)

        return this
    }

    @Suppress("UNCHECKED_CAST")
    private fun unmarshallEntry(ref: UConcreteHeapRef, key: Any?, value: Any?) {
        // TODO: not efficient, implement via memset
        val keyType = if (key == null) ctx.cp.objectType else ctx.jcTypeOf(key)
        val valueType = if (value == null) ctx.cp.objectType else ctx.jcTypeOf(value)
        val keyExpr = marshall.objToExpr<USort>(key, keyType) as UHeapRef
        val lvalue = URefMapEntryLValue(valueSort, ref, keyExpr, mapType)
        val rvalue = marshall.objToExpr<USort>(value, valueType) as UExpr<ValueSort>
        writeToBase(lvalue, rvalue, ctx.trueExpr)
    }

    fun unmarshallContents(ref: UConcreteHeapRef, obj: Map<*, *>) {
        for ((key, value) in obj) {
            unmarshallEntry(ref, key, value)
        }
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteRefMapRegion<ValueSort> {
        return JcConcreteRefMapRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Map Length Region

private class JcConcreteMapLengthRegion(
    private val regionId: UMapLengthRegionId<JcType, USizeSort>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: UMapLengthRegion<JcType, USizeSort>,
    private val marshall: Marshall
) : UMapLengthRegion<JcType, USizeSort>, JcConcreteRegion {

    private val lengthType by lazy { ctx.cp.int }

    override fun read(key: UMapLengthLValue<JcType, USizeSort>): UExpr<USizeSort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val lengthObj = bindings.readMapLength(ref.address)
            return marshall.objToExpr(lengthObj, lengthType)
        } else {
            return baseRegion.read(key)
        }
    }

    override fun write(
        key: UMapLengthLValue<JcType, USizeSort>,
        value: UExpr<USizeSort>,
        guard: UBoolExpr
    ): UMemoryRegion<UMapLengthLValue<JcType, USizeSort>, USizeSort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val lengthObj = marshall.tryExprToObj(value, lengthType)
            val isConcreteWrite = lengthObj.hasValue && guard.isTrue
            if (isConcreteWrite && bindings.writeMapLength(address, lengthObj.value as Int))
                return this

            marshall.unmarshallMap(address, key.mapType)
        }

        baseRegion = baseRegion.write(key, value, guard) as UMapLengthRegion<JcType, USizeSort>

        return this
    }

    private fun unmarshallLength(ref: UConcreteHeapRef, size: Int) {
        val key = UMapLengthLValue(ref, regionId.mapType, regionId.sort)
        val length = marshall.objToExpr<USizeSort>(size, lengthType)
        baseRegion = baseRegion.write(key, length, ctx.trueExpr) as UMapLengthRegion<JcType, USizeSort>
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: Map<*, *>) {
        unmarshallLength(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: SymbolicMap<*, *>) {
        unmarshallLength(ref, obj.size())
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: SymbolicIdentityMap<*, *>) {
        unmarshallLength(ref, obj.size())
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteMapLengthRegion {
        return JcConcreteMapLengthRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Ref Set Region

@Suppress("UNUSED")
private class JcConcreteRefSetRegion(
    private val regionId: URefSetRegionId<JcType>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: URefSetRegion<JcType>,
    private val marshall: Marshall
) : URefSetRegion<JcType>, JcConcreteRegion {

    private val setType by lazy { regionId.setType }
    private val sort by lazy { regionId.sort }

    private fun writeToBase(key: URefSetEntryLValue<JcType>, value: UExpr<UBoolSort>, guard: UBoolExpr) {
        baseRegion = baseRegion.write(key, value, guard) as URefSetRegion<JcType>
    }

    override fun allocatedSetWithInputElements(setRef: UConcreteHeapAddress): UAllocatedRefSetWithInputElements<JcType> {
        // TODO: elems with input addresses (statics and symbolics)
        if (bindings.contains(setRef)) {
            marshall.unmarshallSet(setRef) // TODO: make efficient: create symbolic collection from set #CM
        }

        return baseRegion.allocatedSetWithInputElements(setRef)
    }

    override fun inputSetWithInputElements(): UInputRefSetWithInputElements<JcType> {
        return baseRegion.inputSetWithInputElements()
    }

    override fun union(srcRef: UHeapRef, dstRef: UHeapRef, operationGuard: UBoolExpr): URefSetRegion<JcType> {
        if (srcRef is UConcreteHeapRef &&
            bindings.contains(srcRef.address) &&
            dstRef is UConcreteHeapRef &&
            bindings.contains(dstRef.address)
        ) {
            val isConcreteCopy = operationGuard.isTrue
            if (isConcreteCopy && bindings.setUnion(srcRef.address, dstRef.address)) {
                return this
            }
        }

        if (srcRef is UConcreteHeapRef)
            marshall.unmarshallSet(srcRef.address)

        if (dstRef is UConcreteHeapRef)
            marshall.unmarshallSet(dstRef.address)

        baseRegion = baseRegion.union(srcRef, dstRef, operationGuard)

        return this
    }

    override fun setEntries(ref: UHeapRef): URefSetEntries<JcType> {
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            marshall.unmarshallSet(ref.address) // TODO: make efficient: create set of entries
            // TODO: set of pairs (allocatedRef, element)
        }

        return baseRegion.setEntries(ref)
    }

    override fun write(
        key: URefSetEntryLValue<JcType>,
        value: UExpr<UBoolSort>,
        guard: UBoolExpr
    ): UMemoryRegion<URefSetEntryLValue<JcType>, UBoolSort> {
        val ref = key.setRef
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val objType = ctx.cp.objectType
            val keyObj = marshall.tryExprToObj(key.setElement, objType)
            val valueObj = marshall.tryExprToObj(value, ctx.cp.boolean)
            val isConcreteWrite = valueObj.hasValue && keyObj.hasValue && guard.isTrue
            if (isConcreteWrite && bindings.changeSetContainsElement(address, keyObj.value, valueObj.value as Boolean)) {
                return this
            }

            marshall.unmarshallSet(address)
        }

        writeToBase(key, value, guard)

        return this
    }

    override fun read(key: URefSetEntryLValue<JcType>): UExpr<UBoolSort> {
        val ref = key.setRef
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val objType = ctx.cp.objectType
            val elem = marshall.tryExprToObj(key.setElement, objType)
            if (elem.hasValue) {
                val contains = bindings.checkSetContains(address, elem.value)
                return marshall.objToExpr(contains, ctx.cp.boolean)
            }
            marshall.unmarshallSet(address)
        }

        return baseRegion.read(key)
    }

    @Suppress("UNCHECKED_CAST")
    private fun unmarshallElement(ref: UConcreteHeapRef, element: Any?) {
        val elemType = if (element == null) ctx.cp.objectType else ctx.jcTypeOf(element)
        val elemExpr = marshall.objToExpr<USort>(element, elemType) as UHeapRef
        val lvalue = URefSetEntryLValue(ref, elemExpr, setType)
        writeToBase(lvalue, ctx.trueExpr, ctx.trueExpr)
    }

    fun unmarshallContents(ref: UConcreteHeapRef, obj: Set<*>) {
        for (elem in obj) {
            unmarshallElement(ref, elem)
        }
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteRefSetRegion {
        return JcConcreteRefSetRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Static Region

private class JcConcreteStaticFieldsRegion<Sort : USort>(
    private val regionId: JcStaticFieldRegionId<Sort>,
    private var baseRegion: JcStaticFieldsMemoryRegion<Sort>,
    private val marshall: Marshall,
    private val writtenFields: MutableSet<JcStaticFieldLValue<Sort>> = mutableSetOf()
) : JcStaticFieldsMemoryRegion<Sort>(regionId.sort), JcConcreteRegion {

    // TODO: redo #CM
    override fun read(key: JcStaticFieldLValue<Sort>): UExpr<Sort> {
        val field = key.field
        if (field is JcEnrichedVirtualField || field.name == staticFieldsInitializedFlagField.name)
            return baseRegion.read(key)

        check(JcConcreteMemoryClassLoader.isLoaded(field.enclosingClass))
        val fieldType = field.typedField.type
        return marshall.objToExpr(field.getFieldValue(JcConcreteMemoryClassLoader, null), fieldType)
    }

    override fun write(
        key: JcStaticFieldLValue<Sort>,
        value: UExpr<Sort>,
        guard: UBoolExpr
    ): JcConcreteStaticFieldsRegion<Sort> {
        writtenFields.add(key)
        baseRegion = baseRegion.write(key, value, guard)
        return this
    }

    override fun mutatePrimitiveStaticFieldValuesToSymbolic(enclosingClass: JcClassOrInterface) {
        // No symbolic statics
    }

    fun fieldsWithValues(): MutableMap<JcField, UExpr<Sort>> {
        val result: MutableMap<JcField, UExpr<Sort>> = mutableMapOf()
        for (key in writtenFields) {
            val value = baseRegion.read(key)
            result[key.field] = value
        }

        return result
    }

    fun copy(marshall: Marshall): JcConcreteStaticFieldsRegion<Sort> {
        return JcConcreteStaticFieldsRegion(
            regionId,
            baseRegion,
            marshall,
            writtenFields.toMutableSet()
        )
    }
}

//endregion

//region Concrete Lambda Region

private class JcConcreteCallSiteLambdaRegion(
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: JcLambdaCallSiteMemoryRegion,
    private val marshall: Marshall
) : JcLambdaCallSiteMemoryRegion(ctx), JcConcreteRegion {

    override fun writeCallSite(callSite: JcLambdaCallSite): JcConcreteCallSiteLambdaRegion {
        val address = callSite.ref.address
        val lambda = callSite.lambda
        val maybeArgs = marshall.tryExprListToFullyConcreteList(callSite.callSiteArgs, lambda.callSiteArgTypes)
        if (bindings.contains(address) && maybeArgs.hasValue) {
            val args = maybeArgs.value!!
            val invocationHandler = bindings.readInvocationHandler(address)
            val method = lambda.actualMethod.method.method
            val actualMethod =
                if (method is JcEnrichedVirtualMethod)
                    method.getMethod(ctx) ?: error("cannot find enriched method")
                else method
            invocationHandler.init(actualMethod, lambda.callSiteMethodName, args)
        }

        baseRegion = baseRegion.writeCallSite(callSite)

        return this
    }

    override fun findCallSite(ref: UConcreteHeapRef): JcLambdaCallSite? {
        return baseRegion.findCallSite(ref)
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteCallSiteLambdaRegion {
        return JcConcreteCallSiteLambdaRegion(
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//endregion

//region Marshall

private class Marshall(
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    var regionStorage: JcConcreteRegionStorage,
) {

    //region Helpers

    private val boolean by lazy { ctx.cp.boolean }
    private val byte by lazy { ctx.cp.byte }
    private val short by lazy { ctx.cp.short }
    private val char by lazy { ctx.cp.char }
    private val int by lazy { ctx.cp.int }
    private val long by lazy { ctx.cp.long }
    private val float by lazy { ctx.cp.float }
    private val double by lazy { ctx.cp.double }
    private val void by lazy { ctx.cp.void }
    private val trueExpr by lazy { ctx.trueExpr }
    private val falseExpr by lazy { ctx.falseExpr }

    private val Any?.maybe: Maybe<Any?>
        get() = Maybe(this)

    private val usvmApiSymbolicList by lazy { ctx.cp.findClassOrNull<SymbolicList<*>>()!!.toType() }
    private val javaList by lazy { ctx.cp.findClassOrNull<List<*>>()!!.toType() }
    private val usvmApiSymbolicMap by lazy { ctx.cp.findClassOrNull<SymbolicMap<*, *>>()!!.toType() }
    private val usvmApiSymbolicIdentityMap by lazy { ctx.cp.findClassOrNull<SymbolicIdentityMap<*, *>>()!!.toType() }
    private val javaMap by lazy { ctx.cp.findClassOrNull<Map<*, *>>()!!.toType() }

    //endregion

    //region Encoders

    private val encoders by lazy { loadEncoders() }

    private fun loadEncoders(): Map<JcClassOrInterface, Any> {
        val objectEncoderClass = ctx.cp.findClassOrNull(ObjectEncoder::class.java.name)!!
        return runBlocking {
            ctx.cp.hierarchyExt()
                .findSubClasses(objectEncoderClass, entireHierarchy = true, includeOwn = false)
                .mapNotNull { loadEncoder(it) }
                .toMap()
        }
    }

    private fun loadEncoder(encoder: JcClassOrInterface): Pair<JcClassOrInterface, Any>? {
        val target = encoder.annotation(EncoderFor::class.java.name)!!
        val targetCls = target.values["value"] ?: return null

        targetCls as JcClassOrInterface

        val encoderCls = JcConcreteMemoryClassLoader.loadClass(encoder)
        val encoderInstance = encoderCls.getConstructor().newInstance()

        return targetCls to encoderInstance
    }

    //endregion

    //region Expression To Object Conversion

    fun <Sort : USort> commonTryExprToObj(expr: UExpr<Sort>, type: JcType, fullyConcrete: Boolean): Maybe<Any?> {
        return when {
            expr is UNullRef -> Maybe(null)
            expr is UConcreteHeapRef && expr.address == NULL_ADDRESS -> Maybe(null)
            expr is UConcreteHeapRef && fullyConcrete ->
                bindings.tryFullyConcrete(expr.address)?.maybe ?: Maybe.empty()

            expr is UConcreteHeapRef ->
                bindings.tryVirtToPhys(expr.address)?.maybe ?: Maybe.empty()

            expr is USymbol -> Maybe.empty()
            type == boolean -> {
                when (expr) {
                    trueExpr -> true.maybe
                    falseExpr -> false.maybe
                    else -> Maybe.empty()
                }
            }

            type == byte -> {
                when (expr) {
                    is KBitVec8Value -> expr.byteValue.maybe
                    else -> Maybe.empty()
                }
            }

            type == short -> {
                when (expr) {
                    is KBitVec16Value -> expr.shortValue.maybe
                    else -> Maybe.empty()
                }
            }

            type == char -> {
                when (expr) {
                    is KBitVec16Value -> expr.shortValue.toInt().toChar().maybe
                    else -> Maybe.empty()
                }
            }

            type == int -> {
                when (expr) {
                    is KBitVec32Value -> expr.intValue.maybe
                    else -> Maybe.empty()
                }
            }

            type == long -> {
                when (expr) {
                    is KBitVec64Value -> expr.longValue.maybe
                    else -> Maybe.empty()
                }
            }

            type == float -> {
                when (expr) {
                    is KFp32Value -> expr.value.maybe
                    else -> Maybe.empty()
                }
            }

            type == double -> {
                when (expr) {
                    is KFp64Value -> expr.value.maybe
                    else -> Maybe.empty()
                }
            }

            expr is UIteExpr && expr.condition.isTrue -> commonTryExprToObj(expr.trueBranch, type, fullyConcrete)
            expr is UIteExpr && expr.condition.isFalse -> commonTryExprToObj(expr.falseBranch, type, fullyConcrete)
            expr is UIteExpr -> Maybe.empty()

            else -> error("Marshall.commonTryExprToObj: unexpected expression $expr")
        }
    }

    fun <Sort : USort> tryExprToObj(expr: UExpr<Sort>, type: JcType): Maybe<Any?> {
        return commonTryExprToObj(expr, type, false)
    }

    fun <Sort : USort> tryExprToFullyConcreteObj(expr: UExpr<Sort>, type: JcType): Maybe<Any?> {
        return commonTryExprToObj(expr, type, true)
    }

    fun tryExprListToFullyConcreteList(
        expressions: List<UExpr<*>>,
        types: List<JcType>
    ): Maybe<List<Any?>> {
        val result = mutableListOf<Any?>()
        for ((e, t) in expressions.zip(types)) {
            val obj = tryExprToFullyConcreteObj(e, t)
            if (!obj.hasValue)
                return Maybe.empty()
            result.add(obj)
        }

        return Maybe(result)
    }

    //endregion

    //region Object To Expression Conversion

    private fun typeOfObject(obj: Any): JcType? {
        try {
            val objJavaClass = obj.javaClass
            val typeName = objJavaClass.typeName

            if (objJavaClass.isProxy) {
                val interfaces = objJavaClass.interfaces
                if (interfaces.size == 1)
                    return ctx.cp.findType(interfaces[0].typeName)

                return null
            }

            if (objJavaClass.isLambda) {
                val db = ctx.cp.db
                val vfs = db.javaClass.allInstanceFields.find { it.name == "classesVfs" }!!.getFieldValue(db)!!
                val loc = ctx.cp.registeredLocations.find { it.jcLocation?.jarOrFolder?.absolutePath?.startsWith("/Users/michael/Documents/Work/spring-petclinic/build/libs/BOOT-INF/classes") == true }!!
                val addMethod = vfs.javaClass.methods.find { it.name == "addClass" }!!
                val source = LazyClassSourceImpl(loc, typeName)
                addMethod.invoke(vfs, source)

                val name = typeName.split('/')[0]
                return ctx.cp.findType(name)
            }

            return ctx.cp.findType(typeName)
        } catch (e : Throwable) {
            return null
        }
    }

    private fun referenceTypeToExpr(obj: Any?, type: JcRefType): UHeapRef {
        if (obj == null)
            return ctx.nullRef

        var address = bindings.tryPhysToVirt(obj)
        if (address == null) {
            val objType = typeOfObject(obj)
            val mostConcreteType = when {
                objType is JcUnknownType -> type
                objType != null && (objType.isAssignable(type) || type is JcTypeVariable) -> objType
                else -> type
            }
            address = bindings.allocate(obj, mostConcreteType)!!
            when {
                type.isAssignable(usvmApiSymbolicList) -> unmarshallSymbolicList(address, obj)
                type.isAssignable(usvmApiSymbolicMap) -> unmarshallSymbolicMap(address, obj)
                type.isAssignable(usvmApiSymbolicIdentityMap) -> unmarshallSymbolicIdentityMap(address, obj)
            }
        }

        return ctx.mkConcreteHeapRef(address)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <Sort : USort> primitiveTypeToExpr(obj: Any): UExpr<Sort> {
        with(ctx) {
            return when (obj) {
                is Boolean -> mkBool(obj)
                is Byte -> mkBv(obj)
                is Short -> mkBv(obj)
                is Char -> mkBv(obj.code, charSort)
                is Int -> mkBv(obj)
                is Long -> mkBv(obj)
                is Float -> mkFp(obj, floatSort)
                is Double -> mkFp(obj, doubleSort)
                else -> error("Marshall.primitiveTypeToExpr: unexpected object $obj")
            } as UExpr<Sort>
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <Sort : USort> objToExpr(obj: Any?, type: JcType): UExpr<Sort> {
        return when (type) {
            void -> ctx.voidValue
            is JcPrimitiveType -> primitiveTypeToExpr(obj!!)
            is JcArrayType, is JcClassType, is JcTypeVariable -> referenceTypeToExpr(obj, type as JcRefType)
            else -> error("Marshall.objToExpr: unexpected object $obj with type ${type.typeName}")
        } as UExpr<Sort>
    }

    //endregion

    //region Unmarshall

    private fun unmarshallFields(address: UConcreteHeapAddress, obj: Any, fields: List<JcTypedField>) {
        val ref = ctx.mkConcreteHeapRef(address)
        fields.forEach {
            try {
                regionStorage.getFieldRegion(it).unmarshallField(ref, obj)
            } catch (e: Exception) {
                error("unmarshallFields failed on field ${it.name}, exception: $e")
            }
        }
    }

    fun unmarshallClass(address: UConcreteHeapAddress) {
        val obj = bindings.tryVirtToPhys(address) ?: return
        val type = bindings.typeOf(address)
        type as JcClassType
        val allFields = type.allInstanceFields
        val containsApproximationFields = allFields.any { it.field.toJavaField == null }
        if (containsApproximationFields) {
            encode(address)
            return
        }

        unmarshallFields(address, obj, allFields)
        bindings.remove(address)
    }

    private fun unmarshallArray(
        address: UConcreteHeapAddress,
        obj: Any,
        descriptor: JcType,
        elemSort: USort,
    ) {
        val ref = ctx.mkConcreteHeapRef(address)
        val arrayRegion = regionStorage.getArrayRegion(descriptor, elemSort)
        val arrayLengthRegion = regionStorage.getArrayLengthRegion(descriptor)
        when (obj) {
            is IntArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is ByteArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is CharArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is LongArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is FloatArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is ShortArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is DoubleArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is BooleanArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is Array<*> -> {
                arrayRegion.unmarshallArray(address, obj, descriptor)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }
        }
        bindings.remove(address)
    }

    fun unmarshallArray(address: UConcreteHeapAddress) {
        val obj = bindings.tryVirtToPhys(address) ?: return
        val type = bindings.typeOf(address)
        type as JcArrayType
        val desc = ctx.arrayDescriptorOf(type)
        val elemSort = ctx.typeToSort(type.elementType)
        unmarshallArray(address, obj, desc, elemSort)
    }

    private fun unmarshallMap(address: UConcreteHeapAddress, obj: Map<*, *>, mapType: JcType) {
        val ref = ctx.mkConcreteHeapRef(address)
        val valueSort = ctx.addressSort
        val mapRegion = regionStorage.getMapRegion(mapType, valueSort)
        val mapLengthRegion = regionStorage.getMapLengthRegion(mapType)
        val keySetRegion = regionStorage.getSetRegion(mapType)
        mapRegion.unmarshallContents(ref, obj)
        mapLengthRegion.unmarshallLength(ref, obj)
        keySetRegion.unmarshallContents(ref, obj.keys)
        bindings.remove(address)
    }

    fun unmarshallMap(address: UConcreteHeapAddress, mapType: JcType) {
        val obj = bindings.tryVirtToPhys(address) ?: return
        obj as Map<*, *>
        unmarshallMap(address, obj, mapType)
    }

    @Suppress("UNUSED_VARIABLE")
    fun unmarshallSet(address: UConcreteHeapAddress) {
        val obj = bindings.tryVirtToPhys(address) ?: return
        TODO("unmarshall set")
//        obj as Set<*, *>
//        unmarshallMap(address, obj, mapType)
    }

    fun unmarshallSymbolicList(address: UConcreteHeapAddress, obj: Any) {
        val methods = obj.javaClass.declaredMethods
        val getMethod = methods.find { it.name == "get" }!!
        val size = methods.find { it.name == "size" }!!.invoke(obj) as Int
        val array = Array<Any?>(size) { getMethod.invoke(obj, it) }
        unmarshallArray(address, array, usvmApiSymbolicList, ctx.addressSort)
    }

    private fun createMapFromSymbolicMap(obj: Any): Map<*, *> {
        val mapMethods = obj.javaClass.declaredMethods
        val entriesMethod = mapMethods.find { it.name == "entries" }!!
        val entries = entriesMethod.invoke(obj) as Array<*>
        val map = HashMap<Any?, Any?>()
        entries.forEach { entry ->
            val entryMethods = entry!!.javaClass.declaredMethods
            val getKeyMethod = entryMethods.find { it.name == "getKey" }!!
            val getValueMethod = entryMethods.find { it.name == "getValue" }!!
            getKeyMethod.isAccessible = true
            val key = getKeyMethod.invoke(entry)
            getValueMethod.isAccessible = true
            val value = getValueMethod.invoke(entry)
            map[key] = value
        }
        return map
    }

    fun unmarshallSymbolicMap(address: UConcreteHeapAddress, obj: Any) {
        val map = createMapFromSymbolicMap(obj)
        unmarshallMap(address, map, usvmApiSymbolicMap)
    }

    fun unmarshallSymbolicIdentityMap(address: UConcreteHeapAddress, obj: Any) {
        val map = createMapFromSymbolicMap(obj)
        unmarshallMap(address, map, usvmApiSymbolicIdentityMap)
    }

    //endregion

    //region Encoding

    fun encode(address: UConcreteHeapAddress) {
        val obj = bindings.virtToPhys(address)
        val type = bindings.typeOf(address) as JcClassType
        println("encoding for $address of ${type.name}")
        var encoder: Any? = null
        var jcClass: JcClassOrInterface? = type.jcClass
        var searchingEncoder = true
        while (jcClass != null && searchingEncoder) {
            encoder = encoders[jcClass]
            searchingEncoder = encoder == null
            jcClass = if (searchingEncoder) jcClass.superClass else jcClass
        }
        encoder ?: error("Failed to find encoder for type ${type.name}")
        val encodeMethod = encoder.javaClass.declaredMethods.find { it.name == "encode" }!!
        val approximatedObj = try {
            encodeMethod.invoke(encoder, obj)
        } catch (e: Throwable) {
            var exception = e
            if (e is InvocationTargetException)
                exception = e.targetException
            println("Encoder $encodeMethod threw exception $exception")
            throw exception
        }
        val allFields = type.allInstanceFields
        val approximationFields = allFields.filter { it.field is JcEnrichedVirtualField }
        unmarshallFields(address, approximatedObj, approximationFields)

        val otherFields = ArrayList<JcTypedField>()
        var currentClass = type
        while (currentClass.jcClass != jcClass) {
            val fields = currentClass.declaredInstanceFields
            if (fields.any { it.field is JcEnrichedVirtualField })
                error("Encoding: failed to find encoder for ${currentClass.name}")
            otherFields.addAll(fields)
            currentClass = currentClass.superType ?: error("Encoding: supertype is null")
        }
        if (otherFields.isNotEmpty())
            unmarshallFields(address, obj, otherFields)

        bindings.remove(address)
    }

    //endregion

    fun copy(bindings: JcConcreteMemoryBindings, regionStorage: JcConcreteRegionStorage): Marshall {
        return Marshall(ctx, bindings, regionStorage)
    }
}

//endregion

//region Region Storage

private interface JcConcreteRegionGetter {
    fun <Key, Sort : USort> getConcreteRegion(regionId: UMemoryRegionId<Key, Sort>): JcConcreteRegion
}

private class JcConcreteRegionStorage(
    private val ctx: JcContext,
    private val regionGetter: JcConcreteRegionGetter,
    private val fieldRegions: MutableMap<JcField, JcConcreteFieldRegion<*>> = mutableMapOf(),
    private val arrayRegions: MutableMap<JcType, JcConcreteArrayRegion<*>> = mutableMapOf(),
    private val arrayLengthRegions: MutableMap<JcType, JcConcreteArrayLengthRegion> = mutableMapOf(),
    private val mapRegions: MutableMap<JcType, JcConcreteRefMapRegion<*>> = mutableMapOf(),
    private val mapLengthRegions: MutableMap<JcType, JcConcreteMapLengthRegion> = mutableMapOf(),
    private val setRegions: MutableMap<JcType, JcConcreteRefSetRegion> = mutableMapOf(),
    private val staticFieldsRegions: MutableMap<USort, JcConcreteStaticFieldsRegion<*>> = mutableMapOf(),
    private var lambdaCallSiteRegion: JcConcreteCallSiteLambdaRegion? = null
) {

    fun getFieldRegion(typedField: JcTypedField): JcConcreteFieldRegion<*> {
        val field = typedField.field
        return fieldRegions.getOrPut(field) {
            val sort = ctx.typeToSort(typedField.type)
            val regionId = UFieldsRegionId(field, sort)
            regionGetter.getConcreteRegion(regionId) as JcConcreteFieldRegion<*>
        }
    }

    fun getFieldRegion(regionId: UFieldsRegionId<*, *>): JcConcreteFieldRegion<*> {
        return fieldRegions.getOrPut(regionId.field as JcField) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteFieldRegion<*>
        }
    }

    fun getArrayRegion(desc: JcType, sort: USort): JcConcreteArrayRegion<*> {
        return arrayRegions.getOrPut(desc) {
            val regionId = UArrayRegionId<JcType, USort, USizeSort>(desc, sort)
            regionGetter.getConcreteRegion(regionId) as JcConcreteArrayRegion<*>
        }
    }

    fun getArrayRegion(regionId: UArrayRegionId<*, *, *>): JcConcreteArrayRegion<*> {
        return arrayRegions.getOrPut(regionId.arrayType as JcType) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteArrayRegion<*>
        }
    }

    fun getArrayLengthRegion(desc: JcType): JcConcreteArrayLengthRegion {
        return arrayLengthRegions.getOrPut(desc) {
            val regionId = UArrayLengthsRegionId(ctx.sizeSort, desc)
            regionGetter.getConcreteRegion(regionId) as JcConcreteArrayLengthRegion
        }
    }

    fun getArrayLengthRegion(regionId: UArrayLengthsRegionId<*, *>): JcConcreteArrayLengthRegion {
        return arrayLengthRegions.getOrPut(regionId.arrayType as JcType) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteArrayLengthRegion
        }
    }

    fun getMapRegion(mapType: JcType, valueSort: USort): JcConcreteRefMapRegion<*> {
        return mapRegions.getOrPut(mapType) {
            val regionId = URefMapRegionId(valueSort, mapType)
            regionGetter.getConcreteRegion(regionId) as JcConcreteRefMapRegion<*>
        }
    }

    fun getMapRegion(regionId: URefMapRegionId<*, *>): JcConcreteRefMapRegion<*> {
        return mapRegions.getOrPut(regionId.mapType as JcType) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteRefMapRegion<*>
        }
    }

    fun getMapLengthRegion(mapType: JcType): JcConcreteMapLengthRegion {
        return mapLengthRegions.getOrPut(mapType) {
            val regionId = UMapLengthRegionId(ctx.sizeSort, mapType)
            regionGetter.getConcreteRegion(regionId) as JcConcreteMapLengthRegion
        }
    }

    fun getMapLengthRegion(regionId: UMapLengthRegionId<*, *>): JcConcreteMapLengthRegion {
        return mapLengthRegions.getOrPut(regionId.mapType as JcType) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteMapLengthRegion
        }
    }

    fun getSetRegion(setType: JcType): JcConcreteRefSetRegion {
        return setRegions.getOrPut(setType) {
            val regionId = URefSetRegionId(setType, ctx.boolSort)
            regionGetter.getConcreteRegion(regionId) as JcConcreteRefSetRegion
        }
    }

    fun getSetRegion(regionId: URefSetRegionId<*>): JcConcreteRefSetRegion {
        return setRegions.getOrPut(regionId.setType as JcType) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteRefSetRegion
        }
    }

    fun getStaticFieldsRegion(regionId: JcStaticFieldRegionId<*>): JcConcreteStaticFieldsRegion<*> {
        return staticFieldsRegions.getOrPut(regionId.sort) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteStaticFieldsRegion<*>
        }
    }

    fun getLambdaCallSiteRegion(regionId: JcLambdaCallSiteRegionId): JcConcreteCallSiteLambdaRegion {
        if (lambdaCallSiteRegion != null)
            return lambdaCallSiteRegion!!
        lambdaCallSiteRegion = regionGetter.getConcreteRegion(regionId) as JcConcreteCallSiteLambdaRegion
        return lambdaCallSiteRegion!!
    }

    fun allMutatedStaticFields(): Map<JcField, UExpr<out USort>> {
        val result: MutableMap<JcField, UExpr<*>> = mutableMapOf()
        staticFieldsRegions.forEach { (_, staticRegion) ->
            result.putAll(staticRegion.fieldsWithValues())
        }

        return result
    }

    fun copy(
        bindings: JcConcreteMemoryBindings,
        regionGetter: JcConcreteRegionGetter,
        marshall: Marshall
    ): JcConcreteRegionStorage {
        val newFieldRegions: MutableMap<JcField, JcConcreteFieldRegion<*>> = mutableMapOf()
        for ((k, v) in fieldRegions)
            newFieldRegions[k] = v.copy(bindings, marshall)
        val newArrayRegions: MutableMap<JcType, JcConcreteArrayRegion<*>> = mutableMapOf()
        for ((k, v) in arrayRegions)
            newArrayRegions[k] = v.copy(bindings, marshall)
        val newArrayLengthRegions: MutableMap<JcType, JcConcreteArrayLengthRegion> = mutableMapOf()
        for ((k, v) in arrayLengthRegions)
            newArrayLengthRegions[k] = v.copy(bindings, marshall)
        val newMapRegions: MutableMap<JcType, JcConcreteRefMapRegion<*>> = mutableMapOf()
        for ((k, v) in mapRegions)
            newMapRegions[k] = v.copy(bindings, marshall)
        val newMapLengthRegions: MutableMap<JcType, JcConcreteMapLengthRegion> = mutableMapOf()
        for ((k, v) in mapLengthRegions)
            newMapLengthRegions[k] = v.copy(bindings, marshall)
        val newSetRegions: MutableMap<JcType, JcConcreteRefSetRegion> = mutableMapOf()
        for ((k, v) in setRegions)
            newSetRegions[k] = v.copy(bindings, marshall)
        val newStaticFieldsRegions: MutableMap<USort, JcConcreteStaticFieldsRegion<*>> = mutableMapOf()
        for ((k, v) in staticFieldsRegions)
            newStaticFieldsRegions[k] = v.copy(marshall)
        val newLambdaCallSiteRegion = lambdaCallSiteRegion?.copy(bindings, marshall)
        return JcConcreteRegionStorage(
            ctx,
            regionGetter,
            newFieldRegions,
            newArrayRegions,
            newArrayLengthRegions,
            newMapRegions,
            newMapLengthRegions,
            newSetRegions,
            newStaticFieldsRegions,
            newLambdaCallSiteRegion
        )
    }
}

//endregion

//region Concrete Memory

class JcThreadFactory : ThreadFactory {

    private var count = 0

    override fun newThread(runnable: Runnable): Thread {
        check(count == 0)
        val thread = Thread(runnable)
        count++
        thread.contextClassLoader = JcConcreteMemoryClassLoader
        thread.isDaemon = true
        return thread
    }
}

class JcConcreteMemory private constructor(
    private val ctx: JcContext,
    typeConstraints: UTypeConstraints<JcType>,
    stack: URegistersStack,
    mocks: UIndexedMocker<JcMethod>,
    regions: PersistentMap<UMemoryRegionId<*, *>, UMemoryRegion<*, *>>,
    private val executor: ExecutorService,
    private val bindings: JcConcreteMemoryBindings,
    private var regionStorageVar: JcConcreteRegionStorage? = null,
    private var marshallVar: Marshall? = null
) : UMemory<JcType, JcMethod>(ctx, typeConstraints, stack, mocks, regions), JcConcreteRegionGetter {

    private val ansiReset: String = "\u001B[0m"
    private val ansiBlack: String = "\u001B[30m"
    private val ansiRed: String = "\u001B[31m"
    private val ansiGreen: String = "\u001B[32m"
    private val ansiYellow: String = "\u001B[33m"
    private val ansiBlue: String = "\u001B[34m"
    private val ansiWhite: String = "\u001B[37m"
    private val ansiPurple: String = "\u001B[35m"
    private val ansiCyan: String = "\u001B[36m"

    private val throwableType = ctx.cp.findClass("java.lang.Throwable").toType()

    private val regionStorage: JcConcreteRegionStorage by lazy { regionStorageVar!! }

    private val marshall: Marshall by lazy { marshallVar!! }

    private var concretization = false

    //region 'JcConcreteRegionGetter' implementation

    @Suppress("UNCHECKED_CAST")
    override fun <Key, Sort : USort> getConcreteRegion(regionId: UMemoryRegionId<Key, Sort>): JcConcreteRegion {
        val baseRegion = super.getRegion(regionId)
        return when (regionId) {
            is UFieldsRegionId<*, *> -> {
                baseRegion as UFieldsRegion<JcField, Sort>
                regionId as UFieldsRegionId<JcField, Sort>
                JcConcreteFieldRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is UArrayRegionId<*, *, *> -> {
                baseRegion as UArrayRegion<JcType, Sort, USizeSort>
                regionId as UArrayRegionId<JcType, Sort, USizeSort>
                JcConcreteArrayRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is UArrayLengthsRegionId<*, *> -> {
                baseRegion as UArrayLengthsRegion<JcType, USizeSort>
                regionId as UArrayLengthsRegionId<JcType, USizeSort>
                JcConcreteArrayLengthRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is URefMapRegionId<*, *> -> {
                baseRegion as URefMapRegion<JcType, Sort>
                regionId as URefMapRegionId<JcType, Sort>
                JcConcreteRefMapRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is UMapLengthRegionId<*, *> -> {
                baseRegion as UMapLengthRegion<JcType, USizeSort>
                regionId as UMapLengthRegionId<JcType, USizeSort>
                JcConcreteMapLengthRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is URefSetRegionId<*> -> {
                baseRegion as URefSetRegion<JcType>
                regionId as URefSetRegionId<JcType>
                JcConcreteRefSetRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is JcStaticFieldRegionId<*> -> {
                baseRegion as JcStaticFieldsMemoryRegion<Sort>
                val id = regionId as JcStaticFieldRegionId<Sort>
                JcConcreteStaticFieldsRegion(id, baseRegion, marshall)
            }

            is JcLambdaCallSiteRegionId -> {
                baseRegion as JcLambdaCallSiteMemoryRegion
                JcConcreteCallSiteLambdaRegion(ctx, bindings, baseRegion, marshall)
            }

            else -> baseRegion
        } as JcConcreteRegion
    }

    //endregion

    //region 'UMemory' implementation

    @Suppress("UNCHECKED_CAST")
    override fun <Key, Sort : USort> getRegion(regionId: UMemoryRegionId<Key, Sort>): UMemoryRegion<Key, Sort> {
        return when (regionId) {
            is UFieldsRegionId<*, *> -> regionStorage.getFieldRegion(regionId)
            is UArrayRegionId<*, *, *> -> regionStorage.getArrayRegion(regionId)
            is UArrayLengthsRegionId<*, *> -> regionStorage.getArrayLengthRegion(regionId)
            is UMapRegionId<*, *, *, *> -> error("ConcreteMemory.getRegion: unexpected 'UMapRegionId'")
            is URefMapRegionId<*, *> -> regionStorage.getMapRegion(regionId)
            is UMapLengthRegionId<*, *> -> regionStorage.getMapLengthRegion(regionId)
            is USetRegionId<*, *, *> -> error("ConcreteMemory.getRegion: unexpected 'USetRegionId'")
            is URefSetRegionId<*> -> regionStorage.getSetRegion(regionId)
            is JcStaticFieldRegionId<*> -> regionStorage.getStaticFieldsRegion(regionId)
            is JcLambdaCallSiteRegionId -> regionStorage.getLambdaCallSiteRegion(regionId)
            else -> super.getRegion(regionId)
        } as UMemoryRegion<Key, Sort>
    }

    override fun <Key, Sort : USort> setRegion(
        regionId: UMemoryRegionId<Key, Sort>,
        newRegion: UMemoryRegion<Key, Sort>
    ) {
        when (regionId) {
            is UFieldsRegionId<*, *> -> check(newRegion is JcConcreteFieldRegion)
            is UArrayRegionId<*, *, *> -> check(newRegion is JcConcreteArrayRegion)
            is UArrayLengthsRegionId<*, *> -> check(newRegion is JcConcreteArrayLengthRegion)
            is UMapRegionId<*, *, *, *> -> error("ConcreteMemory.setRegion: unexpected 'UMapRegionId'")
            is URefMapRegionId<*, *> -> check(newRegion is JcConcreteRefMapRegion)
            is UMapLengthRegionId<*, *> -> check(newRegion is JcConcreteMapLengthRegion)
            is USetRegionId<*, *, *> -> error("ConcreteMemory.setRegion: unexpected 'USetRegionId'")
            is URefSetRegionId<*> -> check(newRegion is JcConcreteRefSetRegion)
            is JcStaticFieldRegionId<*> -> check(newRegion is JcConcreteStaticFieldsRegion)
            is JcLambdaCallSiteRegionId -> check(newRegion is JcConcreteCallSiteLambdaRegion)
            else -> {
                super.setRegion(regionId, newRegion)
            }
        }
    }

    //endregion

    private fun allocateObject(obj: Any, type: JcType): UConcreteHeapRef {
        val address = bindings.allocate(obj, type)!!
        return ctx.mkConcreteHeapRef(address)
    }

    private val forcedAllocationTypes = setOf(
        "org.springframework.web.bind.ServletRequestParameterPropertyValues",
        "org.springframework.web.context.support.ServletRequestHandledEvent",
        "java.lang.Class",
        "org.springframework.core.annotation.AnnotatedMethod",
        "org.springframework.core.annotation.SynthesizingMethodParameter",
        "org.thymeleaf.context.WebExpressionContext",
        "org.thymeleaf.EngineConfiguration",
        "org.thymeleaf.spring6.view.ThymeleafViewResolver",
        "org.springframework.web.servlet.view.AbstractCachingViewResolver",
        "java.util.Locale",
    )

    private fun shouldForceAllocation(type: JcType): Boolean {
//        return type.typeName.startsWith("org.thymeleaf") || type.typeName.startsWith("org.springframework.web.servlet.view") || // TODO: delete
//                forcedAllocationTypes.contains(type.typeName) ||
//                type.isAssignable(throwableType)
                // TODO: optimize forcedAllocationTypes via allowing all
            return !type.typeName.startsWith("org.usvm.api.") &&
                    !type.typeName.startsWith("generated.") &&
                    !type.typeName.startsWith("stub.") &&
                    !type.typeName.startsWith("runtime.")
    }

    override fun allocConcrete(type: JcType): UConcreteHeapRef {
        val address =
            if (bindings.isWritable || shouldForceAllocation(type))
                bindings.allocateDefaultConcrete(type)
            else null
        if (!bindings.isWritable && address != null)
            println(ansiCyan + "[Alloc] Can be added to forcedAllocationTypes: ${type.typeName}" + ansiReset)
        if (address != null)
            return ctx.mkConcreteHeapRef(address)
        return super.allocConcrete(type)
    }

    override fun allocStatic(type: JcType): UConcreteHeapRef {
        val address =
            if (bindings.isWritable || shouldForceAllocation(type))
                bindings.allocateDefaultStatic(type)
            else null
        if (address != null)
            return ctx.mkConcreteHeapRef(address)
        return super.allocStatic(type)
    }

    override fun tryAllocateConcrete(obj: Any, type: JcType): UConcreteHeapRef? {
        val address = bindings.allocate(obj, type)
        if (address != null)
            return ctx.mkConcreteHeapRef(address)
        return null
    }

    override fun forceAllocConcrete(type: JcType): UConcreteHeapRef {
        val address = bindings.allocateDefaultConcrete(type)
        if (address != null)
            return ctx.mkConcreteHeapRef(address)
        return super.allocConcrete(type)
    }

    override fun tryHeapRefToObject(heapRef: UConcreteHeapRef): Any? {
        val maybe = marshall.tryExprToFullyConcreteObj(heapRef, ctx.cp.objectType)
        check(!(maybe.hasValue && maybe.value == null))
        if (maybe.hasValue)
            return maybe.value!!

        return null
    }

    override fun <Sort: USort> tryExprToInt(expr: UExpr<Sort>): Int? {
        val maybe = marshall.tryExprToFullyConcreteObj(expr, ctx.cp.int)
        check(!(maybe.hasValue && maybe.value == null))
        if (maybe.hasValue)
            return maybe.value!! as Int

        return null
    }

    override fun tryObjectToExpr(obj: Any?, type: JcType): UExpr<USort> {
        return marshall.objToExpr(obj, type)
    }

    override fun clone(typeConstraints: UTypeConstraints<JcType>): UMemory<JcType, JcMethod> {
        check(!concretization)

        bindings.makeNonWritable()
        println(ansiBlue + "Concrete memory is non writable!" + ansiReset)
        val stack = stack.clone()
        val mocks = mocks.clone()
        val regions = regions.build()
        val bindings = bindings.copy(typeConstraints)
        val memory = JcConcreteMemory(
            ctx,
            typeConstraints,
            stack,
            mocks,
            regions,
            executor,
            bindings,
            regionStorage,
            marshall
        )
        val marshall = marshall.copy(bindings, regionStorage)
        val regionStorage = regionStorage.copy(bindings, memory, marshall)
        marshall.regionStorage = regionStorage
        memory.regionStorageVar = regionStorage
        memory.marshallVar = marshall

        return memory
    }

    //region Concrete Invoke

    private fun methodIsInvokable(method: JcMethod): Boolean {
        return !(
                    method.isConstructor && method.enclosingClass.isAbstract ||
                    method.enclosingClass.isEnum && method.isConstructor ||
                    method.humanReadableSignature.let {
                        it.startsWith("org.usvm") ||
                        it.startsWith("runtime.LibSLRuntime") ||
                        it.startsWith("generated.") ||
                        it.startsWith("stub.")
                    }
                )
    }

    private fun shouldInvoke(method: JcMethod): Boolean {
        val signature = method.humanReadableSignature
        return !forbiddenInvocations.contains(signature) &&
                (
                        concreteNonMutatingInvocations.contains(signature) ||
                        bindings.isWritable && concreteMutatingInvocations.contains(signature) ||
                        method.isConstructor && method.enclosingClass.toType().isAssignable(throwableType)
                )
    }

    private inner class JcConcretizer(
        state: JcState
    ) : JcTestStateResolver<Any?>(state.ctx, state.models.first(), state.memory, state.callStack.lastMethod().enclosingClass.toType().declaredMethods.first()) {
        override val decoderApi: JcTestInterpreterDecoderApi = JcTestInterpreterDecoderApi(ctx, JcConcreteMemoryClassLoader)

        override fun tryCreateObjectInstance(ref: UConcreteHeapRef, heapRef: UHeapRef): Any? {
            val addressInModel = ref.address

            if (bindings.contains(addressInModel)) {
                val obj = bindings.tryFullyConcrete(addressInModel)
                check(obj != null)
                return obj
            }

            if (heapRef !is UConcreteHeapRef)
                return null

            val address = heapRef.address
            val obj = bindings.tryFullyConcrete(address)
            if (obj != null)
                bindings.addObjectToBacktrackRec(obj)

            return obj
        }

        private fun resolveConcreteArray(ref: UConcreteHeapRef, type: JcArrayType): Any {
            val address = ref.address
            val obj = bindings.virtToPhys(address)
            // TODO: optimize #CM
            bindings.addObjectToBacktrackRec(obj)
            val elementType = type.elementType
            val elementSort = ctx.typeToSort(type.elementType)
            val arrayDescriptor = ctx.arrayDescriptorOf(type)
            for (kind in bindings.symbolicMembers(address)) {
                check(kind is ArrayIndexChildKind)
                val index = kind.index
                val value = readArrayIndex(ref, ctx.mkSizeExpr(index), arrayDescriptor, elementSort)
                val resolved = resolveExpr(value, elementType)
                obj.setArrayValue(index, resolved)
            }

            return obj
        }

        override fun resolveArray(ref: UConcreteHeapRef, heapRef: UHeapRef, type: JcArrayType): Any? {
            val addressInModel = ref.address
            if (heapRef is UConcreteHeapRef && bindings.contains(heapRef.address)) {
                val array = resolveConcreteArray(heapRef, type)
                saveResolvedRef(addressInModel, array)
                return array
            }

            val array = super.resolveArray(ref, heapRef, type)
            if (array != null && !bindings.contains(addressInModel)) {
                types.remove(addressInModel)
                bindings.allocate(addressInModel, array, type)
            }

            return array
        }

        private fun resolveConcreteObject(ref: UConcreteHeapRef, type: JcClassType): Any {
            val address = ref.address
            val obj = bindings.virtToPhys(address)
            // TODO: optimize #CM
            bindings.addObjectToBacktrackRec(obj)
            for (kind in bindings.symbolicMembers(address)) {
                check(kind is FieldChildKind)
                val field = kind.field
                val jcField = type.findFieldOrNull(field.name)
                    ?: error("resolveConcreteObject: can not find field $field")
                val fieldType = jcField.type
                val fieldSort = ctx.typeToSort(fieldType)
                val value = readField(ref, jcField, fieldSort)
                val resolved = resolveExpr(value, fieldType)
                field.setFieldValue(obj, resolved)
            }

            return obj
        }

        override fun resolveObject(ref: UConcreteHeapRef, heapRef: UHeapRef, type: JcClassType): Any? {
            val addressInModel = ref.address
            if (heapRef is UConcreteHeapRef && bindings.contains(heapRef.address)) {
                val obj = resolveConcreteObject(heapRef, type)
                saveResolvedRef(addressInModel, obj)
                return obj
            }

            val obj = super.resolveObject(ref, heapRef, type)
            if (obj != null && !bindings.contains(addressInModel)) {
                types.remove(addressInModel)
                bindings.allocate(addressInModel, obj, type)
            }

            return obj
        }

        override fun allocateClassInstance(type: JcClassType): Any =
            type.allocateInstance(JcConcreteMemoryClassLoader)

        override fun allocateString(value: Any?): Any = when (value) {
            is CharArray -> String(value)
            is ByteArray -> String(value)
            else -> String()
        }
    }

    // TODO: no mutable statics! Delete! #CM
    // TODO: collect classes, where where writes and initialize via JcConcreteMemoryClassLoader.loadClass(field.enclosingClass)
    // TODO: maybe in executor #CM (make function 'callStaticInit')
    private fun concretizeStatics(jcConcretizer: JcConcretizer) {
        println(ansiGreen + "Concretizing statics" + ansiReset)
        val statics = regionStorage.allMutatedStaticFields()
        // TODO: optimize
        statics.forEach { (field, value) ->
            val javaField = field.toJavaField
            if (javaField != null && !field.isFinal) {
                val typedField = field.typedField
                val concretizedValue = jcConcretizer.resolveExpr(value, typedField.type)
                JcConcreteMemoryClassLoader.loadClass(field.enclosingClass)
                val currentValue = field.getFieldValue(JcConcreteMemoryClassLoader, null)
                if (concretizedValue != currentValue) {
                    // TODO: remember field and current value #CM
                    field.setFieldValue(JcConcreteMemoryClassLoader, null, concretizedValue)
                }
            }
        }
    }

    private fun concretize(
        state: JcState,
        exprResolver: JcExprResolver,
        stmt: JcMethodCall,
        method: JcMethod,
    ) {
        // TODO: (1) clone recursively each object and remember it
        // TODO: (2) mutate and concretize old object
        // TODO: (3) finish current state: add new path selector (after concretization it will finish current path)
        // TODO: (4) roll back changes after path finish via remembered objects
        val concretizer = JcConcretizer(state)
        if (!concretization)
            concretizeStatics(concretizer)

        concretization = true
        val parameterInfos = method.parameters
        var parameters = stmt.arguments
        val isStatic = method.isStatic
        var thisObj: Any? = null
        val objParameters = mutableListOf<Any?>()

        if (!isStatic) {
            val thisType = method.enclosingClass.toType()
            thisObj = concretizer.withMode(ResolveMode.CURRENT) {
                resolveReference(parameters[0].asExpr(ctx.addressSort), thisType)
            }
            parameters = parameters.drop(1)
        }

        check(parameterInfos.size == parameters.size)
        for (i in parameterInfos.indices) {
            val info = parameterInfos[i]
            val value = parameters[i]
            val type = ctx.cp.findTypeOrNull(info.type)!!
            val elem = concretizer.withMode(ResolveMode.CURRENT) {
                resolveExpr(value, type)
            }
            objParameters.add(elem)
        }

        check(objParameters.size == parameters.size)
        println(ansiGreen + "Concretizing ${method.humanReadableSignature}" + ansiReset)
        invoke(state, exprResolver, stmt, method, thisObj, objParameters)
    }

    private fun ensureClinit(type: JcClassOrInterface) {
        executor.submit {
            try {
                // Loading type and executing its class initializer
                JcConcreteMemoryClassLoader.loadClass(type)
            } catch (e: Throwable) {
                error("clinit should not throw exceptions")
            }
        }.get()
    }

    private fun unfoldException(e: Throwable): Throwable {
        return when {
            e is ExecutionException && e.cause != null -> unfoldException(e.cause!!)
            e is InvocationTargetException -> e.targetException
            else -> e
        }
    }

    private fun invoke(
        state: JcState,
        exprResolver: JcExprResolver,
        stmt: JcMethodCall,
        method: JcMethod,
        thisObj: Any?,
        objParameters: List<Any?>
    ) {
        var thisArg = thisObj
        try {
            var resultObj: Any? = null
            var exception: Throwable? = null
            executor.submit {
                try {
                    resultObj = method.invoke(JcConcreteMemoryClassLoader, thisObj, objParameters)
                } catch (e: Throwable) {
                    exception = unfoldException(e)
                }
            }.get()

            if (exception == null) {
                // No exception
                println("Result $resultObj")
                if (method.isConstructor) {
                    val thisAddress = bindings.tryPhysToVirt(thisObj!!)
                    check(thisAddress != null)
                    thisArg = resultObj
                    val type = bindings.typeOf(thisAddress)
                    bindings.remove(thisAddress)
                    types.remove(thisAddress)
                    bindings.allocate(thisAddress, resultObj!!, type)
                }

                val returnType = ctx.cp.findTypeOrNull(method.returnType)!!
                val result: UExpr<USort> = marshall.objToExpr(resultObj, returnType)
                exprResolver.ensureExprCorrectness(result, returnType)
                state.newStmt(JcConcreteInvocationResult(result, stmt))
            } else {
                // Exception thrown
                val e = exception!!
                val jcType = ctx.jcTypeOf(e)
                println("Exception ${e.javaClass} with message ${e.message}")
                val exceptionObj = allocateObject(e, jcType)
                state.throwExceptionWithoutStackFrameDrop(exceptionObj, jcType)
            }
        } finally {
            // TODO: if method is not mutating, do not reTrack #CM
            objParameters.forEach {
                bindings.reTrackObject(it)
            }
            if (thisObj != null)
                bindings.reTrackObject(thisArg)
        }
    }

    private fun shouldNotInvokeClinit(method: JcMethod): Boolean {
        check(method.isClassInitializer)
        // TODO: add recursive static fields check: if static field of another class was read it should not be symbolic #CM
        return method is JcEnrichedVirtualMethod
    }

    private fun tryConcreteInvoke(stmt: JcMethodCall, state: JcState, exprResolver: JcExprResolver): Boolean {
        val method = stmt.method
        val arguments = stmt.arguments
        if (!methodIsInvokable(method))
            return false

        val signature = method.humanReadableSignature

        if (method.isClassInitializer) {
            if (shouldNotInvokeClinit(method))
                // Executing clinit symbolically
                return false

            // Executing clinit concretely
            ensureClinit(method.enclosingClass)
            state.skipMethodInvocationWithValue(stmt, ctx.voidValue)
            return true
        }

        if (concretizeInvocations.contains(signature) || concretization && !forbiddenInvocations.contains(signature)) {
            concretize(state, exprResolver, stmt, method)
            return true
        }

        if (!bindings.isWritable && concreteMutatingInvocations.contains(signature)) {
            // TODO: delete this
            // TODO: !shouldInvoke(signature) will do the thing #CM
            return false
        }
//        if (!shouldInvoke(method)) { // TODO: uncomment #CM
//            return false
//        }

        val parameterInfos = method.parameters
        val isStatic = method.isStatic
        var thisObj: Any? = null
        var parameters = arguments

        if (!isStatic) {
            val thisType = method.enclosingClass.toType()
            val obj = marshall.tryExprToFullyConcreteObj(arguments[0], thisType)
            if (!obj.hasValue)
                return false
            thisObj = obj.value
            parameters = arguments.drop(1)
        }

        val objParameters = mutableListOf<Any?>()
        check(parameterInfos.size == parameters.size)
        for (i in parameterInfos.indices) {
            val info = parameterInfos[i]
            val value = parameters[i]
            val type = ctx.cp.findTypeOrNull(info.type)!!
            val elem = marshall.tryExprToFullyConcreteObj(value, type)
            if (!elem.hasValue)
                return false
            objParameters.add(elem.value)
        }

        check(objParameters.size == parameters.size)
        if (!shouldInvoke(method)) { // TODO: delete #CM
            if (!forbiddenInvocations.contains(signature))
                println(ansiYellow + "Can be added $signature" + ansiReset)
            return false
        }
        println(ansiGreen + "Invoking $signature" + ansiReset)
        invoke(state, exprResolver, stmt, method, thisObj, objParameters)

        return true
    }

    override fun <Inst, State, Resolver> tryConcreteInvoke(stmt: Inst, state: State, exprResolver: Resolver): Boolean {
        stmt as JcMethodCall
        state as JcState
        exprResolver as JcExprResolver
        val success = tryConcreteInvoke(stmt, state, exprResolver)
        // If constructor was not invoked and memory is not writable, deleting default 'this' from concrete memory:
        // + No need to encode objects in inconsistent state (created via allocConcrete -- objects with default fields)
        // - During symbolic execution, 'this' may stay concrete
        if (!bindings.isWritable && !success && stmt.method.isConstructor) {
            val thisArg = stmt.arguments[0]
            if (thisArg is UConcreteHeapRef && bindings.contains(thisArg.address))
                bindings.remove(thisArg.address)
        }

        return success
    }

    fun endConcretize() {
        concretization = false
        bindings.applyBacktrack()
    }

    //endregion

    companion object {

        operator fun invoke(
            ctx: JcContext,
            typeConstraints: UTypeConstraints<JcType>,
        ): JcConcreteMemory {
            val bindings = JcConcreteMemoryBindings(ctx, typeConstraints)
            val stack = URegistersStack()
            val mocks = UIndexedMocker<JcMethod>()
            val regions: PersistentMap<UMemoryRegionId<*, *>, UMemoryRegion<*, *>> = persistentMapOf()
            val executor = Executors.newSingleThreadExecutor(JcThreadFactory())
            val memory = JcConcreteMemory(ctx, typeConstraints, stack, mocks, regions, executor, bindings)
            val storage = JcConcreteRegionStorage(ctx, memory)
            val marshall = Marshall(ctx, bindings, storage)
            memory.regionStorageVar = storage
            memory.marshallVar = marshall
            return memory
        }

        //region Concrete Invocations

        private val forbiddenInvocations = setOf(
            "org.springframework.context.support.GenericApplicationContext#getBeanFactory():org.springframework.beans.factory.config.ConfigurableListableBeanFactory",
            "org.springframework.web.context.support.ServletContextAwareProcessor#getServletConfig():jakarta.servlet.ServletConfig",
            "org.springframework.web.context.support.GenericWebApplicationContext#getServletContext():jakarta.servlet.ServletContext",
            "org.springframework.beans.factory.support.AbstractBeanFactory#getBeanPostProcessors():java.util.List",
            "org.thymeleaf.spring6.view.ThymeleafViewResolver#getViewClass():java.lang.Class",
            "org.springframework.beans.factory.support.AbstractBeanFactory#getParentBeanFactory():org.springframework.beans.factory.BeanFactory",
            "java.util.Objects#requireNonNull(java.lang.Object):java.lang.Object",
            "org.springframework.web.context.request.ServletRequestAttributes#getRequest():jakarta.servlet.http.HttpServletRequest",
            "org.thymeleaf.spring6.view.ThymeleafViewResolver#getViewNames():java.lang.String[]",
            "org.thymeleaf.spring6.view.ThymeleafViewResolver#getViewClass():java.lang.Class",
            "org.springframework.context.support.ApplicationObjectSupport#getApplicationContext():org.springframework.context.ApplicationContext",
            "org.springframework.context.support.GenericApplicationContext#getAutowireCapableBeanFactory():org.springframework.beans.factory.config.AutowireCapableBeanFactory",
            "org.springframework.context.support.AbstractApplicationContext#assertBeanFactoryActive():void",
            "java.util.concurrent.atomic.AtomicBoolean#get():boolean",
            "org.springframework.test.context.TestContextManager#getTestContext():org.springframework.test.context.TestContext",
            "org.springframework.test.context.support.DefaultTestContext#getApplicationContext():org.springframework.context.ApplicationContext",
            "org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate#loadContext(org.springframework.test.context.MergedContextConfiguration):org.springframework.context.ApplicationContext",
            "org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate#loadContextInternal(org.springframework.test.context.MergedContextConfiguration):org.springframework.context.ApplicationContext",
            "org.springframework.boot.test.context.SpringBootContextLoader#loadContext(org.springframework.test.context.MergedContextConfiguration):org.springframework.context.ApplicationContext",
            "java.lang.Object#<init>():void",
            "org.springframework.boot.test.context.SpringBootContextLoader#loadContext(org.springframework.test.context.MergedContextConfiguration,org.springframework.boot.test.context.SpringBootContextLoader\$Mode,org.springframework.context.ApplicationContextInitializer):org.springframework.context.ApplicationContext",
            "org.apache.commons.logging.LogFactory#getLog(java.lang.Class):org.apache.commons.logging.Log",
            "java.lang.ThreadLocal#set(java.lang.Object):void",
            "java.lang.ThreadLocal#<init>():void",
            "org.springframework.boot.SpringApplication#printBanner(org.springframework.core.env.ConfigurableEnvironment):org.springframework.boot.Banner",
            "org.springframework.boot.SpringApplication#afterRefresh(org.springframework.context.ConfigurableApplicationContext,org.springframework.boot.ApplicationArguments):void",
            "org.springframework.boot.SpringApplication#startAnalysis():void",
            "org.springframework.boot.SpringApplication#allControllerPaths():java.util.Map",
            "org.springframework.boot.SpringApplication#internalLog(java.lang.String[]):void",
            "org.springframework.web.context.request.RequestContextHolder#setRequestAttributes(org.springframework.web.context.request.RequestAttributes):void",
            "org.springframework.web.context.request.RequestContextHolder#setRequestAttributes(org.springframework.web.context.request.RequestAttributes,boolean):void",
            "org.springframework.mock.web.MockFilterChain#doFilter(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse):void",
            "org.springframework.web.filter.RequestContextFilter#doFilterInternal(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,jakarta.servlet.FilterChain):void",
            "java.lang.Boolean#<init>(boolean):void",
            "org.springframework.mock.web.MockFilterChain#doFilter(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse):void",
            "org.springframework.web.filter.OncePerRequestFilter#doFilter(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse,jakarta.servlet.FilterChain):void",
            "org.springframework.web.filter.FormContentFilter#doFilterInternal(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,jakarta.servlet.FilterChain):void",
            "org.springframework.web.servlet.DispatcherServlet#doDispatch(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter#handle(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,java.lang.Object):org.springframework.web.servlet.ModelAndView",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter#handleInternal(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,org.springframework.web.method.HandlerMethod):org.springframework.web.servlet.ModelAndView",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter#invokeHandlerMethod(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,org.springframework.web.method.HandlerMethod):org.springframework.web.servlet.ModelAndView",
            "org.springframework.web.method.annotation.ModelFactory#initModel(org.springframework.web.context.request.NativeWebRequest,org.springframework.web.method.support.ModelAndViewContainer,org.springframework.web.method.HandlerMethod):void",
            "org.springframework.web.method.annotation.ModelFactory#invokeModelAttributeMethods(org.springframework.web.context.request.NativeWebRequest,org.springframework.web.method.support.ModelAndViewContainer):void",
            "org.springframework.web.method.support.InvocableHandlerMethod#invokeForRequest(org.springframework.web.context.request.NativeWebRequest,org.springframework.web.method.support.ModelAndViewContainer,java.lang.Object[]):java.lang.Object",
            "org.springframework.web.method.support.InvocableHandlerMethod#getMethodArgumentValues(org.springframework.web.context.request.NativeWebRequest,org.springframework.web.method.support.ModelAndViewContainer,java.lang.Object[]):java.lang.Object[]",
            "org.springframework.web.method.support.HandlerMethodArgumentResolverComposite#resolveArgument(org.springframework.core.MethodParameter,org.springframework.web.method.support.ModelAndViewContainer,org.springframework.web.context.request.NativeWebRequest,org.springframework.web.bind.support.WebDataBinderFactory):java.lang.Object",
            "org.springframework.web.servlet.mvc.method.annotation.PathVariableMethodArgumentResolver#resolveArgument(org.springframework.core.MethodParameter,org.springframework.web.method.support.ModelAndViewContainer,org.springframework.web.context.request.NativeWebRequest,org.springframework.web.bind.support.WebDataBinderFactory):java.lang.Object",
            "org.apache.commons.logging.impl.NoOpLog#<init>():void",
            "java.util.AbstractMap#get(java.lang.Object):java.lang.Object",
            "java.util.AbstractMap#_getEntry(java.lang.Object):java.util.Map\$Entry",
            "java.util.AbstractMap#_getStorage():runtime.LibSLRuntime\$Map",
            "java.util.AbstractMap#createStorage(boolean):runtime.LibSLRuntime\$Map",
            "java.util.AbstractMap#createContainer(boolean):runtime.LibSLRuntime\$Map\$Container",
            "org.springframework.mock.web.MockHttpServletRequest#checkActive():void",
            "java.util.AbstractSet#createStorage(boolean):runtime.LibSLRuntime\$Map",
            "java.util.AbstractSet#createContainer(boolean):runtime.LibSLRuntime\$Map\$Container",
            "stub.java.util.map.AbstractMap_Entry#getValue():java.lang.Object",
            "org.apache.commons.logging.Log#isTraceEnabled():boolean",
            "org.springframework.web.servlet.view.AbstractCachingViewResolver#isCache():boolean",
            "org.thymeleaf.spring6.view.ThymeleafViewResolver#getExcludedViewNames():java.lang.String[]",
            "org.slf4j.LoggerFactory#getLogger(java.lang.Class):org.slf4j.Logger",
            "org.springframework.web.context.support.ServletContextAwareProcessor#getServletContext():jakarta.servlet.ServletContext",
            "org.usvm.api.Engine#assume(boolean):void",
            "runtime.LibSLRuntime\$Map#hasKey(java.lang.Object):boolean",
            "runtime.LibSLRuntime\$HashMapContainer#containsKey(java.lang.Object):boolean",
            "org.thymeleaf.spring6.view.ThymeleafViewResolver#getTemplateEngine():org.thymeleaf.spring6.ISpringTemplateEngine",
            "org.usvm.api.Engine#makeSymbolicMap():org.usvm.api.SymbolicMap",
            "org.thymeleaf.spring6.view.ThymeleafViewResolver#getForceContentType():boolean",
            "org.thymeleaf.spring6.view.ThymeleafViewResolver#getCharacterEncoding():java.lang.String",
            "org.thymeleaf.spring6.view.ThymeleafViewResolver#getProducePartialOutputWhileProcessing():boolean",
            "org.thymeleaf.spring6.view.ThymeleafViewResolver#getContentType():java.lang.String",
            "org.springframework.util.ClassUtils#getMainPackageName():java.lang.String",
            "org.springframework.util.ClassUtils#getPackageName(java.lang.Class):java.lang.String",
            "runtime.LibSLRuntime\$Map\$Container#containsKey(java.lang.Object):boolean",
            "runtime.LibSLRuntime\$Map#size():int",
            "runtime.LibSLRuntime\$HashMapContainer#size():int",
            "org.apache.commons.logging.Log#isDebugEnabled():boolean",
            "org.springframework.mock.web.MockHttpServletRequest#getRemoteAddr():java.lang.String",
            "jakarta.servlet.GenericServlet#getServletConfig():jakarta.servlet.ServletConfig",
            "org.springframework.mock.web.MockServletConfig#getServletName():java.lang.String",
            "org.springframework.mock.web.MockHttpServletResponse#getStatus():int",
            "org.springframework.mock.web.MockHttpServletResponse#isCommitted():boolean",
            "org.springframework.web.context.request.AbstractRequestAttributes#isRequestActive():boolean",
            "org.springframework.mock.web.MockServletContext#getContextPath():java.lang.String",
            "runtime.LibSLRuntime\$Map#get(java.lang.Object):java.lang.Object",
            "runtime.LibSLRuntime\$HashMapContainer#get(java.lang.Object):java.lang.Object",
            "org.usvm.api.Engine#makeSymbolicList():org.usvm.api.SymbolicList",
            "java.util.Map\$Entry#getValue():java.lang.Object",
            "org.springframework.web.servlet.view.UrlBasedViewResolver#getViewNames():java.lang.String[]",
            "org.springframework.web.servlet.view.UrlBasedViewResolver#getContentType():java.lang.String",
            "org.springframework.web.servlet.view.UrlBasedViewResolver#getRequestContextAttribute():java.lang.String",
            "org.springframework.web.servlet.view.UrlBasedViewResolver#getExposePathVariables():java.lang.Boolean",
            "org.springframework.web.servlet.view.UrlBasedViewResolver#getExposeContextBeansAsAttributes():java.lang.Boolean",
            "org.springframework.web.servlet.view.UrlBasedViewResolver#getExposedContextBeanNames():java.lang.String[]",
            "org.springframework.test.context.TestContextManager#<init>(java.lang.Class):void",
            "org.springframework.test.context.TestContextManager#<init>(org.springframework.test.context.TestContextBootstrapper):void",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#buildTestContext():org.springframework.test.context.TestContext",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#buildTestContext():org.springframework.test.context.TestContext",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#buildMergedContextConfiguration():org.springframework.test.context.MergedContextConfiguration",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#buildDefaultMergedContextConfiguration(java.lang.Class,org.springframework.test.context.CacheAwareContextLoaderDelegate):org.springframework.test.context.MergedContextConfiguration",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#buildMergedContextConfiguration(java.lang.Class,java.util.List,org.springframework.test.context.MergedContextConfiguration,org.springframework.test.context.CacheAwareContextLoaderDelegate,boolean):org.springframework.test.context.MergedContextConfiguration",
            "org.springframework.test.context.MergedContextConfiguration#<init>(java.lang.Class,java.lang.String[],java.lang.Class[],java.util.Set,java.lang.String[],java.util.List,java.lang.String[],java.util.Set,org.springframework.test.context.ContextLoader,org.springframework.test.context.CacheAwareContextLoaderDelegate,org.springframework.test.context.MergedContextConfiguration):void",
            "org.springframework.test.context.MergedContextConfiguration#processContextCustomizers(java.util.Set):java.util.Set",
            "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTestContextBootstrapper#processMergedContextConfiguration(org.springframework.test.context.MergedContextConfiguration):org.springframework.test.context.MergedContextConfiguration",
            "org.apache.commons.logging.Log#info(java.lang.Object):void",
            "org.springframework.web.filter.CharacterEncodingFilter#doFilterInternal(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,jakarta.servlet.FilterChain):void",
            "org.springframework.mock.web.MockFilterChain\$ServletFilterProxy#doFilter(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse,jakarta.servlet.FilterChain):void",
            "jakarta.servlet.http.HttpServlet#service(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse):void",
            "org.springframework.test.web.servlet.TestDispatcherServlet#service(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.FrameworkServlet#doGet(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.FrameworkServlet#processRequest(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.DispatcherServlet#doService(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.apache.commons.logging.LogFactory#getLog(java.lang.String):org.apache.commons.logging.Log",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#processMergedContextConfiguration(org.springframework.test.context.MergedContextConfiguration):org.springframework.test.context.MergedContextConfiguration",
            "org.springframework.boot.test.context.SpringBootContextLoader\$ContextLoaderHook#run(org.springframework.util.function.ThrowingSupplier):org.springframework.context.ApplicationContext",
            "org.springframework.boot.SpringApplication#withHook(org.springframework.boot.SpringApplicationHook,org.springframework.util.function.ThrowingSupplier):java.lang.Object",
            "org.springframework.boot.SpringApplication#run(java.lang.String[]):org.springframework.context.ConfigurableApplicationContext",
            "org.springframework.test.web.servlet.MockMvc#perform(org.springframework.test.web.servlet.RequestBuilder):org.springframework.test.web.servlet.ResultActions",
            "generated.org.springframework.boot.SymbolicValueFactory#createSymbolic(java.lang.Class):java.lang.Object",
            "org.usvm.api.Engine#makeNullableSymbolic(java.lang.Class):java.lang.Object",
            "org.usvm.api.Engine#makeSymbolicInt():int",
            "org.springframework.validation.DataBinder#getTarget():java.lang.Object",
            "org.springframework.web.servlet.mvc.method.annotation.ServletModelAttributeMethodProcessor#bindRequestParameters(org.springframework.web.bind.WebDataBinder,org.springframework.web.context.request.NativeWebRequest):void",
            "runtime.LibSLRuntime\$Map#union(runtime.LibSLRuntime\$Map):void",
            "runtime.LibSLRuntime\$HashMapContainer#merge(runtime.LibSLRuntime\$Map\$Container):void",
            "runtime.LibSLRuntime\$Map#duplicate():runtime.LibSLRuntime\$Map",
            "runtime.LibSLRuntime\$HashMapContainer#duplicate():runtime.LibSLRuntime\$Map\$Container",
            "org.thymeleaf.EngineConfiguration#getTemplateManager():org.thymeleaf.engine.TemplateManager",
            "org.thymeleaf.EngineConfiguration#getTemplateResolvers():java.util.Set",
            "java.util.Locale#getDefault(java.util.Locale\$Category):java.util.Locale",
            "runtime.LibSLRuntime\$ArrayActions#copy(java.lang.Object,int,java.lang.Object,int,int):void",
            "runtime.LibSLRuntime\$Map#remove(java.lang.Object):void",
            "runtime.LibSLRuntime\$HashMapContainer#remove(java.lang.Object):void",
            "org.springframework.context.event.SimpleApplicationEventMulticaster#getTaskExecutor():java.util.concurrent.Executor",
            "org.springframework.web.bind.support.SimpleSessionStatus#isComplete():boolean",
            "org.springframework.web.context.request.async.StandardServletAsyncWebRequest#<init>(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.view.UrlBasedViewResolver#getPrefix():java.lang.String",
            "org.springframework.context.event.SimpleApplicationEventMulticaster#getErrorHandler():org.springframework.util.ErrorHandler",
            "ch.qos.logback.classic.Logger#isTraceEnabled():boolean",
            "org.slf4j.LoggerFactory#getLogger(java.lang.String):org.slf4j.Logger",
            "org.springframework.mock.web.MockHttpServletRequest#getContextPath():java.lang.String",
            "org.springframework.mock.web.MockHttpServletRequest#getServletContext():jakarta.servlet.ServletContext",
            "java.util.stream.AbstractPipeline#isParallel():boolean",
            "org.thymeleaf.web.servlet.IServletWebRequest#getApplicationPath():java.lang.String",
            "org.thymeleaf.web.servlet.JakartaServletWebRequest#getRequestURI():java.lang.String",
            "org.thymeleaf.web.servlet.IServletWebRequest#getPathWithinApplication():java.lang.String",
            "org.thymeleaf.web.IWebRequest#getRequestPath():java.lang.String",
            "org.thymeleaf.web.servlet.JakartaServletWebRequest#getNativeRequestObject():java.lang.Object",
            "java.lang.StringBuilder#_assumeInvariants():void",
            "org.springframework.core.io.DefaultResourceLoader#getProtocolResolvers():java.util.Collection",
            "org.springframework.boot.SpringApplication#endOfPathAnalysis():void",
            "org.springframework.boot.SpringApplication#println(java.lang.String):void",
        )

        private val concreteMutatingInvocations = setOf(
//        "org.springframework.core.metrics.DefaultApplicationStartup#<init>():void",
            "org.springframework.core.io.support.SpringFactoriesLoader#forDefaultResourceLocation(java.lang.ClassLoader):org.springframework.core.io.support.SpringFactoriesLoader",
            "org.springframework.core.io.support.SpringFactoriesLoader\$FailureHandler#throwing():org.springframework.core.io.support.SpringFactoriesLoader\$FailureHandler",
//        "org.springframework.boot.SpringApplication#<init>(java.lang.Class[]):void",
            "org.springframework.boot.SpringApplication#createBootstrapContext():org.springframework.boot.DefaultBootstrapContext",
//        "org.apache.commons.logging.LogFactory#getLog(java.lang.Class):org.apache.commons.logging.Log",
            "java.util.IdentityHashMap#init(int):void",
//        "org.springframework.boot.SpringApplicationShutdownHook\$ApplicationContextClosedListener#<init>(org.springframework.boot.SpringApplicationShutdownHook):void",
            "java.util.concurrent.atomic.AtomicInteger#getAndAdd(int):int",
            "org.springframework.boot.SpringApplication#configureHeadlessProperty():void",
            "org.springframework.boot.SpringApplication#getRunListeners(java.lang.String[]):org.springframework.boot.SpringApplicationRunListeners",
            "org.springframework.boot.SpringApplicationRunListeners#starting(org.springframework.boot.ConfigurableBootstrapContext,java.lang.Class):void",
            "org.springframework.core.metrics.DefaultApplicationStartup\$DefaultStartupStep#<init>():void",
            "org.springframework.boot.SpringApplication#prepareEnvironment(org.springframework.boot.SpringApplicationRunListeners,org.springframework.boot.DefaultBootstrapContext,org.springframework.boot.ApplicationArguments):org.springframework.core.env.ConfigurableEnvironment",
            "org.springframework.boot.SpringApplication#getOrCreateEnvironment():org.springframework.core.env.ConfigurableEnvironment",
            "org.springframework.boot.SpringApplicationShutdownHook#enableShutdownHookAddition():void",
            // TODO: skip this method #CM
//        "org.springframework.boot.SpringApplication#printBanner(org.springframework.core.env.ConfigurableEnvironment):org.springframework.boot.Banner",
            "org.springframework.boot.SpringApplication#createApplicationContext():org.springframework.context.ConfigurableApplicationContext",
            "org.springframework.boot.SpringApplication#getSpringFactoriesInstances(java.lang.Class):java.util.List",
            "org.springframework.boot.SpringApplication#setInitializers(java.util.Collection):void",
            "org.springframework.context.support.GenericApplicationContext#setApplicationStartup(org.springframework.core.metrics.ApplicationStartup):void",
            "org.springframework.boot.SpringApplication#prepareContext(org.springframework.boot.DefaultBootstrapContext,org.springframework.context.ConfigurableApplicationContext,org.springframework.core.env.ConfigurableEnvironment,org.springframework.boot.SpringApplicationRunListeners,org.springframework.boot.ApplicationArguments,org.springframework.boot.Banner):void",
            "org.springframework.boot.SpringApplication#refreshContext(org.springframework.context.ConfigurableApplicationContext):void",
            "org.springframework.context.support.AbstractApplicationContext#getBeansOfType(java.lang.Class):java.util.Map",
            "org.springframework.test.web.servlet.setup.MockMvcBuilders#webAppContextSetup(org.springframework.web.context.WebApplicationContext):org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder",
            "org.springframework.test.web.servlet.setup.AbstractMockMvcBuilder#addFilter(jakarta.servlet.Filter,java.lang.String[]):org.springframework.test.web.servlet.setup.AbstractMockMvcBuilder",
            "org.springframework.test.web.servlet.setup.AbstractMockMvcBuilder#build():org.springframework.test.web.servlet.MockMvc",
            // TODO: delete #CM
//        "org.springframework.boot.SpringApplication#setListeners(java.util.Collection):void",
//        "org.springframework.boot.SpringApplicationShutdownHook#registerApplicationContext(org.springframework.context.ConfigurableApplicationContext):void",
            "org.springframework.core.metrics.DefaultApplicationStartup#start(java.lang.String):org.springframework.core.metrics.StartupStep",
            "java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int):void",
//        "org.springframework.test.context.TestContextManager#<init>(java.lang.Class):void",
            "org.springframework.test.context.BootstrapUtils#resolveTestContextBootstrapper(java.lang.Class):org.springframework.test.context.TestContextBootstrapper",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#getBootstrapContext():org.springframework.test.context.BootstrapContext",
            "org.springframework.test.context.support.DefaultBootstrapContext#getTestClass():java.lang.Class",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#getCacheAwareContextLoaderDelegate():org.springframework.test.context.CacheAwareContextLoaderDelegate",
            "org.springframework.test.context.TestContextAnnotationUtils#findAnnotationDescriptorForTypes(java.lang.Class,java.lang.Class[]):org.springframework.test.context.TestContextAnnotationUtils\$UntypedAnnotationDescriptor",
            "org.springframework.test.context.ContextConfigurationAttributes#<init>(java.lang.Class):void",
            "java.util.Collections#singletonList(java.lang.Object):java.util.List",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#resolveContextLoader(java.lang.Class,java.util.List):org.springframework.test.context.ContextLoader",
            // TODO: approximate to 'false'
            "org.apache.commons.logging.LogAdapter\$Slf4jLog#isTraceEnabled():boolean",
            "org.apache.commons.logging.LogAdapter\$Slf4jLog#isDebugEnabled():boolean",
            "org.springframework.util.Assert#notEmpty(java.util.Collection,java.lang.String):void",
            "java.util.ArrayList#<init>():void",
            "java.util.ArrayList#<init>(int):void",
            "java.util.Collections\$SingletonList#iterator():java.util.Iterator",
            "java.util.Collections\$1#hasNext():boolean",
            "java.util.Collections\$1#next():java.lang.Object",
            "org.springframework.boot.test.context.SpringBootContextLoader#processContextConfiguration(org.springframework.test.context.ContextConfigurationAttributes):void",
            "org.springframework.test.context.ContextConfigurationAttributes#getLocations():java.lang.String[]",
            "java.util.ArrayList#addAll(int,java.util.Collection):boolean",
            "org.springframework.test.context.ContextConfigurationAttributes#getClasses():java.lang.Class[]",
            "org.springframework.test.context.ContextConfigurationAttributes#getInitializers():java.lang.Class[]",
            "org.springframework.test.context.ContextConfigurationAttributes#isInheritLocations():boolean",
            "java.util.Collections#unmodifiableList(java.util.List):java.util.List",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#getContextCustomizers(java.lang.Class,java.util.List):java.util.Set",
            "org.springframework.util.Assert#state(boolean,java.util.function.Supplier):void",
            "org.springframework.test.context.support.TestPropertySourceUtils#buildMergedTestPropertySources(java.lang.Class):org.springframework.test.context.support.MergedTestPropertySources",
            "org.springframework.util.ClassUtils#toClassArray(java.util.Collection):java.lang.Class[]",
            "org.springframework.test.context.support.ApplicationContextInitializerUtils#resolveInitializerClasses(java.util.List):java.util.Set",
            "java.util.Collections#addAll(java.util.Collection,java.lang.Object[]):boolean",
            "org.springframework.test.context.support.ActiveProfilesUtils#resolveActiveProfiles(java.lang.Class):java.lang.String[]",
            "org.springframework.test.context.support.MergedTestPropertySources#getPropertySourceDescriptors():java.util.List",
            "org.springframework.test.context.support.MergedTestPropertySources#getProperties():java.lang.String[]",
            "org.springframework.test.context.MergedContextConfiguration#processStrings(java.lang.String[]):java.lang.String[]",
            "org.springframework.test.context.MergedContextConfiguration#processClasses(java.lang.Class[]):java.lang.Class[]",
            "org.springframework.test.context.MergedContextConfiguration#processContextInitializerClasses(java.util.Set):java.util.Set",
            "java.util.Collections#unmodifiableSet(java.util.Set):java.util.Set",
            "org.springframework.test.context.MergedContextConfiguration#processActiveProfiles(java.lang.String[]):java.lang.String[]",
            "java.util.stream.ReferencePipeline#map(java.util.function.Function):java.util.stream.Stream",
            "java.util.stream.ReferencePipeline#flatMap(java.util.function.Function):java.util.stream.Stream",
            "java.util.stream.ReferencePipeline#toArray(java.util.function.IntFunction):java.lang.Object[]",
            "java.util.LinkedHashSet#removeIf(java.util.function.Predicate):boolean",
            "org.springframework.test.context.MergedContextConfiguration#getClasses():java.lang.Class[]",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#containsNonTestComponent(java.lang.Class[]):boolean",
            "org.springframework.test.context.MergedContextConfiguration#hasLocations():boolean",
            "org.springframework.test.context.MergedContextConfiguration#getTestClass():java.lang.Class",
            "org.springframework.boot.test.context.AnnotatedClassFinder#findFromPackage(java.lang.String):java.lang.Class",
            "org.springframework.test.context.aot.DefaultAotTestAttributes#setAttribute(java.lang.String,java.lang.String):void",
            "org.apache.commons.logging.LogAdapter\$Slf4jLocationAwareLog#info(java.lang.Object):void",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#merge(java.lang.Class,java.lang.Class[]):java.lang.Class[]",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#getAndProcessPropertySourceProperties(org.springframework.test.context.MergedContextConfiguration):java.util.List",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#createModifiedConfig(org.springframework.test.context.MergedContextConfiguration,java.lang.Class[],java.lang.String[]):org.springframework.test.context.MergedContextConfiguration",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#getWebEnvironment(java.lang.Class):org.springframework.boot.test.context.SpringBootTest\$WebEnvironment",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#determineResourceBasePath(org.springframework.test.context.MergedContextConfiguration):java.lang.String",
            "org.springframework.test.context.web.WebMergedContextConfiguration#<init>(org.springframework.test.context.MergedContextConfiguration,java.lang.String):void",
            "org.springframework.test.context.support.DefaultTestContext#<init>(java.lang.Class,org.springframework.test.context.MergedContextConfiguration,org.springframework.test.context.CacheAwareContextLoaderDelegate):void",
            "org.springframework.test.context.support.DefaultTestContext#getTestClass():java.lang.Class",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#verifyConfiguration(java.lang.Class):void",
            "org.springframework.test.context.TestContextManager#copyTestContext(org.springframework.test.context.TestContext):org.springframework.test.context.TestContext",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#getTestExecutionListeners():java.util.List",
            "org.springframework.test.context.TestContextAnnotationUtils#findAnnotationDescriptor(java.lang.Class,java.lang.Class):org.springframework.test.context.TestContextAnnotationUtils\$AnnotationDescriptor",
            "org.springframework.test.context.TestContextManager#registerTestExecutionListeners(java.util.List):void",
            "org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate#replaceIfNecessary(org.springframework.test.context.MergedContextConfiguration):org.springframework.test.context.MergedContextConfiguration",
            "org.springframework.test.context.cache.DefaultContextCache#get(org.springframework.test.context.MergedContextConfiguration):org.springframework.context.ApplicationContext",
            "org.springframework.boot.SpringApplication#deduceMainApplicationClass():java.lang.Class",
            "org.springframework.boot.test.context.SpringBootContextLoader#configure(org.springframework.test.context.MergedContextConfiguration,org.springframework.boot.SpringApplication):void",
            "org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate#getContextLoader(org.springframework.test.context.MergedContextConfiguration):org.springframework.test.context.ContextLoader",
            "org.springframework.boot.test.context.SpringBootContextLoader#assertHasClassesOrLocations(org.springframework.test.context.MergedContextConfiguration):void",
            "org.springframework.boot.test.context.SpringBootTestAnnotation#get(org.springframework.test.context.MergedContextConfiguration):org.springframework.boot.test.context.SpringBootTestAnnotation",
            "org.springframework.test.context.MergedContextConfiguration#getContextCustomizers():java.util.Set",
            "org.springframework.boot.test.context.SpringBootTestAnnotation#getArgs():java.lang.String[]",
            "org.springframework.boot.test.context.SpringBootTestAnnotation#getUseMainMethod():org.springframework.boot.test.context.SpringBootTest\$UseMainMethod",
            "org.springframework.boot.test.context.SpringBootContextLoader#getMainMethod(org.springframework.test.context.MergedContextConfiguration,org.springframework.boot.test.context.SpringBootTest\$UseMainMethod):java.lang.reflect.Method",
            "org.springframework.boot.test.context.SpringBootContextLoader#getSpringApplication():org.springframework.boot.SpringApplication",
            "org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder#postProcessRequest(org.springframework.mock.web.MockHttpServletRequest):org.springframework.mock.web.MockHttpServletRequest",
            "org.springframework.mock.web.MockHttpServletRequest#setAttribute(java.lang.String,java.lang.Object):void",
            "org.springframework.web.filter.GenericFilterBean#getFilterName():java.lang.String",
            "java.util.ImmutableCollections\$ListItr#next():java.lang.Object",
            "org.springframework.web.filter.RequestContextFilter#initContextHolders(jakarta.servlet.http.HttpServletRequest,org.springframework.web.context.request.ServletRequestAttributes):void",
            "org.springframework.mock.web.MockHttpServletRequest#getLocale():java.util.Locale",
            "org.springframework.web.filter.FormContentFilter#parseIfNecessary(jakarta.servlet.http.HttpServletRequest):org.springframework.util.MultiValueMap",
            "org.springframework.util.CollectionUtils#isEmpty(java.util.Map):boolean",
            "org.springframework.mock.web.MockHttpServletRequest#setCharacterEncoding(java.lang.String):void",
            "org.springframework.mock.web.MockHttpServletRequest#removeAttribute(java.lang.String):void",
            "org.springframework.web.filter.RequestContextFilter#resetContextHolders():void",
            "org.springframework.test.web.servlet.TestDispatcherServlet#registerAsyncResultInterceptors(jakarta.servlet.http.HttpServletRequest):void",
            "org.springframework.web.context.request.async.WebAsyncUtils#getAsyncManager(jakarta.servlet.ServletRequest):org.springframework.web.context.request.async.WebAsyncManager",
            "org.springframework.web.context.request.async.WebAsyncManager#registerCallableInterceptor(java.lang.Object,org.springframework.web.context.request.async.CallableProcessingInterceptor):void",
            "org.springframework.web.servlet.FrameworkServlet#initContextHolders(jakarta.servlet.http.HttpServletRequest,org.springframework.context.i18n.LocaleContext,org.springframework.web.context.request.RequestAttributes):void",
            "org.springframework.web.servlet.support.AbstractFlashMapManager#retrieveAndUpdate(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):org.springframework.web.servlet.FlashMap",
            "org.springframework.web.util.ServletRequestPathUtils#parseAndCache(jakarta.servlet.http.HttpServletRequest):org.springframework.http.server.RequestPath",
            "org.springframework.http.HttpMethod#<init>(java.lang.String):void",
            "org.springframework.web.context.request.ServletWebRequest#checkNotModified(long):boolean",
            "org.springframework.web.context.request.ServletRequestAttributes#getResponse():jakarta.servlet.http.HttpServletResponse",
            "org.springframework.web.servlet.HandlerExecutionChain#applyPreHandle(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):boolean",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter#getDataBinderFactory(org.springframework.web.method.HandlerMethod):org.springframework.web.bind.support.WebDataBinderFactory",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter#getModelFactory(org.springframework.web.method.HandlerMethod,org.springframework.web.bind.support.WebDataBinderFactory):org.springframework.web.method.annotation.ModelFactory",
            "org.springframework.web.method.support.InvocableHandlerMethod#setHandlerMethodArgumentResolvers(org.springframework.web.method.support.HandlerMethodArgumentResolverComposite):void",
            "org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod#setHandlerMethodReturnValueHandlers(org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite):void",
            "org.springframework.web.method.support.InvocableHandlerMethod#setDataBinderFactory(org.springframework.web.bind.support.WebDataBinderFactory):void",
            "org.springframework.web.method.support.InvocableHandlerMethod#setParameterNameDiscoverer(org.springframework.core.ParameterNameDiscoverer):void",
            "org.springframework.web.method.support.InvocableHandlerMethod#setMethodValidator(org.springframework.validation.method.MethodValidator):void",
            "org.springframework.web.method.support.ModelAndViewContainer#addAllAttributes(java.util.Map):org.springframework.web.method.support.ModelAndViewContainer",
            "org.springframework.web.method.support.ModelAndViewContainer#setIgnoreDefaultModelOnRedirect(boolean):void",
            "org.springframework.web.context.request.async.WebAsyncUtils#createAsyncWebRequest(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):org.springframework.web.context.request.async.AsyncWebRequest",
            "org.springframework.web.context.request.async.StandardServletAsyncWebRequest#setTimeout(java.lang.Long):void",
            "org.springframework.web.context.request.async.WebAsyncManager#setTaskExecutor(org.springframework.core.task.AsyncTaskExecutor):void",
            "org.springframework.web.context.request.async.WebAsyncManager#setAsyncWebRequest(org.springframework.web.context.request.async.AsyncWebRequest):void",
            "org.springframework.web.context.request.async.WebAsyncManager#registerCallableInterceptors(org.springframework.web.context.request.async.CallableProcessingInterceptor[]):void",
            "org.springframework.web.context.request.async.WebAsyncManager#registerDeferredResultInterceptors(org.springframework.web.context.request.async.DeferredResultProcessingInterceptor[]):void",
            "org.springframework.web.context.request.async.WebAsyncManager#hasConcurrentResult():boolean",
            "org.springframework.validation.support.BindingAwareModelMap#put(java.lang.Object,java.lang.Object):java.lang.Object",
            "org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod#setResponseStatus(org.springframework.web.context.request.ServletWebRequest):void",
            "org.springframework.web.method.HandlerMethod#getResponseStatus():org.springframework.http.HttpStatusCode",
            "org.springframework.web.method.HandlerMethod#getResponseStatusReason():java.lang.String",
            "org.springframework.web.method.support.ModelAndViewContainer#setRequestHandled(boolean):void",
            "org.springframework.core.annotation.AnnotatedMethod#getReturnValueType(java.lang.Object):org.springframework.core.MethodParameter",
            "org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite#handleReturnValue(java.lang.Object,org.springframework.core.MethodParameter,org.springframework.web.method.support.ModelAndViewContainer,org.springframework.web.context.request.NativeWebRequest):void",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter#getModelAndView(org.springframework.web.method.support.ModelAndViewContainer,org.springframework.web.method.annotation.ModelFactory,org.springframework.web.context.request.NativeWebRequest):org.springframework.web.servlet.ModelAndView",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter#getSessionAttributesHandler(org.springframework.web.method.HandlerMethod):org.springframework.web.method.annotation.SessionAttributesHandler",
            "org.springframework.web.method.annotation.SessionAttributesHandler#hasSessionAttributes():boolean",
            "org.springframework.web.servlet.support.WebContentGenerator#prepareResponse(jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.DispatcherServlet#applyDefaultViewName(jakarta.servlet.http.HttpServletRequest,org.springframework.web.servlet.ModelAndView):void",
            "org.springframework.web.servlet.HandlerExecutionChain#applyPostHandle(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,org.springframework.web.servlet.ModelAndView):void",
            "org.springframework.web.servlet.DispatcherServlet#processDispatchResult(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,org.springframework.web.servlet.HandlerExecutionChain,org.springframework.web.servlet.ModelAndView,java.lang.Exception):void",
            "org.springframework.web.util.ServletRequestPathUtils#setParsedRequestPath(org.springframework.http.server.RequestPath,jakarta.servlet.ServletRequest):void",
            "org.springframework.web.servlet.FrameworkServlet#resetContextHolders(jakarta.servlet.http.HttpServletRequest,org.springframework.context.i18n.LocaleContext,org.springframework.web.context.request.RequestAttributes):void",
            "org.springframework.web.context.request.AbstractRequestAttributes#requestCompleted():void",
            "org.springframework.web.servlet.FrameworkServlet#logResult(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,java.lang.Throwable,org.springframework.web.context.request.async.WebAsyncManager):void",
            "org.springframework.web.servlet.FrameworkServlet#publishRequestHandledEvent(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,long,java.lang.Throwable):void",
            "jakarta.servlet.DispatcherType#\$values():jakarta.servlet.DispatcherType[]",
            "org.springframework.mock.web.MockHttpServletRequest#getDispatcherType():jakarta.servlet.DispatcherType",
            "java.lang.Enum#equals(java.lang.Object):boolean",
            "org.springframework.test.web.servlet.MockMvc#applyDefaultResultActions(org.springframework.test.web.servlet.MvcResult):void",
            "org.springframework.test.web.servlet.MockMvc\$1#<init>(org.springframework.test.web.servlet.MockMvc,org.springframework.test.web.servlet.MvcResult):void",
            "java.util.HashMap\$KeyIterator#next():java.lang.Object",
            "java.util.TreeMap\$KeyIterator#next():java.lang.Object",
            "org.springframework.test.web.servlet.request.MockMvcRequestBuilders#post(java.lang.String,java.lang.Object[]):org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder",
            "org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder#<init>(org.springframework.http.HttpMethod,java.lang.String,java.lang.Object[]):void",
            "java.util.ArrayList#toArray(java.lang.Object[]):java.lang.Object[]",
            "org.springframework.web.method.support.ModelAndViewContainer#mergeAttributes(java.util.Map):org.springframework.web.method.support.ModelAndViewContainer",
            "org.springframework.web.method.annotation.ModelFactory#getNextModelMethod(org.springframework.web.method.support.ModelAndViewContainer):org.springframework.web.method.annotation.ModelFactory\$ModelMethod",
            "org.springframework.web.method.support.ModelAndViewContainer#getModel():org.springframework.ui.ModelMap",
            "java.util.ArrayList#add(java.lang.Object):boolean",
            "java.util.HashMap#resize():java.util.HashMap\$Node[]",
            "java.util.LinkedHashMap\$LinkedKeyIterator#next():java.lang.Object",
            "java.util.LinkedHashMap\$LinkedEntryIterator#next():java.lang.Object",
            "java.util.LinkedHashMap\$LinkedHashIterator#nextNode():java.util.LinkedHashMap\$Entry",
            "java.util.ArrayList\$Itr#next():java.lang.Object",
            "java.util.LinkedHashMap#clear():void",
            "org.springframework.mock.web.MockHttpServletRequest#getSession(boolean):jakarta.servlet.http.HttpSession",
            "org.springframework.web.util.WebUtils#getSessionId(jakarta.servlet.http.HttpServletRequest):java.lang.String",
            "java.lang.StringBuilder#append(java.lang.String):java.lang.StringBuilder",
            "java.util.HashMap#putIfAbsent(java.lang.Object,java.lang.Object):java.lang.Object",
            "java.util.AbstractMap#putIfAbsent(java.lang.Object,java.lang.Object):java.lang.Object",
            "org.springframework.web.method.annotation.SessionAttributesHandler#isHandlerSessionAttribute(java.lang.String,java.lang.Class):boolean",
            "java.util.stream.ReduceOps\$3ReducingSink#begin(long):void",
            "org.springframework.mock.web.MockHttpServletResponse#setLocale(java.util.Locale):void",
            "java.util.concurrent.CopyOnWriteArrayList\$COWIterator#next():java.lang.Object",
            "java.util.concurrent.ConcurrentHashMap#remove(java.lang.Object):java.lang.Object",
            "java.util.HashMap#clear():void",
            "java.util.AbstractMap#clear():void",
            "java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object):java.lang.Object",
            "java.util.HashMap#put(java.lang.Object,java.lang.Object):java.lang.Object",
            "java.util.LinkedHashMap#remove(java.lang.Object):java.lang.Object",
            "java.util.HashMap#remove(java.lang.Object):java.lang.Object",
            "java.util.AbstractMap#remove(java.lang.Object):java.lang.Object",
            "org.springframework.mock.web.MockHttpServletResponse#resetBuffer():void",
            "java.io.ByteArrayOutputStream#reset():void",
            "java.util.LinkedList#clear():void",
            "java.util.AbstractList#clear():void",
            "org.springframework.mock.web.HeaderValueHolder#setValue(java.lang.Object):void",
            "org.springframework.util.LinkedCaseInsensitiveMap#computeIfAbsent(java.lang.Object,java.util.function.Function):java.lang.Object",
            "java.util.AbstractMap#put(java.lang.Object,java.lang.Object):java.lang.Object",
            "org.springframework.web.context.request.ServletRequestAttributes#setAttribute(java.lang.String,java.lang.Object,int):void",
            "org.springframework.web.servlet.view.AbstractUrlBasedView#setUrl(java.lang.String):void",
            "java.util.HashMap#putAll(java.util.Map):void",
            "java.util.AbstractMap#putAll(java.util.Map):void",
            "java.util.HashMap#forEach(java.util.function.BiConsumer):void",
            "java.util.AbstractMap#forEach(java.util.function.BiConsumer):void",
            "java.lang.ThreadLocal#remove():void",
            "org.springframework.context.i18n.LocaleContextHolder#setLocale(java.util.Locale,boolean):void",
            "org.springframework.context.i18n.LocaleContextHolder#setLocaleContext(org.springframework.context.i18n.LocaleContext,boolean):void",
        )

        private val concreteNonMutatingInvocations = setOf(
            "org.springframework.web.method.HandlerMethod#shouldValidateReturnValue():boolean",
            "java.lang.String#startsWith(java.lang.String):boolean",
            "org.springframework.core.annotation.AnnotatedMethod#isVoid():boolean",
            "java.lang.Integer#numberOfLeadingZeros(int):int",
            "org.springframework.web.bind.annotation.ModelAttribute#value():java.lang.String",
            "org.springframework.core.annotation.AnnotatedMethod\$AnnotatedMethodParameter#getMethodAnnotation(java.lang.Class):java.lang.annotation.Annotation",
            "org.springframework.core.annotation.AnnotatedMethod#getReturnType():org.springframework.core.MethodParameter",
            "org.springframework.web.bind.annotation.ModelAttribute#name():java.lang.String",
            "org.springframework.web.method.support.ModelAndViewContainer#containsAttribute(java.lang.String):boolean",
            "java.util.ArrayList#isEmpty():boolean",
            "java.lang.String#isBlank():boolean",
            "org.springframework.web.method.annotation.ModelFactory#getNameForReturnValue(java.lang.Object,org.springframework.core.MethodParameter):java.lang.String",
            "org.springframework.web.bind.annotation.ModelAttribute#binding():boolean",
            "org.springframework.util.StringUtils#hasText(java.lang.String):boolean",
            "org.springframework.web.method.support.ModelAndViewContainer#useDefaultModel():boolean",
            "java.lang.Class#getName():java.lang.String",
            "java.lang.String#isEmpty():boolean",
            "runtime.LibSLRuntime#toString(java.lang.Object):java.lang.String",
            "java.lang.StringConcatHelper#stringOf(java.lang.Object):java.lang.String",
            "java.lang.Object#toString():java.lang.String",
            "org.usvm.api.internal.StringConcatUtil#concat(java.lang.Object[]):java.lang.String",
            "java.lang.String#toString():java.lang.String",
            "java.lang.String#concat(java.lang.String):java.lang.String",
            "java.lang.StringConcatHelper#simpleConcat(java.lang.Object,java.lang.Object):java.lang.String",
            "java.util.HashMap#hash(java.lang.Object):int",
            "java.lang.Object#hashCode():int",
            "java.lang.String#isLatin1():boolean",
            "java.lang.StringLatin1#hashCode(byte[]):int",
            "org.springframework.web.method.annotation.ModelFactory#findSessionAttributeArguments(org.springframework.web.method.HandlerMethod):java.util.List",
            "org.springframework.core.MethodParameter#hasParameterAnnotation(java.lang.Class):boolean",
            "org.springframework.core.MethodParameter#getParameterAnnotation(java.lang.Class):java.lang.annotation.Annotation",
            "org.springframework.core.annotation.AnnotatedMethod\$AnnotatedMethodParameter#getParameterAnnotations():java.lang.annotation.Annotation[]",
            "java.lang.Class#isInstance(java.lang.Object):boolean",
            "org.springframework.web.method.annotation.ModelFactory#getNameForParameter(org.springframework.core.MethodParameter):java.lang.String",
            "java.lang.String#length():int",
            "java.lang.StringConcatHelper#initialCoder():long",
            "java.lang.StringConcatHelper#checkOverflow(long):long",
            "java.lang.String#coder():byte",
            "java.util.ArrayList#iterator():java.util.Iterator",
            "java.lang.Class#desiredAssertionStatus():boolean",
            "java.lang.String#toCharArray():char[]",
            "runtime.LibSLRuntime#toString(char[]):java.lang.String",
            "java.util.concurrent.ConcurrentHashMap#tableSizeFor(int):int",
            "java.lang.Class#getSimpleName():java.lang.String",
            "org.springframework.core.annotation.AnnotatedMethod#getMethodParameters():org.springframework.core.MethodParameter[]",
            "org.springframework.util.ObjectUtils#isEmpty(java.lang.Object[]):boolean",
            "org.springframework.core.annotation.AnnotatedMethod#findProvidedArgument(org.springframework.core.MethodParameter,java.lang.Object[]):java.lang.Object",
            "org.springframework.web.context.request.async.StandardServletAsyncWebRequest#isAsyncStarted():boolean",
            "org.springframework.mock.web.MockHttpServletRequest#getAttribute(java.lang.String):java.lang.Object",
            "org.springframework.web.method.annotation.ModelAttributeMethodProcessor#wrapAsOptionalIfNecessary(org.springframework.core.MethodParameter,java.lang.Object):java.lang.Object",
            "org.springframework.validation.DefaultMessageCodesResolver\$Format#\$values():org.springframework.validation.DefaultMessageCodesResolver\$Format[]",
            "org.springframework.validation.DefaultMessageCodesResolver\$Format#<init>(java.lang.String,int):void",
            "org.springframework.validation.AbstractBindingResult#hasErrors():boolean",
            "java.util.HashSet#contains(java.lang.Object):boolean",
            "org.springframework.web.context.request.ServletWebRequest#getNativeRequest(java.lang.Class):java.lang.Object",
            "org.springframework.validation.DataBinder#shouldNotBindPropertyValues():boolean",
            "org.springframework.web.util.WebUtils#getParametersStartingWith(jakarta.servlet.ServletRequest,java.lang.String):java.util.Map",
            "java.util.TreeMap#size():int",
            "ch.qos.logback.core.spi.FilterReply#\$values():ch.qos.logback.core.spi.FilterReply[]",
            "org.springframework.web.util.WebUtils#getNativeRequest(jakarta.servlet.ServletRequest,java.lang.Class):java.lang.Object",
            "org.springframework.web.bind.ServletRequestDataBinder#isFormDataPost(jakarta.servlet.ServletRequest):boolean",
            "org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder#getUriVars(jakarta.servlet.ServletRequest):java.util.Map",
            "org.springframework.web.bind.WebDataBinder#getFieldDefaultPrefix():java.lang.String",
            "org.springframework.beans.MutablePropertyValues#getPropertyValues():org.springframework.beans.PropertyValue[]",
            "org.springframework.web.bind.WebDataBinder#getFieldMarkerPrefix():java.lang.String",
            "org.springframework.validation.DataBinder#getRequiredFields():java.lang.String[]",
            "org.springframework.validation.DataBinder#getPropertyAccessor():org.springframework.beans.ConfigurablePropertyAccessor",
            "org.springframework.validation.DataBinder#isIgnoreUnknownFields():boolean",
            "org.springframework.validation.DataBinder#isIgnoreInvalidFields():boolean",
            "org.springframework.beans.MutablePropertyValues#getPropertyValueList():java.util.List",
            "java.util.ArrayList\$Itr#hasNext():boolean",
            "org.springframework.core.MethodParameter#getParameterType():java.lang.Class",
            "org.springframework.validation.AbstractBindingResult#getModel():java.util.Map",
            "java.util.LinkedHashMap#keySet():java.util.Set",
            "java.util.LinkedHashMap\$LinkedKeySet#iterator():java.util.Iterator",
            "java.util.LinkedHashMap\$LinkedHashIterator#hasNext():boolean",
            "java.util.HashMap#getNode(java.lang.Object):java.util.HashMap\$Node",
            "java.util.LinkedHashMap#entrySet():java.util.Set",
            "java.util.LinkedHashMap\$LinkedEntrySet#iterator():java.util.Iterator",
            "java.util.HashMap\$Node#getKey():java.lang.Object",
            "java.util.HashMap\$Node#getValue():java.lang.Object",
            "org.springframework.core.annotation.AnnotatedMethod\$AnnotatedMethodParameter#getMethod():java.lang.reflect.Method",
            "org.springframework.core.KotlinDetector#isKotlinReflectPresent():boolean",
            "org.springframework.data.domain.Sort#unsorted():org.springframework.data.domain.Sort",
            "java.lang.reflect.Method#getDeclaringClass():java.lang.Class",
            "org.springframework.core.MethodParameter#getMethod():java.lang.reflect.Method",
            "java.lang.Class#isAssignableFrom(java.lang.Class):boolean",
            "java.util.stream.IntStream#range(int,int):java.util.stream.IntStream",
            "java.lang.Throwable#getMessage():java.lang.String",
            "java.lang.Throwable#getCause():java.lang.Throwable",
            "java.util.ArrayList\$Itr#checkForComodification():void",
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver#shouldApplyTo(jakarta.servlet.http.HttpServletRequest,java.lang.Object):boolean",
            "org.springframework.web.method.HandlerMethod#getBeanType():java.lang.Class",
            "java.util.ArrayList#get(int):java.lang.Object",
            "org.springframework.web.context.request.async.WebAsyncManager#isConcurrentHandlingStarted():boolean",
            "java.lang.Thread#currentThread():java.lang.Thread",
            "java.lang.Class#getClassLoader():java.lang.ClassLoader",
            "org.springframework.util.ClassUtils#isPresent(java.lang.String,java.lang.ClassLoader):boolean",
            "java.util.concurrent.ConcurrentHashMap#isEmpty():boolean",
            "java.lang.System#currentTimeMillis():long",
            "org.springframework.mock.web.MockHttpServletRequest#getRequestURI():java.lang.String",
            "java.util.LinkedHashMap#values():java.util.Collection",
            "org.springframework.core.ResolvableType#forClass(java.lang.Class):org.springframework.core.ResolvableType",
            "org.springframework.util.ClassUtils#isCacheSafe(java.lang.Class,java.lang.ClassLoader):boolean",
            "java.util.LinkedHashSet#iterator():java.util.Iterator",
            "java.util.LinkedHashMap\$LinkedValues#iterator():java.util.Iterator",
            "org.springframework.core.ResolvableType#forInstance(java.lang.Object):org.springframework.core.ResolvableType",
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver#shouldApplyTo(jakarta.servlet.http.HttpServletRequest,java.lang.Object):boolean",
            "java.lang.reflect.Method#getParameterCount():int",
            "org.springframework.core.annotation.AnnotatedMethod#hasMethodAnnotation(java.lang.Class):boolean",
            "java.lang.Boolean#getBoolean(java.lang.String):boolean",
            "java.lang.System#getProperty(java.lang.String):java.lang.String",
            "org.springframework.core.MethodParameter#validateIndex(java.lang.reflect.Executable,int):int",
            "org.springframework.web.method.HandlerMethod#getContainingClass():java.lang.Class",
            "java.lang.reflect.Method#getReturnType():java.lang.Class",
            "org.springframework.web.servlet.mvc.method.annotation.ReactiveTypeHandler#isReactiveType(java.lang.Class):boolean",
            "java.lang.StringBuilder#toString():java.lang.String",
            "org.springframework.core.annotation.AnnotatedElementUtils#hasAnnotation(java.lang.reflect.AnnotatedElement,java.lang.Class):boolean",
            "java.lang.Class#isArray():boolean",
            "org.springframework.beans.BeanUtils#isSimpleValueType(java.lang.Class):boolean",
            "org.springframework.util.ClassUtils#isSimpleValueType(java.lang.Class):boolean",
            "org.springframework.util.ClassUtils#isPrimitiveOrWrapper(java.lang.Class):boolean",
            "java.lang.Class#isPrimitive():boolean",
            "org.springframework.boot.autoconfigure.validation.ValidatorAdapter#supports(java.lang.Class):boolean",
            "org.springframework.web.bind.annotation.InitBinder#value():java.lang.String[]",
            "org.springframework.beans.PropertyAccessorUtils#canonicalPropertyName(java.lang.String):java.lang.String",
            "java.lang.String#toLowerCase():java.lang.String",
            "org.springframework.web.method.annotation.ModelFactory#isBindingCandidate(java.lang.String,java.lang.Object):boolean",
            "org.springframework.web.servlet.mvc.method.annotation.ViewNameMethodReturnValueHandler#isRedirectViewName(java.lang.String):boolean",
            "java.util.ArrayList#size():int",
            "java.util.HashSet#isEmpty():boolean",
            "java.lang.StringLatin1#toLowerCase(java.lang.String,byte[],java.util.Locale):java.lang.String",
            "org.springframework.util.LinkedCaseInsensitiveMap#containsKey(java.lang.Object):boolean",
            "org.springframework.util.LinkedCaseInsensitiveMap#convertKey(java.lang.String):java.lang.String",
            "org.springframework.util.LinkedCaseInsensitiveMap#convertKey(java.lang.String):java.lang.String",
            "java.lang.String#toLowerCase(java.util.Locale):java.lang.String",
            "org.springframework.http.CacheControl#empty():org.springframework.http.CacheControl",
            "org.springframework.test.web.servlet.TestDispatcherServlet#getMvcResult(jakarta.servlet.ServletRequest):org.springframework.test.web.servlet.DefaultMvcResult",
            "org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver#resolveLocale(jakarta.servlet.http.HttpServletRequest):java.util.Locale",
            "java.util.LinkedList#getFirst():java.lang.Object",
            "java.util.Locale#toLanguageTag():java.lang.String",
            "org.springframework.http.CacheControl#getHeaderValue():java.lang.String",
            "org.springframework.web.servlet.FrameworkServlet#getUsernameForRequest(jakarta.servlet.http.HttpServletRequest):java.lang.String",
            "org.springframework.web.servlet.view.ContentNegotiatingViewResolver#getMediaTypes(jakarta.servlet.http.HttpServletRequest):java.util.List",
            "org.springframework.beans.BeanUtils#instantiateClass(java.lang.Class):java.lang.Object",
            "org.springframework.util.ClassUtils#getUserClass(java.lang.Object):java.lang.Class",
            "org.springframework.util.ClassUtils#getUserClass(java.lang.Class):java.lang.Class",
            "org.springframework.util.ObjectUtils#unwrapOptional(java.lang.Object):java.lang.Object",
            "org.springframework.context.support.GenericApplicationContext#getClassLoader():java.lang.ClassLoader",
            "java.util.LinkedHashSet#isEmpty():boolean",
            "java.util.HashMap#isEmpty():boolean",
            "java.util.AbstractMap#isEmpty():boolean",
            "java.lang.Class#isRecord():boolean",
            "java.lang.Class#getSuperclass():java.lang.Class",
            "org.springframework.util.ReflectionUtils#getDeclaredFields(java.lang.Class):java.lang.reflect.Field[]",
            "org.springframework.util.StringUtils#hasLength(java.lang.String):boolean",
            "org.springframework.beans.factory.annotation.InjectionMetadata#needsRefresh(org.springframework.beans.factory.annotation.InjectionMetadata,java.lang.Class):boolean",
            "org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor#buildPersistenceMetadata(java.lang.Class):org.springframework.beans.factory.annotation.InjectionMetadata",
            "org.springframework.core.annotation.AnnotationUtils#isCandidateClass(java.lang.Class,java.lang.Class):boolean",
            "org.springframework.context.annotation.CommonAnnotationBeanPostProcessor#loadAnnotationType(java.lang.String):java.lang.Class",
            "org.springframework.util.ClassUtils#forName(java.lang.String,java.lang.ClassLoader):java.lang.Class",
            "org.springframework.context.annotation.CommonAnnotationBeanPostProcessor#buildResourceMetadata(java.lang.Class):org.springframework.beans.factory.annotation.InjectionMetadata",
            "java.util.concurrent.CopyOnWriteArrayList#iterator():java.util.Iterator",
            "org.springframework.boot.context.properties.bind.BindMethod#\$values():org.springframework.boot.context.properties.bind.BindMethod[]",
            "org.springframework.core.annotation.MergedAnnotation#missing():org.springframework.core.annotation.MergedAnnotation",
            "org.springframework.core.annotation.MissingMergedAnnotation#getInstance():org.springframework.core.annotation.MergedAnnotation",
            "org.springframework.core.annotation.MissingMergedAnnotation#isPresent():boolean",
            "org.springframework.boot.context.properties.ConfigurationPropertiesBean#findMergedAnnotation(java.lang.reflect.AnnotatedElement,java.lang.Class):org.springframework.core.annotation.MergedAnnotation",
            "org.springframework.core.annotation.MergedAnnotations#from(java.lang.reflect.AnnotatedElement,org.springframework.core.annotation.MergedAnnotations\$SearchStrategy):org.springframework.core.annotation.MergedAnnotations",
            "org.springframework.core.annotation.MergedAnnotations\$SearchStrategy#\$values():org.springframework.core.annotation.MergedAnnotations\$SearchStrategy[]",
            "org.springframework.core.annotation.TypeMappedAnnotations#stream(java.lang.Class):java.util.stream.Stream",
            "java.lang.String#compareTo(java.lang.Object):int",
            "java.util.stream.StreamShape#\$values():java.util.stream.StreamShape[]",
            "java.util.stream.StreamOpFlag\$Type#\$values():java.util.stream.StreamOpFlag\$Type[]",
            "java.util.stream.StreamOpFlag#set(java.util.stream.StreamOpFlag\$Type):java.util.stream.StreamOpFlag\$MaskBuilder",
            "java.util.stream.StreamOpFlag\$MaskBuilder#set(java.util.stream.StreamOpFlag\$Type):java.util.stream.StreamOpFlag\$MaskBuilder",
            "java.util.stream.StreamOpFlag\$MaskBuilder#setAndClear(java.util.stream.StreamOpFlag\$Type):java.util.stream.StreamOpFlag\$MaskBuilder",
            "java.util.stream.StreamOpFlag\$MaskBuilder#mask(java.util.stream.StreamOpFlag\$Type,java.lang.Integer):java.util.stream.StreamOpFlag\$MaskBuilder",
            "java.util.stream.StreamOpFlag\$MaskBuilder#build():java.util.Map",
            "java.util.stream.StreamOpFlag\$MaskBuilder#clear(java.util.stream.StreamOpFlag\$Type):java.util.stream.StreamOpFlag\$MaskBuilder",
            "java.util.stream.StreamOpFlag#\$values():java.util.stream.StreamOpFlag[]",
            "java.util.stream.StreamOpFlag#createMask(java.util.stream.StreamOpFlag\$Type):int",
            "java.lang.Object#clone():java.lang.Object",
            "java.util.stream.StreamOpFlag#createFlagMask():int",
            "java.util.stream.StreamOpFlag#values():java.util.stream.StreamOpFlag[]",
            "java.util.stream.StreamOpFlag#combineOpFlags(int,int):int",
            "java.util.stream.StreamOpFlag#getMask(int):int",
            // TODO: delete? creates lambda #CM
            "java.util.stream.Collectors#toSet():java.util.stream.Collector",
            "java.util.stream.ReduceOps#makeRef(java.util.stream.Collector):java.util.stream.TerminalOp",
            "java.util.stream.ReduceOps\$3#getOpFlags():int",
            "java.util.stream.ReduceOps\$3#makeSink():java.util.stream.ReduceOps\$AccumulatingSink",
            "java.util.stream.StreamOpFlag#isKnown(int):boolean",
            "java.util.Spliterator#getExactSizeIfKnown():long",
            "org.springframework.core.annotation.TypeMappedAnnotations\$AggregatesSpliterator#characteristics():int",
            "org.springframework.core.annotation.AnnotationUtils#isCandidateClass(java.lang.Class,java.util.Collection):boolean",
            "java.util.concurrent.CopyOnWriteArrayList\$COWIterator#hasNext():boolean",
            "java.util.function.Supplier#get():java.lang.Object",
            "org.springframework.core.annotation.TypeMappedAnnotations\$Aggregate#size():int",
            "java.util.stream.Collectors\$CollectorImpl#characteristics():java.util.Set",
            "java.util.stream.Collector\$Characteristics#\$values():java.util.stream.Collector\$Characteristics[]",
            "java.util.Collections\$UnmodifiableCollection#contains(java.lang.Object):boolean",
            "java.util.RegularEnumSet#contains(java.lang.Object):boolean",
            "java.util.stream.ReduceOps\$3#makeSink():java.util.stream.ReduceOps\$AccumulatingSink",
            "org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor#findLifecycleMetadata(java.lang.Class):org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor\$LifecycleMetadata",
            "org.springframework.beans.factory.support.AbstractBeanFactory#getBeanPostProcessorCount():int",
            "java.lang.Boolean#equals(java.lang.Object):boolean",
            "org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator#isInfrastructureClass(java.lang.Class):boolean",
            "org.springframework.aop.support.AopUtils#findAdvisorsThatCanApply(java.util.List,java.lang.Class):java.util.List",
            "org.springframework.aop.support.AopUtils#canApply(org.springframework.aop.Advisor,java.lang.Class):boolean",
            "org.springframework.aop.support.AopUtils#canApply(org.springframework.aop.Advisor,java.lang.Class,boolean):boolean",
            "org.thymeleaf.spring6.view.ThymeleafViewResolver#getStaticVariables():java.util.Map",
            "java.util.Collections\$UnmodifiableMap#entrySet():java.util.Set",
            "org.springframework.web.accept.ContentNegotiationManager#resolveFileExtensions(org.springframework.http.MediaType):java.util.List",
            "java.util.Collections\$EmptyList#iterator():java.util.Iterator",
            "java.util.Collections#emptyIterator():java.util.Iterator",
            "java.util.Collections\$EmptyIterator#hasNext():boolean",
            "org.springframework.util.CollectionUtils#isEmpty(java.util.Collection):boolean",
            "org.springframework.http.MediaType#parseMediaType(java.lang.String):org.springframework.http.MediaType",
            "org.springframework.http.MediaType#isCompatibleWith(org.springframework.http.MediaType):boolean",
            "org.springframework.http.MediaType#removeQualityValue():org.springframework.http.MediaType",
            "org.thymeleaf.web.servlet.JakartaServletWebApplication#buildApplication(jakarta.servlet.ServletContext):org.thymeleaf.web.servlet.JakartaServletWebApplication",
            "java.lang.Class#getDeclaredField(java.lang.String):java.lang.reflect.Field",
            "java.lang.reflect.Field#get(java.lang.Object):java.lang.Object",
            "org.springframework.web.servlet.i18n.AbstractLocaleResolver#getDefaultLocale():java.util.Locale",
            "org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver#getSupportedLocales():java.util.List",
            "org.springframework.web.util.WebUtils#getDefaultHtmlEscape(jakarta.servlet.ServletContext):java.lang.Boolean",
            "org.springframework.mock.web.MockServletContext#getInitParameter(java.lang.String):java.lang.String",
            "org.springframework.web.util.WebUtils#getResponseEncodedHtmlEscape(jakarta.servlet.ServletContext):java.lang.Boolean",
            "org.springframework.context.support.AbstractApplicationContext#containsBean(java.lang.String):boolean",
            "java.lang.Thread#getContextClassLoader():java.lang.ClassLoader",
            "java.nio.charset.Charset#forName(java.lang.String):java.nio.charset.Charset",
            "org.springframework.util.MimeType#isConcrete():boolean",
            "org.springframework.util.MimeType#isWildcardType():boolean",
            "org.thymeleaf.util.ContentTypeUtils#combineContentTypeAndCharset(java.lang.String,java.nio.charset.Charset):java.lang.String",
            "org.thymeleaf.util.ContentTypeUtils#computeCharsetFromContentType(java.lang.String):java.nio.charset.Charset",
            "java.util.Collections\$UnmodifiableMap\$UnmodifiableEntrySet#iterator():java.util.Iterator",
            "org.springframework.web.servlet.view.InternalResourceViewResolver#instantiateView():org.springframework.web.servlet.view.AbstractUrlBasedView",
            "org.springframework.web.servlet.view.UrlBasedViewResolver#getSuffix():java.lang.String",
            "org.springframework.web.servlet.view.UrlBasedViewResolver#getAttributesMap():java.util.Map",
            "org.springframework.test.context.cache.DefaultContextCache#getFailureCount(org.springframework.test.context.MergedContextConfiguration):int",
            "java.util.Collection#stream():java.util.stream.Stream",
            "java.util.Collections\$UnmodifiableCollection#stream():java.util.stream.Stream",
            "org.springframework.mock.web.MockHttpServletResponse#containsHeader(java.lang.String):boolean",
            "org.springframework.util.MimeType#getCharset():java.nio.charset.Charset",
            "java.nio.charset.Charset#name():java.lang.String",
            "java.lang.String#indexOf(java.lang.String):int",
            "java.lang.StringLatin1#indexOf(byte[],byte[]):int",
            "java.lang.StringLatin1#indexOf(byte[],int,byte[],int,int):int",
            "sun.nio.cs.UTF_8#newEncoder():java.nio.charset.CharsetEncoder",
            "java.nio.ByteBuffer#allocate(int):java.nio.ByteBuffer",
            "org.thymeleaf.util.ContentTypeUtils#computeTemplateModeForContentType(java.lang.String):org.thymeleaf.templatemode.TemplateMode",
            "org.thymeleaf.util.ContentTypeUtils#isContentTypeSSE(java.lang.String):boolean",
            "org.thymeleaf.util.ContentTypeUtils#isContentType(java.lang.String,java.lang.String):boolean",
            "java.lang.System#nanoTime():long",
            "java.util.Collections\$UnmodifiableCollection#iterator():java.util.Iterator",
            "org.thymeleaf.util.PatternSpec#isEmpty():boolean",
            "org.thymeleaf.util.StringUtils#isEmptyOrWhitespace(java.lang.String):boolean",
            "java.lang.String#charAt(int):char",
            "org.thymeleaf.TemplateEngine#threadIndex():java.lang.String",
            "java.lang.Thread#getName():java.lang.String",
            "java.util.LinkedHashSet#<init>():void",
            "java.util.LinkedHashMap#<init>(int,float):void",
            "java.lang.Float#isNaN(float):boolean",
            "java.util.HashMap#tableSizeFor(int):int",
            "org.springframework.boot.Banner\$Mode#\$values():org.springframework.boot.Banner\$Mode[]",
            "java.util.Collections#emptySet():java.util.Set",
            "org.springframework.boot.DefaultApplicationContextFactory#<init>():void",
            "org.springframework.boot.WebApplicationType#\$values():org.springframework.boot.WebApplicationType[]",
            "org.springframework.util.ClassUtils#getDefaultClassLoader():java.lang.ClassLoader",
            "java.lang.System#getSecurityManager():java.lang.SecurityManager",
            "java.lang.System#allowSecurityManager():boolean",
            "java.security.AllPermission#<init>():void",
            "org.slf4j.LoggerFactory#getILoggerFactory():org.slf4j.ILoggerFactory",
            "org.slf4j.LoggerFactory#getProvider():org.slf4j.spi.SLF4JServiceProvider",
            "java.util.concurrent.ConcurrentHashMap#<init>():void",
            "java.util.concurrent.LinkedBlockingQueue#<init>():void",
            "java.util.concurrent.LinkedBlockingQueue#<init>(int):void",
            "org.apache.commons.logging.LogAdapter\$Slf4jLocationAwareLog#<init>(org.slf4j.spi.LocationAwareLogger):void",
            "org.apache.commons.logging.LogAdapter\$Slf4jLog#<init>(org.slf4j.Logger):void",
            "ch.qos.logback.classic.Logger#getName():java.lang.String",
            "org.springframework.util.ConcurrentReferenceHashMap#<init>():void",
            "java.lang.Runtime#getRuntime():java.lang.Runtime",
            "java.lang.Runtime#availableProcessors():int",
            "java.lang.StringLatin1#canEncode(int):boolean",
            "java.util.concurrent.CopyOnWriteArrayList#size():int",
            "java.util.concurrent.CopyOnWriteArrayList#getArray():java.lang.Object[]",
            "java.util.concurrent.CopyOnWriteArrayList#get(int):java.lang.Object",
            "java.util.concurrent.CopyOnWriteArrayList#elementAt(java.lang.Object[],int):java.lang.Object",
            "java.util.IdentityHashMap#<init>():void",
            "java.util.Collections#newSetFromMap(java.util.Map):java.util.Set",
            "org.springframework.boot.SpringApplication\$Startup#create():org.springframework.boot.SpringApplication\$Startup",
            "org.springframework.boot.DefaultApplicationArguments#<init>(java.lang.String[]):void",
            "org.springframework.boot.DefaultApplicationArguments\$Source#<init>(java.lang.String[]):void",
            "java.util.Arrays#asList(java.lang.Object[]):java.util.List",
            "java.util.LinkedHashSet#<init>(java.util.Collection):void",
            "java.util.HashSet#<init>(int,float,boolean):void",
            "org.springframework.boot.WebApplicationType#<init>(java.lang.String,int):void",
            "org.springframework.core.io.support.SpringFactoriesLoader\$ArgumentResolver#of(java.lang.Class,java.lang.Object):org.springframework.core.io.support.SpringFactoriesLoader\$ArgumentResolver",
            "java.lang.Class#getComponentType():java.lang.Class",
            "java.util.ArrayList#<init>(java.util.Collection):void",
            "java.util.ArrayList#toArray():java.lang.Object[]",
            "java.util.Arrays#copyOf(java.lang.Object[],int):java.lang.Object[]",
            "java.lang.Object#getClass():java.lang.Class",
            "java.util.Arrays#copyOf(java.lang.Object[],int,java.lang.Class):java.lang.Object[]",
            "java.util.LinkedHashMap\$LinkedValues#toArray():java.lang.Object[]",
            "org.springframework.boot.WebApplicationType#deduceFromClasspath():org.springframework.boot.WebApplicationType",
            "org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder#<init>(org.springframework.web.context.WebApplicationContext):void",
            "org.springframework.boot.SpringApplicationShutdownHook#<init>():void",
            "java.util.HashMap#keySet():java.util.Set",
            "java.util.TreeMap#keySet():java.util.Set",
            "java.util.LinkedHashMap#get(java.lang.Object):java.lang.Object",
            "java.util.HashMap#get(java.lang.Object):java.lang.Object",
            "java.util.TreeMap#get(java.lang.Object):java.lang.Object",
            "java.util.Arrays\$ArrayList#get(int):java.lang.Object",
            "java.lang.Integer#intValue():int",
            "java.util.HashMap#<init>():void",
            "java.util.HashMap\$KeySet#iterator():java.util.Iterator",
            "java.util.TreeMap\$KeySet#iterator():java.util.Iterator",
            "java.util.HashMap\$HashIterator#hasNext():boolean",
            "java.util.TreeMap\$PrivateEntryIterator#hasNext():boolean",
            "java.util.LinkedHashMap#<init>(int):void",
            "java.util.concurrent.ConcurrentHashMap#<init>(int):void",
            "java.lang.String\$CaseInsensitiveComparator#<init>():void",
            "org.springframework.validation.DefaultBindingErrorProcessor#<init>():void",
            "org.springframework.web.bind.support.BindParamNameResolver#<init>():void",
            "java.util.ArrayDeque#<init>():void",
            "org.springframework.validation.DefaultMessageCodesResolver#<init>():void",
            "org.springframework.validation.DefaultMessageCodesResolver\$Format\$1#<init>(java.lang.String,int):void",
            "org.springframework.validation.DefaultMessageCodesResolver\$Format\$2#<init>(java.lang.String,int):void",
            "java.util.HashSet#<init>():void",
            "org.springframework.ui.ModelMap#<init>():void",
            "org.springframework.mock.web.HeaderValueHolder#<init>():void",
            "java.util.LinkedList#<init>():void",
            "org.springframework.context.support.MessageSourceAccessor#<init>(org.springframework.context.MessageSource):void",
            "java.util.stream.ReferencePipeline\$3#<init>(java.util.stream.ReferencePipeline,java.util.stream.AbstractPipeline,java.util.stream.StreamShape,int,java.util.function.Function):void",
            "java.util.HashMap#<init>(int,float):void",
            "org.springframework.web.servlet.view.AbstractCachingViewResolver\$1#<init>():void",
            "org.springframework.context.support.MessageSourceAccessor#<init>(org.springframework.context.MessageSource):void",
            "org.springframework.web.servlet.handler.AbstractHandlerExceptionResolver#shouldApplyTo(jakarta.servlet.http.HttpServletRequest,java.lang.Object):boolean",
            "org.springframework.web.servlet.handler.AbstractHandlerExceptionResolver#hasHandlerMappings():boolean",
            "java.lang.String#equals(java.lang.Object):boolean",
            "java.nio.charset.CodingErrorAction#<init>(java.lang.String):void",
            "java.io.OutputStreamWriter#<init>(java.io.OutputStream,java.lang.String):void",
            "org.thymeleaf.spring6.expression.ThymeleafEvaluationContext\$ThymeleafEvaluationContextACLMethodResolver#<init>():void",
            "org.springframework.expression.spel.support.StandardTypeLocator#<init>():void",
            "org.thymeleaf.spring6.expression.ThymeleafEvaluationContext\$ThymeleafEvaluationContextACLTypeLocator#<init>():void",
            "org.springframework.context.expression.MapAccessor#<init>():void",
            "org.thymeleaf.spring6.expression.ThymeleafEvaluationContext\$ThymeleafEvaluationContextACLPropertyAccessor#<init>():void",
            "org.thymeleaf.spring6.expression.SPELContextPropertyAccessor#<init>():void",
            "org.springframework.expression.spel.support.StandardTypeConverter#<init>(org.springframework.core.convert.ConversionService):void",
            "org.springframework.context.expression.BeanFactoryResolver#<init>(org.springframework.beans.factory.BeanFactory):void",
            "org.springframework.expression.TypedValue#<init>(java.lang.Object):void",
            "org.springframework.expression.spel.support.StandardOperatorOverloader#<init>():void",
            "org.springframework.expression.spel.support.StandardTypeComparator#<init>():void",
            "org.thymeleaf.spring6.expression.ThymeleafEvaluationContext#<init>(org.springframework.context.ApplicationContext,org.springframework.core.convert.ConversionService):void",
            "org.thymeleaf.spring6.context.webmvc.SpringWebMvcThymeleafRequestDataValueProcessor#<init>(org.springframework.web.servlet.support.RequestDataValueProcessor,jakarta.servlet.http.HttpServletRequest):void",
            "org.springframework.web.util.UrlPathHelper#<init>():void",
            "org.springframework.web.servlet.support.RequestContextUtils#findWebApplicationContext(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.ServletContext):org.springframework.web.context.WebApplicationContext",
            "org.springframework.web.servlet.support.RequestContextUtils#getLocaleResolver(jakarta.servlet.http.HttpServletRequest):org.springframework.web.servlet.LocaleResolver",
            "java.util.HashMap#<init>(int):void",
            "org.thymeleaf.web.servlet.JakartaServletWebSession#<init>(jakarta.servlet.http.HttpServletRequest):void",
            "org.thymeleaf.web.servlet.JakartaServletWebApplication#servletContextMatches(jakarta.servlet.http.HttpServletRequest):boolean",
            "org.thymeleaf.web.servlet.JakartaServletWebRequest#<init>(jakarta.servlet.http.HttpServletRequest):void",
            "org.thymeleaf.web.servlet.JakartaServletWebRequest#getContextPath():java.lang.String",
            "java.lang.Character#isWhitespace(char):boolean",
            "java.lang.String#indexOf(int):int",
            "java.net.URI#match(char,long,long):boolean",
            "java.lang.String#contains(java.lang.CharSequence):boolean",
            "java.util.Set#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object):java.util.Set",
            "java.util.ImmutableCollections\$AbstractImmutableList#iterator():java.util.Iterator",
            "java.util.ImmutableCollections\$ListItr#hasNext():boolean",
            "java.lang.Class#getPrimitiveClass(java.lang.String):java.lang.Class",
            "java.util.Collections#synchronizedList(java.util.List):java.util.List",
            "org.springframework.mock.web.MockHttpServletResponse#<init>():void",
            "java.lang.String#formatted(java.lang.Object[]):java.lang.String",
            "org.springframework.test.context.aot.DefaultAotTestAttributes#getString(java.lang.String):java.lang.String",
            "java.util.concurrent.ConcurrentHashMap#get(java.lang.Object):java.lang.Object",
            "org.springframework.boot.test.context.AnnotatedClassFinder#<init>(java.lang.Class):void",
            "org.springframework.util.StringUtils#toStringArray(java.util.Collection):java.lang.String[]",
            "java.lang.CharacterDataLatin1#<init>():void",
            "java.lang.CharacterDataLatin1#toUpperCase(int):int",
            "java.lang.CharacterDataLatin1#getProperties(int):int",
            "java.lang.Character#toLowerCase(int):int",
            "java.lang.CharacterData#of(int):java.lang.CharacterData",
            "java.lang.CharacterDataLatin1#toLowerCase(int):int",
            "java.lang.String#getBytes():byte[]",
            "org.springframework.http.HttpMethod#name():java.lang.String",
            "org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder#initUri(java.lang.String,java.lang.Object[]):java.net.URI",
            "org.springframework.test.web.servlet.request.MockMvcRequestBuilders#get(java.lang.String,java.lang.Object[]):org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder",
            "org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder#buildRequest(jakarta.servlet.ServletContext):org.springframework.mock.web.MockHttpServletRequest",
            "org.springframework.mock.web.MockHttpServletRequest#getAsyncContext():jakarta.servlet.AsyncContext",
            "org.springframework.test.web.servlet.DefaultMvcResult#<init>(org.springframework.mock.web.MockHttpServletRequest,org.springframework.mock.web.MockHttpServletResponse):void",
            "org.springframework.web.context.request.RequestContextHolder#getRequestAttributes():org.springframework.web.context.request.RequestAttributes",
            "org.springframework.web.context.request.ServletRequestAttributes#<init>(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.mock.web.MockFilterChain#<init>(jakarta.servlet.Servlet,jakarta.servlet.Filter[]):void",
            "org.springframework.util.Assert#notNull(java.lang.Object,java.lang.String):void",
            "org.springframework.util.Assert#state(boolean,java.lang.String):void",
            "org.springframework.web.filter.OncePerRequestFilter#getAlreadyFilteredAttributeName():java.lang.String",
            "org.springframework.web.filter.OncePerRequestFilter#skipDispatch(jakarta.servlet.http.HttpServletRequest):boolean",
            "org.springframework.web.filter.OncePerRequestFilter#shouldNotFilter(jakarta.servlet.http.HttpServletRequest):boolean",
            "org.springframework.web.filter.CharacterEncodingFilter#getEncoding():java.lang.String",
            "org.springframework.web.filter.CharacterEncodingFilter#isForceRequestEncoding():boolean",
            "org.springframework.web.filter.CharacterEncodingFilter#isForceResponseEncoding():boolean",
            "org.springframework.mock.web.MockHttpServletRequest#getMethod():java.lang.String",
            "java.util.ImmutableCollections\$SetN#contains(java.lang.Object):boolean",
            "jakarta.servlet.http.HttpServlet#getLastModified(jakarta.servlet.http.HttpServletRequest):long",
            "org.springframework.context.i18n.LocaleContextHolder#getLocaleContext():org.springframework.context.i18n.LocaleContext",
            "org.springframework.web.servlet.DispatcherServlet#buildLocaleContext(jakarta.servlet.http.HttpServletRequest):org.springframework.context.i18n.LocaleContext",
            "org.springframework.web.servlet.FrameworkServlet#buildRequestAttributes(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,org.springframework.web.context.request.RequestAttributes):org.springframework.web.context.request.ServletRequestAttributes",
            "org.springframework.web.servlet.FrameworkServlet\$RequestBindingInterceptor#<init>(org.springframework.web.servlet.FrameworkServlet):void",
            "org.springframework.web.servlet.DispatcherServlet#logRequest(jakarta.servlet.http.HttpServletRequest):void",
            "org.springframework.web.util.WebUtils#isIncludeRequest(jakarta.servlet.ServletRequest):boolean",
            "org.springframework.web.servlet.FrameworkServlet#getWebApplicationContext():org.springframework.web.context.WebApplicationContext",
            "org.springframework.web.servlet.DispatcherServlet#getThemeSource():org.springframework.ui.context.ThemeSource",
            "org.springframework.web.servlet.FlashMap#<init>():void",
            "org.springframework.web.servlet.DispatcherServlet#checkMultipart(jakarta.servlet.http.HttpServletRequest):jakarta.servlet.http.HttpServletRequest",
            "org.springframework.web.servlet.HandlerExecutionChain#getHandler():java.lang.Object",
            "org.springframework.web.servlet.DispatcherServlet#getHandlerAdapter(java.lang.Object):org.springframework.web.servlet.HandlerAdapter",
            "org.springframework.http.HttpMethod#matches(java.lang.String):boolean",
            "org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter#getLastModified(jakarta.servlet.http.HttpServletRequest,java.lang.Object):long",
            "org.springframework.web.context.request.ServletWebRequest#<init>(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.support.WebContentGenerator#checkRequest(jakarta.servlet.http.HttpServletRequest):void",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter#createInvocableHandlerMethod(org.springframework.web.method.HandlerMethod):org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod",
            "org.springframework.web.method.support.ModelAndViewContainer#<init>():void",
            "org.springframework.web.servlet.support.RequestContextUtils#getInputFlashMap(jakarta.servlet.http.HttpServletRequest):java.util.Map",
            "org.springframework.web.method.annotation.SessionAttributesHandler#retrieveAttributes(org.springframework.web.context.request.WebRequest):java.util.Map",
            "org.springframework.web.method.annotation.ModelFactory\$ModelMethod#getHandlerMethod():org.springframework.web.method.support.InvocableHandlerMethod",
            "org.springframework.core.annotation.AnnotatedMethod#getMethodAnnotation(java.lang.Class):java.lang.annotation.Annotation",
            "org.springframework.web.method.support.InvocableHandlerMethod#getValidationGroups():java.lang.Class[]",
            "org.springframework.web.method.HandlerMethod#shouldValidateArguments():boolean",
            "org.springframework.core.annotation.AnnotatedMethod#getBridgedMethod():java.lang.reflect.Method",
            "org.springframework.web.method.HandlerMethod#getBean():java.lang.Object",
            "org.springframework.context.i18n.SimpleLocaleContext#<init>(java.util.Locale):void",
            "org.springframework.web.filter.FormContentFilter#shouldParse(jakarta.servlet.http.HttpServletRequest):boolean",
            "org.springframework.web.context.request.async.WebAsyncManager#<init>():void",
            "java.util.LinkedHashMap#<init>():void",
            "org.springframework.mock.web.MockHttpServletMapping#<init>(java.lang.String,java.lang.String,java.lang.String,jakarta.servlet.http.MappingMatch):void",
            "org.springframework.util.ObjectUtils#nullSafeEquals(java.lang.Object,java.lang.Object):boolean",
            "org.springframework.http.server.DefaultRequestPath#<init>(java.lang.String,java.lang.String):void",
            "org.springframework.http.server.RequestPath#parse(java.lang.String,java.lang.String):org.springframework.http.server.RequestPath",
            "org.springframework.http.server.PathContainer#parsePath(java.lang.String):org.springframework.http.server.PathContainer",
            "org.springframework.http.server.DefaultPathContainer#createFromUrlPath(java.lang.String,org.springframework.http.server.PathContainer\$Options):org.springframework.http.server.PathContainer",
            "java.lang.Character#valueOf(char):java.lang.Character",
            "java.lang.Character#<init>(char):void",
            "org.springframework.http.server.PathContainer\$Options#separator():char",
            "org.springframework.web.util.UrlPathHelper#removeSemicolonContent(java.lang.String):java.lang.String",
            // TODO: be careful: all methods below are mutating, but maybe it's insufficient #CM
            "java.lang.StringBuilder#append(java.lang.Object):java.lang.StringBuilder",
            "org.springframework.web.method.support.HandlerMethodArgumentResolverComposite#supportsParameter(org.springframework.core.MethodParameter):boolean",
            "org.springframework.web.method.support.HandlerMethodArgumentResolverComposite#getArgumentResolver(org.springframework.core.MethodParameter):org.springframework.web.method.support.HandlerMethodArgumentResolver",
            "org.springframework.core.MethodParameter#initParameterNameDiscovery(org.springframework.core.ParameterNameDiscoverer):void",
            "org.springframework.web.servlet.mvc.method.annotation.ServletModelAttributeMethodProcessor#createAttribute(java.lang.String,org.springframework.core.MethodParameter,org.springframework.web.bind.support.WebDataBinderFactory,org.springframework.web.context.request.NativeWebRequest):java.lang.Object",
            "org.springframework.core.ResolvableType#forMethodParameter(org.springframework.core.MethodParameter):org.springframework.core.ResolvableType",
            "org.springframework.web.bind.support.DefaultDataBinderFactory#createBinder(org.springframework.web.context.request.NativeWebRequest,java.lang.Object,java.lang.String,org.springframework.core.ResolvableType):org.springframework.web.bind.WebDataBinder",
            "org.springframework.web.servlet.mvc.method.annotation.ServletModelAttributeMethodProcessor#constructAttribute(org.springframework.web.bind.WebDataBinder,org.springframework.web.context.request.NativeWebRequest):void",
            "org.springframework.web.servlet.mvc.method.annotation.ServletModelAttributeMethodProcessor#constructAttribute(org.springframework.web.bind.WebDataBinder,org.springframework.web.context.request.NativeWebRequest):void",
            "org.springframework.validation.DataBinder#getBindingResult():org.springframework.validation.BindingResult",
            "org.springframework.web.bind.ServletRequestParameterPropertyValues#<init>(jakarta.servlet.ServletRequest):void",
            "org.springframework.validation.DataBinder#getInternalBindingResult():org.springframework.validation.AbstractPropertyBindingResult",
            "java.lang.IllegalArgumentException#<init>(java.lang.String):void",
            "java.lang.IllegalStateException#<init>(java.lang.String,java.lang.Throwable):void",
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver#getExceptionHandlerMethod(org.springframework.web.method.HandlerMethod,java.lang.Exception):org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod",
            "org.springframework.core.annotation.AnnotatedElementUtils#findMergedAnnotation(java.lang.reflect.AnnotatedElement,java.lang.Class):java.lang.annotation.Annotation",
            "org.springframework.web.context.support.ServletRequestHandledEvent#<init>(java.lang.Object,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,long,java.lang.Throwable,int):void",
            "org.springframework.context.event.AbstractApplicationEventMulticaster#getApplicationListeners(org.springframework.context.ApplicationEvent,org.springframework.core.ResolvableType):java.util.Collection",
            "jakarta.servlet.ServletException#<init>(java.lang.String,java.lang.Throwable):void",
            "java.lang.RuntimeException#<init>(java.lang.Throwable):void",
            "java.lang.StringBuilder#<init>():void",
            "org.springframework.core.annotation.AnnotationsScanner#getDeclaredAnnotations(java.lang.reflect.AnnotatedElement,boolean):java.lang.annotation.Annotation[]",
            "java.lang.Integer#valueOf(int):java.lang.Integer",
            "org.springframework.beans.factory.support.AbstractBeanFactory#hasInstantiationAwareBeanPostProcessors():boolean",
            "org.springframework.beans.factory.support.AbstractBeanFactory#getBeanPostProcessorCache():org.springframework.beans.factory.support.AbstractBeanFactory\$BeanPostProcessorCache",
            "org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator#findCandidateAdvisors():java.util.List",
            "org.springframework.aop.framework.AbstractAdvisingBeanPostProcessor#isEligible(java.lang.Class):boolean",
            "org.thymeleaf.TemplateEngine#getConfiguration():org.thymeleaf.IEngineConfiguration",
            "java.util.Locale#createConstant(byte):java.util.Locale",
            "java.util.Locale#initDefault():java.util.Locale",
            "java.lang.System#registerNatives():void",
            "org.springframework.test.web.servlet.TestDispatcherServlet#getHandler(jakarta.servlet.http.HttpServletRequest):org.springframework.web.servlet.HandlerExecutionChain",
            // TODO: not sure, that this can be invoked! #CM
            "org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory#autowireBeanProperties(java.lang.Object,int,boolean):void",
        )

        private val concretizeInvocations = setOf(
            "org.springframework.test.web.servlet.TestDispatcherServlet#render(org.springframework.web.servlet.ModelAndView,jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
        )

        //endregion

        //region Invariants check

        init {
            check(concreteMutatingInvocations.intersect(concreteNonMutatingInvocations).isEmpty())
            check(concreteMutatingInvocations.intersect(forbiddenInvocations).isEmpty())
            check(concreteNonMutatingInvocations.intersect(forbiddenInvocations).isEmpty())
        }

        //endregion
    }
}

//endregion
