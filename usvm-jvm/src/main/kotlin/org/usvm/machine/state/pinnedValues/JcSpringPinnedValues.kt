package org.usvm.machine.state.pinnedValues

import com.jetbrains.rd.util.firstOrNull
import io.ksmt.utils.asExpr
import org.jacodb.api.jvm.JcType
import org.usvm.UExpr
import org.usvm.USort
import org.usvm.api.makeNullableSymbolicRef
import org.usvm.api.makeSymbolicRef
import org.usvm.machine.interpreter.JcStepScope

class JcSpringPinnedValues (
    private var pinnedValues: Map<JcPinnedKey, JcSpringPinnedValue> = emptyMap()
){
    fun getValue(key: JcPinnedKey): JcSpringPinnedValue? {
        return pinnedValues[key]
    }

    fun setValue(key: JcPinnedKey, value: JcSpringPinnedValue) {
        pinnedValues = pinnedValues.filter { it.key != key }
        pinnedValues += key to value
    }
    
    fun createIfAbsent(key: JcPinnedKey, type: JcType, scope: JcStepScope, sort: USort, nullable: Boolean = true): JcSpringPinnedValue? {
        val existingValue = getValue(key)
        if (existingValue != null)
            return existingValue

        val newValueExpr = 
            if (nullable) scope.makeNullableSymbolicRef(type)?.asExpr(sort)
            else scope.makeSymbolicRef(type)?.asExpr(sort)

        if (newValueExpr == null) {
            println("Error creating symbolic value!")
            return null
        }

        val newValue = JcSpringPinnedValue(newValueExpr, type)
        setValue(key, newValue)

        return newValue
    }

    fun getKeyOfExpr(value: UExpr<out USort>): JcPinnedKey? {
        val pair = pinnedValues.entries.firstOrNull { it.value.getExpr() == value }
        if (pair == null)
            return null
        return pair.key
    }

    // TODO: Find solution without unchecked cast #AA
    @Suppress("UNCHECKED_CAST")
    fun <K : JcPinnedKey> getValuesOfSource(source: JcSpringPinnedValueSource): Map<K, JcSpringPinnedValue> {
        return pinnedValues
            .filter { it.key.getSource() == source }
            .map { (k, v) -> k as K to v}
            .toMap()
    }
}