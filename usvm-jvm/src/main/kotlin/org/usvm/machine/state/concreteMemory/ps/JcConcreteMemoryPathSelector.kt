package org.usvm.machine.state.concreteMemory.ps

import org.jacodb.api.jvm.cfg.JcExpr
import org.usvm.UConcreteHeapRef
import org.usvm.UHeapRef
import org.usvm.UPathSelector
import org.usvm.api.util.JcTestStateResolver
import org.usvm.logger
import org.usvm.machine.state.JcState
import org.usvm.machine.state.concreteMemory.JcConcreteMemory
import kotlin.reflect.jvm.internal.impl.load.kotlin.JvmType

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
        println("picked state: ${state.id}")
        memory.reset()
        return state
    }

    override fun update(state: JcState) {
        selector.update(state)
    }

    override fun add(states: Collection<JcState>) {
        selector.add(states)
    }

    private fun getConcreteValue(state: JcState, expr: UConcreteHeapRef) : Any? {
        if (expr.address == 0) return "failed to retrieve"
        return (state.memory as JcConcreteMemory).tryHeapRefToObject(expr)
    }

    private fun printSpringTestSummary(state: JcState) {

        val userDefinedValues = state.userDefinedValues
        userDefinedValues.forEach {
            logger.info(it.key + ": " + getConcreteValue(state, state.models[0].eval(it.value) as UConcreteHeapRef).toString())
        }
    }


    override fun remove(state: JcState) {
        // TODO: care about Engine.assume -- it's fork, but else state of assume is useless #CM
        check(fixedState == state)
        fixedState = null
        printSpringTestSummary(state)
        selector.remove(state)
        (state.memory as JcConcreteMemory).kill()
        println("removed state: ${state.id}")
        // TODO: generate test? #CM
    }
}
