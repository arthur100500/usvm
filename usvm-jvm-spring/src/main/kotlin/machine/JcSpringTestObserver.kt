package machine

import machine.state.JcSpringState
import org.jacodb.api.jvm.JcMethod
import org.usvm.statistics.UMachineObserver
import org.usvm.test.api.UTest
import testGeneration.JcSpringTestExprResolver
import testGeneration.canGenerateTest
import testGeneration.generateTest
import testGeneration.getHandlerMethod


interface TestReproducer {
    fun reproduce(test: UTest): Boolean
    fun kill()
}

interface TestRenderer {
    fun render(test: UTest, method: JcMethod, isExceptional: Boolean)
}

class JcSpringTestObserver(
    private val testReproducer: TestReproducer,
    private val testRenderer: TestRenderer
) : UMachineObserver<JcSpringState> {

    override fun onStateTerminated(state: JcSpringState, stateReachable: Boolean) {
        state.callStack.push(state.entrypoint, state.entrypoint.instList[0])
        if (!stateReachable || !state.hasEnoughInfoForTest()) return
        // TODO: Remove it and move generation inside
        val debugExprResolver = JcSpringTestExprResolver(state)
        val debugRenderedValues = state.pinnedValues.getMap().map { it.key to debugExprResolver.resolvePinnedValue(it.value) }
        if (!state.canGenerateTest()) return
        val test = state.generateTest()
        try {
            testRenderer.render(test, state.getHandlerMethod(), state.isExceptional)
            val success = testReproducer.reproduce(test)
            println("Test success: $success")
        } catch (e: Throwable) {
            println("generation failed with $e")
        }
    }

    override fun onMachineStopped() {
       testReproducer.kill()
    }
}
