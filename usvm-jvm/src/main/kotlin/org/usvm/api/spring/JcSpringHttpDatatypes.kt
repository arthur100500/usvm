package org.usvm.api.spring

abstract class JcSpringNamedMultiValueHolder(private val name: String, private val values: List<String>) {
    fun getName() = name
    fun getValues() = values
}

// TODO: Add other datatypes if necessary
class JcSpringHttpHeader(name: String, values: List<String>) : JcSpringNamedMultiValueHolder(name, values)
class JcSpringHttpParameter(name: String, values: List<String>) : JcSpringNamedMultiValueHolder(name, values)
class JcSpringHttpCookie(name: String, values: List<String>) : JcSpringNamedMultiValueHolder(name, values)

enum class JcSpringRequestMethod {
    GET,
    PUT,
    POST,
    PATCH,
    DELETE;
}