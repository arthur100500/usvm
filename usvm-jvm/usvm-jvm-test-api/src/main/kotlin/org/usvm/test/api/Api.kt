package org.usvm.test.api

import org.jacodb.api.jvm.*
import org.jacodb.api.jvm.ext.*

/**
 * Api for UTestExpression
 * Used for specifying scenario of target method execution
 */

sealed interface UTestInst

sealed interface UTestExpression: UTestInst {
    val type: JcType?
}

sealed class UTestMock(
    override val type: JcType,
    open val fields: Map<JcField, UTestExpression>,
    open val methods: Map<JcMethod, List<UTestExpression>>
): UTestExpression
/**
 * Mock for specific object
 */
class UTestMockObject(
    override val type: JcType,
    override val fields: Map<JcField, UTestExpression>,
    override val methods: Map<JcMethod, List<UTestExpression>>
) : UTestMock(type, fields, methods)

/**
 * Mock for all objects of type
 */
class UTestGlobalMock(
    override val type: JcType,
    override val fields: Map<JcField, UTestExpression>,
    override val methods: Map<JcMethod, List<UTestExpression>>
) : UTestMock(type, fields, methods)


sealed interface UTestCall : UTestExpression {
    val instance: UTestExpression?
    val method: JcMethod?
    val args: List<UTestExpression>
}

class UTestMethodCall(
    override val instance: UTestExpression,
    override val method: JcMethod,
    override val args: List<UTestExpression>
) : UTestCall {
    override val type: JcType? = method.enclosingClass.classpath.findTypeOrNull(method.returnType)
    override fun toString() = "$instance.${method.name}(${args.joinToString { it.toString() }})"
}

class UTestStaticMethodCall(
    override val method: JcMethod,
    override val args: List<UTestExpression>
) : UTestCall {
    override val instance: UTestExpression? = null
    override val type: JcType? = method.enclosingClass.classpath.findTypeOrNull(method.returnType)
    override fun toString() = "${method.enclosingClass.simpleName}.${method.name}(${args.joinToString { it.toString() }})"
}

class UTestConstructorCall(
    override val method: JcMethod,
    override val args: List<UTestExpression>
) : UTestCall {
    override val instance: UTestExpression? = null
    override val type: JcType = method.enclosingClass.toType()
    override fun toString() = "new ${method.enclosingClass.simpleName}(${args.joinToString { it.toString() }})"
}

class UTestAllocateMemoryCall(
    val clazz: JcClassOrInterface
) : UTestCall {
    override val instance: UTestExpression? = null
    override val method: JcMethod? = null
    override val args: List<UTestExpression> = listOf()
    override val type: JcType = clazz.toType()
    override fun toString() = "ALLOC(${clazz.simpleName})"
}

sealed interface UTestStatement : UTestInst

class UTestSetFieldStatement(
    val instance: UTestExpression,
    val field: JcField,
    val value: UTestExpression
) : UTestStatement {
    override fun toString() = "$instance.${field.name} = $value"
}

class UTestSetStaticFieldStatement(
    val field: JcField,
    val value: UTestExpression
) : UTestStatement {
    override fun toString() = "${field.enclosingClass.simpleName}.${field.name} = $value"
}


class UTestBinaryConditionExpression(
    val conditionType: ConditionType,
    val lhv: UTestExpression,
    val rhv: UTestExpression,
    val trueBranch: UTestExpression,
    val elseBranch: UTestExpression
) : UTestExpression {
    //TODO!! What if trueBranch and elseBranch have different types of the last instruction? Shouldn't we find their LCA?

    init {
        check(trueBranch.type == elseBranch.type){ "True and else branches should be equal" }
    }

    //Probably add functionality in jacodb?
    override val type: JcType? = trueBranch.type
}

class UTestBinaryConditionStatement(
    val conditionType: ConditionType,
    val lhv: UTestExpression,
    val rhv: UTestExpression,
    val trueBranch: List<UTestStatement>,
    val elseBranch: List<UTestStatement>
) : UTestStatement

class UTestArithmeticExpression(
    val operationType: ArithmeticOperationType,
    val lhv: UTestExpression,
    val rhv: UTestExpression,
    override val type: JcType
) : UTestExpression

class UTestGetStaticFieldExpression(
    val field: JcField
) : UTestExpression {
    override val type: JcType? = field.enclosingClass.classpath.findTypeOrNull(field.type)
}

sealed class UTestConstExpression<T> : UTestExpression {
    abstract val value: T
    override fun toString() = "$value"
}

class UTestBooleanExpression(
    override val value: Boolean,
    override val type: JcType
) : UTestConstExpression<Boolean>()

class UTestByteExpression(
    override val value: Byte,
    override val type: JcType
) : UTestConstExpression<Byte>()

class UTestShortExpression(
    override val value: Short,
    override val type: JcType
) : UTestConstExpression<Short>()

class UTestIntExpression(
    override val value: Int,
    override val type: JcType
) : UTestConstExpression<Int>()

class UTestLongExpression(
    override val value: Long,
    override val type: JcType
) : UTestConstExpression<Long>()

class UTestFloatExpression(
    override val value: Float,
    override val type: JcType
) : UTestConstExpression<Float>()

class UTestDoubleExpression(
    override val value: Double,
    override val type: JcType
) : UTestConstExpression<Double>()

class UTestCharExpression(
    override val value: Char,
    override val type: JcType
) : UTestConstExpression<Char>()

class UTestStringExpression(
    override val value: String,
    override val type: JcType
) : UTestConstExpression<String>() {
    override fun toString() ="\"$value\""
}

class UTestNullExpression(
    override val type: JcType
) : UTestConstExpression<Any?>() {
    override val value = null
}

class UTestGetFieldExpression(
    val instance: UTestExpression,
    val field: JcField
) : UTestExpression {
    override val type: JcType? = field.enclosingClass.classpath.findTypeOrNull(field.type)
    override fun toString() = "$instance.${field.name}"
}

class UTestArrayLengthExpression(
    val arrayInstance: UTestExpression
) : UTestExpression {
    override val type: JcType? = arrayInstance.type?.classpath?.int
    override fun toString() = "$arrayInstance.length"
}

class UTestArrayGetExpression(
    val arrayInstance: UTestExpression,
    val index: UTestExpression
) : UTestExpression {
    override val type: JcType? = (arrayInstance.type as? JcArrayType)?.elementType
    override fun toString() = "$arrayInstance[$index]"
}

class UTestArraySetStatement(
    val arrayInstance: UTestExpression,
    val index: UTestExpression,
    val setValueExpression: UTestExpression
) : UTestStatement {
    override fun toString() = "$arrayInstance[$index] = $setValueExpression"
}

class UTestCreateArrayExpression(
    val elementType: JcType,
    val size: UTestExpression
) : UTestExpression {
    override val type: JcType = elementType.classpath.arrayTypeOf(elementType)
    override fun toString() = "new ${elementType.typeName}[$size]"
}

class UTestCastExpression(
    val expr: UTestExpression,
    override val type: JcType
) : UTestExpression

class UTestClassExpression(
    override val type: JcType
): UTestExpression {
    override fun toString() = "${type.typeName}.class"
}


enum class ConditionType {
    EQ, NEQ, GEQ, GT
}

enum class ArithmeticOperationType {

    //Arithmetic
    PLUS, SUB, MUL, DIV, REM,

    //Relational
    EQ, NEQ, GT, GEQ, LT, LEQ,

    //Bitwise
    OR, AND, XOR
}
