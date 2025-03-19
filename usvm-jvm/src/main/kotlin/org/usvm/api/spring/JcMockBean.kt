package org.usvm.api.spring

import org.jacodb.api.jvm.JcMethod
import org.jacodb.api.jvm.ext.toType
import org.usvm.machine.state.pinnedValues.JcObjectPinnedKey
import org.usvm.machine.state.pinnedValues.JcSpringPinnedValueSource
import org.usvm.machine.state.pinnedValues.JcSpringPinnedValues
import org.usvm.test.api.UTest
import org.usvm.test.api.UTestInst
import org.usvm.test.api.UTestMockObject

class JcMockBean(private val origin: UTestMockObject) {
    fun getFields() = origin.fields
    fun getMethods() = origin.methods
    fun getType() = origin.type

    companion object {
        fun ofPinnedValues(
            pinnedValues: JcSpringPinnedValues,
            exprResolver: JcSpringTestExprResolver
        ): Pair<List<JcMockBean>, List<UTestInst>> {
            // TODO: Also fields #AA
            val mocks = pinnedValues.getValuesOfSource<JcObjectPinnedKey<JcMethod>>(JcSpringPinnedValueSource.MOCK_RESULT)
            val distinctMocks = mocks.entries.mapNotNull { it.key.getObj()?.enclosingClass }.distinct()
            val testMockObjects = distinctMocks.map { type ->
                val distinctMethods = mocks.entries
                    .filter { it.key.getObj()?.enclosingClass == type }
                    .groupBy({ it.key.getObj()!! }, { exprResolver.resolvePinnedValue(it.value) })
                UTestMockObject(
                    type.toType(),
                    mapOf(),
                    distinctMethods
                )
            }
            return testMockObjects.map { JcMockBean(it) } to exprResolver.getInstructions()
        }
    }
}