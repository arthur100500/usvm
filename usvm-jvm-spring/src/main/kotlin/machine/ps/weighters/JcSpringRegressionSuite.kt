package machine.ps.weighters

import machine.state.JcSpringState
import org.usvm.machine.state.JcState
import org.usvm.ps.StateWeighter

class JcSpringRegressionSuite: StateWeighter<JcState, Int> {

    override fun weight(state: JcState): Int {
        state as JcSpringState
        // TODO: implement
        return 0
    }
}
