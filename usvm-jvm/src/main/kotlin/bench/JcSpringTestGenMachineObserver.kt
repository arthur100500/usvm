package bench

import org.usvm.api.JcSpringTest
import org.usvm.jvm.rendering.JcSpringTestKind
import org.usvm.jvm.rendering.JcSpringTestMeta
import org.usvm.jvm.rendering.JcSpringTestRenderManager
import org.usvm.jvm.rendering.UTestRenderWrapper
import org.usvm.machine.JcSpringMachine
import org.usvm.machine.state.JcSpringState
import org.usvm.statistics.UMachineObserver

class JcSpringTestGenMachineObserver(private val machine: JcSpringMachine) : UMachineObserver<JcSpringState> {
    override fun onStateTerminated(state: JcSpringState, stateReachable: Boolean) {
        state.callStack.push(state.entrypoint, state.entrypoint.instList[0])
        if (!stateReachable || state.reqSetup.size < 2) return
        try {
            val test = JcSpringTest.generateFromState(state)
            JcSpringTestRenderManager().render(
                state.entrypoint.enclosingClass.classpath,
                listOf(
                    UTestRenderWrapper(
                        test.generateTestDSL(),
                        JcSpringTestMeta(test.generatedTestClass, test.reqPath.path, JcSpringTestKind.WebMVC)
                    )
                )
            )
            machine.testPool.add(test)
        } catch (e: Throwable) {
            println("generation failed with $e")
        }
    }

    override fun onMachineStopped() {
        machine.testPool.removeIf(::testIsValidAndMayBeRendered)
    }

    // TODO: reproduction
    private fun testIsValidAndMayBeRendered(test: JcSpringTest): Boolean = true
}