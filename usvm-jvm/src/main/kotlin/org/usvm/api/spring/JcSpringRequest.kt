package org.usvm.api.spring

import com.jetbrains.rd.util.firstOrNull
import org.jacodb.api.jvm.JcType
import org.usvm.UExpr
import org.usvm.USort
import org.usvm.machine.state.pinnedValues.JcSpringPinnedValue
import org.usvm.machine.state.pinnedValues.JcSpringPinnedValueKey
import org.usvm.machine.state.pinnedValues.JcSpringPinnedValueSource
import org.usvm.machine.state.pinnedValues.JcSpringPinnedValues

interface JcSpringRequest {
    fun getCookies(): List<JcSpringHttpCookie>
    fun getHeaders(): List<JcSpringHttpHeader>
    fun getMethod(): JcSpringRequestMethod
    fun getPath(): String
    fun getContentAsString(): String
    fun getParameters(): List<JcSpringHttpParameter>
    fun getUriVariables(): List<Any?>
}

class JcSpringRealRequest(private val request: Any) : JcSpringRequest {
    init {
        check(request.javaClass.name.endsWith("MockHttpServletRequest"))
    }

    override fun getCookies(): List<JcSpringHttpCookie> {
        TODO("Not yet implemented")
    }

    override fun getHeaders(): List<JcSpringHttpHeader> {
        TODO("Not yet implemented")
    }

    override fun getMethod(): JcSpringRequestMethod {
        TODO("Not yet implemented")
    }

    override fun getPath(): String {
        TODO("Not yet implemented")
    }

    override fun getContentAsString(): String {
        TODO("Not yet implemented")
    }

    override fun getParameters(): List<JcSpringHttpParameter> {
        TODO("Not yet implemented")
    }

    override fun getUriVariables(): List<Any?> {
        // MockHttpServletRequest contains built URL
        return listOf()
    }
}

class JcSpringPinnedValuesRequest(
    private val pinnedValues: JcSpringPinnedValues,
    private val concretize: (value: JcSpringPinnedValue) -> Any?
) : JcSpringRequest {

    private fun collectAndConcretize(source: JcSpringPinnedValueSource): Map<String, Any?> {
        return pinnedValues.getValuesOfSource(source)
            .map { (key, value) ->
                val name = key.getName()
                check(name != null) { "Only named pinned values here!" }
                name to concretize(value)
            }.toMap()
    }

    private fun sortRequestUriVariables(path: String, uriVariables: Map<String, Any?>): List<Any?> {
        // TODO: check it
        val uriVariableNames = Regex("\\{([^}]*)}").findAll(path)
            .map { it.groupValues[1] }
            .toList()

        return uriVariableNames.map {
            uriVariables.getValue(it)
        }.also { assert(it.size == uriVariables.size) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleStringMultiValue(possibleMultiValue: Any?): List<String>? {
        if (possibleMultiValue == null) return null
        // TODO: Check return types and adjust this accordingly #AA
        return listOf(possibleMultiValue.toString())
    }

    override fun getCookies(): List<JcSpringHttpCookie> {
        TODO("Not yet implemented")
    }

    override fun getHeaders(): List<JcSpringHttpHeader> {
        val headersRaw = collectAndConcretize(JcSpringPinnedValueSource.REQUEST_HEADER)
        return headersRaw.mapNotNull { (key, value) -> handleStringMultiValue(value)?.let { JcSpringHttpHeader(key, it)} }
    }

    override fun getMethod(): JcSpringRequestMethod {
        val method = pinnedValues.getValue(JcSpringPinnedValueKey.requestMethod())?.let { concretize(it) }
        check(method != null && method is String)
        return JcSpringRequestMethod.valueOf(method.uppercase())
    }

    override fun getPath(): String {
        val path = pinnedValues.getValue(JcSpringPinnedValueKey.requestPath())?.let { concretize(it) }
        check(path != null && path is String)
        return path
    }

    override fun getContentAsString(): String {
        TODO("Not yet implemented")
    }

    override fun getParameters(): List<JcSpringHttpParameter> {
        val paramsRaw = collectAndConcretize(JcSpringPinnedValueSource.REQUEST_PARAM)
        return paramsRaw.mapNotNull { (key, value) -> handleStringMultiValue(value)?.let { JcSpringHttpParameter(key, it)} }
    }

    override fun getUriVariables(): List<Any?> {
        val pathVariables = collectAndConcretize(JcSpringPinnedValueSource.REQUEST_PATH_VARIABLE)
        return sortRequestUriVariables(getPath(), pathVariables)
    }
}