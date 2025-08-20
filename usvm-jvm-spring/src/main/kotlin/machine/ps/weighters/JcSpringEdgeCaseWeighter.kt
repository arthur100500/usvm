package machine.ps.weighters

import machine.state.JcSpringState
import org.usvm.machine.state.JcState
import org.usvm.ps.StateWeighter

class JcSpringEdgeCaseWeighter: StateWeighter<JcState, Int> {

    private companion object {
        private const val GOOD_WEIGHT = 10
        private const val BAD_WEIGHT = 0
    }

    override fun weight(state: JcState): Int {
        state as JcSpringState
        return if (state.isExceptional) GOOD_WEIGHT else BAD_WEIGHT
    }
}
