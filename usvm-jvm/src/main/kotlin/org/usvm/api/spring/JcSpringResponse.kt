package org.usvm.api.spring

@Suppress("UNCHECKED_CAST")
class JcSpringResponse(private val response: Any) {
    private val responseClass = response.javaClass

    init {
        check(response.javaClass.name.endsWith("MockHttpServletResponse"))
    }

    private fun <T> getFromMethod(methodName: String, parameterTypes: Array<Class<*>> = arrayOf(), arguments: Array<Any> = arrayOf()): T {
        return responseClass.getMethod(methodName, *parameterTypes).invoke(response, *arguments) as T
    }

    private fun getHeader(name: String): Collection<String> {
        return getFromMethod("getHeaders", arrayOf(String::class.java), arrayOf(name))
    }

    fun getStatusCode(): Int = getFromMethod("getStatus")

    // TODO: Will not work on HttpServletResonse interface #AA
    fun getErrorMessage(): String = getFromMethod("getErrorMessage")

    // TODO: Will not work on HttpServletResonse interface #AA
    fun getContentLength(): Int = getFromMethod("getContentLength")

    // TODO: Will not work on HttpServletResonse interface #AA
    fun getCookies(): List<JcSpringHttpCookie> {
        val rawCookies = getFromMethod("getCookies") as Array<Any>? ?: arrayOf()
        return rawCookies.map { JcSpringHttpCookie.ofCookieObject(it) }
    }

    fun getContentAsString(): String = getFromMethod("getContentAsString")

    fun getHeaders(): List<JcSpringHttpHeader> {
        val headersNames = getFromMethod("getHeaderNames") as Collection<String>
        return headersNames.toList().map { JcSpringHttpHeader(it, getHeader(it).toList()) }
    }
}
