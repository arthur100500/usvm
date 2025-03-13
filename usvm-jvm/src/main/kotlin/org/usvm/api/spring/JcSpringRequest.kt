package org.usvm.api.spring

import org.jacodb.api.jvm.JcType
import org.usvm.UExpr
import org.usvm.USort

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
    private val pinnedValues: Map<String, Pair<UExpr<out USort>, JcType>>,
    private val concretize: (value: UExpr<out USort>, type: JcType) -> Any?
) : JcSpringRequest {

    private fun collectAndConcretize(prefix: String): Map<String, Any?> {
        return pinnedValues.filter { it.key.startsWith(prefix) }
            .map { (key, value) ->
                val name = key.replaceFirst(prefix, "")
                name to concretize(value.first, value.second)
            }.toMap()
    }

    private fun sortRequestUriVariable(path: String, uriVariables: Map<String, Any?>): List<Any?> {
//      TODO: check it
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
        val headersRaw = collectAndConcretize("HEADER_")
        return headersRaw.mapNotNull { (key, value) -> handleStringMultiValue(value)?.let { JcSpringHttpHeader(key, it)} }
    }

    override fun getMethod(): JcSpringRequestMethod {
        val method = pinnedValues["CONFIG_METHOD"]?.let { concretize(it.first, it.second) }
        check(method != null && method is String)
        return JcSpringRequestMethod.valueOf(method.uppercase())
    }

    override fun getPath(): String {
        val path = pinnedValues["CONFIG_PATH"]?.let { concretize(it.first, it.second) }
        check(path != null && path is String)
        return path
    }

    override fun getContentAsString(): String {
        TODO("Not yet implemented")
    }

    override fun getParameters(): List<JcSpringHttpParameter> {
        val paramsRaw = collectAndConcretize("PARAM_")
        return paramsRaw.mapNotNull { (key, value) -> handleStringMultiValue(value)?.let { JcSpringHttpParameter(key, it)} }
    }

    override fun getUriVariables(): List<Any?> {
        val pathVariables = collectAndConcretize("PATH_")
        return sortRequestUriVariable(getPath(), pathVariables)
    }
}