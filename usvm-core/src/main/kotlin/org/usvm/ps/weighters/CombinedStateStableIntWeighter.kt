package org.usvm.ps.weighters

import org.usvm.ps.StateWeighter

class CombinedStateStableIntWeighter<in State> : CombinedStateWeighter<State, Int, Float> {

    constructor(
        weighters: List<StateWeighter<State, Int>>
    ) : super(weighters, Int::stableAdd)

    constructor(
        weighters: List<StateWeighter<State, Int>>,
        metaWeights: List<Float>
    ) : super(
        weighters,
        metaWeights,
        Int::stableAdd,
        Int::stableMul
    )
}
