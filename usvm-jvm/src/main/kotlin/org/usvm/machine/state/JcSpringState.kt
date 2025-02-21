package org.usvm.machine.state

import org.jacodb.api.jvm.JcMethod
import org.jacodb.api.jvm.JcType
import org.jacodb.api.jvm.cfg.JcInst
import org.usvm.PathNode
import org.usvm.UCallStack
import org.usvm.UExpr
import org.usvm.USort
import org.usvm.api.JcSpringTest
import org.usvm.api.SpringReqSettings
import org.usvm.api.targets.JcTarget
import org.usvm.collections.immutable.internal.MutabilityOwnership
import org.usvm.constraints.UPathConstraints
import org.usvm.machine.JcContext
import org.usvm.machine.state.concreteMemory.JcConcreteMemory
import org.usvm.memory.UMemory
import org.usvm.model.UModelBase
import org.usvm.targets.UTargetsSet

class JcSpringState(
    ctx: JcContext,
    ownership: MutabilityOwnership,
    entrypoint: JcMethod,
    callStack: UCallStack<JcMethod, JcInst> = UCallStack(),
    pathConstraints: UPathConstraints<JcType> = UPathConstraints(ctx, ownership),
    memory: UMemory<JcType, JcMethod> = JcConcreteMemory(ctx, ownership, pathConstraints.typeConstraints),
    models: List<UModelBase<JcType>> = listOf(),
    pathNode: PathNode<JcInst> = PathNode.root(),
    forkPoints: PathNode<PathNode<JcInst>> = PathNode.root(),
    methodResult: JcMethodResult = JcMethodResult.NoCall,
    targets: UTargetsSet<JcTarget, JcInst> = UTargetsSet.empty(),
    var userDefinedValues: Map<String, Pair<UExpr<out USort>, JcType>> = emptyMap(),
    var reqSetup: Map<SpringReqSettings, UExpr<out USort>> = emptyMap(),
    var res: UExpr<out USort>? = null,
    var resultConclusion: JcSpringTest? = null,
) : JcState(
    ctx,
    ownership,
    entrypoint,
    callStack,
    pathConstraints,
    memory,
    models,
    pathNode,
    forkPoints,
    methodResult,
    targets
) {
    companion object {
        fun defaultFromJcState(state: JcState): JcSpringState = JcSpringState(
            state.ctx,
            state.ownership,
            state.entrypoint,
            state.callStack,
            state.pathConstraints,
            state.memory,
            state.models,
            state.pathNode,
            state.forkPoints,
            state.methodResult,
            state.targets,
        )
    }

    fun getUserDefinedValue(key: String): Pair<UExpr<out USort>, JcType>? {
        return userDefinedValues[key]
    }

    override fun clone(newConstraints: UPathConstraints<JcType>?): JcSpringState {
        val jcStateClone = super.clone(newConstraints)
        val new = defaultFromJcState(jcStateClone)
        new.userDefinedValues = userDefinedValues
        new.reqSetup = reqSetup
        new.res = res
        new.resultConclusion =
            resultConclusion?.let { error("State cannot be cloned if resultConclusion was generated from it") }
        return new
    }
}