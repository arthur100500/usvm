package org.usvm.machine.state.concreteMemory.concreteMemoryRegions

import org.jacodb.api.jvm.JcArrayType
import org.jacodb.api.jvm.JcType
import org.jacodb.api.jvm.ext.boolean
import org.jacodb.api.jvm.ext.byte
import org.jacodb.api.jvm.ext.char
import org.jacodb.api.jvm.ext.double
import org.jacodb.api.jvm.ext.float
import org.jacodb.api.jvm.ext.int
import org.jacodb.api.jvm.ext.long
import org.jacodb.api.jvm.ext.objectType
import org.jacodb.api.jvm.ext.short
import org.usvm.UBoolExpr
import org.usvm.UConcreteHeapAddress
import org.usvm.UConcreteHeapRef
import org.usvm.UExpr
import org.usvm.UHeapRef
import org.usvm.USort
import org.usvm.collection.array.UArrayIndexLValue
import org.usvm.collection.array.UArrayRegion
import org.usvm.collection.array.UArrayRegionId
import org.usvm.isTrue
import org.usvm.machine.JcContext
import org.usvm.machine.USizeSort
import org.usvm.machine.state.concreteMemory.JcConcreteMemoryBindings
import org.usvm.machine.state.concreteMemory.Marshall
import org.usvm.memory.UMemoryRegion
import org.usvm.mkSizeExpr

internal class JcConcreteArrayRegion<Sort : USort>(
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
