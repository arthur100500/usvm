package machine.ps.weighters

import machine.state.JcConcreteState
import org.usvm.machine.state.JcState
import org.usvm.ps.StateWeighter

class JcConcreteBacktrackWeighter: StateWeighter<JcState, Int> {

    override fun weight(state: JcState): Int {
        state as JcConcreteState
        return - state.concreteMemory.resetWeight()
    }
}
