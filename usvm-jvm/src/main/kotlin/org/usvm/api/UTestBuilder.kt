package org.usvm.api

import org.jacodb.api.jvm.JcClassType
import org.jacodb.api.jvm.JcType
import org.jacodb.api.jvm.JcTypedMethod
import org.usvm.api.util.JcTestStateResolver
import org.usvm.machine.JcContext
import org.usvm.machine.state.JcState
import org.usvm.memory.ULValue
import org.usvm.memory.UReadOnlyMemory
import org.usvm.model.UModelBase
import org.usvm.test.api.JcTestExecutorDecoderApi
import org.usvm.test.api.UTest
import org.usvm.test.api.UTestExpression
import org.usvm.test.api.UTestStaticMethodCall
import org.usvm.test.api.UTestMethodCall
import org.usvm.test.api.UTestAllocateMemoryCall

fun createUTest(
    method: JcTypedMethod,
    state: JcState
): UTest {
    val model = state.models.first()
    val ctx = state.ctx
    val memoryScope = MemoryScope(ctx, model, state.memory, method)

    return memoryScope.createUTest()
}

/**
 * An actual class for resolving objects from [UExpr]s.
 *
 * @param model a model to which compose expressions.
 * @param finalStateMemory a read-only memory to read [ULValue]s from.
 */
private class MemoryScope(
    ctx: JcContext,
    model: UModelBase<JcType>,
    finalStateMemory: UReadOnlyMemory<JcType>,
    method: JcTypedMethod,
) : JcTestStateResolver<UTestExpression>(ctx, model, finalStateMemory, method) {

    override val decoderApi = JcTestExecutorDecoderApi(ctx.cp)

    fun createUTest(): UTest {
        return withMode(ResolveMode.CURRENT) {
            val thisInstance = resolveThisInstance()
            val parameters = resolveParameters()

            resolveStatics()

            val initStmts = this@MemoryScope.decoderApi.initializerInstructions()

            val callExpr = if (method.isStatic) {
                UTestStaticMethodCall(method.method, parameters)
            } else {
                UTestMethodCall(thisInstance, method.method, parameters)
            }
            UTest(initStmts, callExpr)
        }
    }

    override fun allocateClassInstance(type: JcClassType): UTestExpression =
        UTestAllocateMemoryCall(type.jcClass)

    // todo: looks incorrect
    override fun allocateString(value: UTestExpression): UTestExpression = value
}
