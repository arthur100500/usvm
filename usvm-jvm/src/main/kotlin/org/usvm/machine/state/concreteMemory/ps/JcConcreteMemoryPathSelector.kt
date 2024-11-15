package org.usvm.machine.state.concreteMemory.ps

import org.usvm.UPathSelector
import org.usvm.machine.state.JcState
import org.usvm.machine.state.concreteMemory.JcConcreteMemory

class JcConcreteMemoryPathSelector(
    private val selector: UPathSelector<JcState>
) : UPathSelector<JcState> {

    private var fixedState: JcState? = null

    override fun isEmpty(): Boolean {
        return selector.isEmpty()
    }

    override fun peek(): JcState {
        if (fixedState != null)
            return fixedState as JcState
        val state = selector.peek()
        fixedState = state
        val memory = state.memory as JcConcreteMemory
        memory.reset()
        return state
    }

    override fun update(state: JcState) {
        selector.update(state)
    }

    override fun add(states: Collection<JcState>) {
        selector.add(states)
    }

    override fun remove(state: JcState) {
        check(fixedState == state)
        fixedState = null
        selector.remove(state)
        (state.memory as JcConcreteMemory).kill()
        // TODO: generate test? #CM
    }
}
