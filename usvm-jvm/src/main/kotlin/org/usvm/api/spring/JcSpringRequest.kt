package org.usvm.api.spring

import org.jacodb.api.jvm.JcType
import org.usvm.UExpr
import org.usvm.USort

interface JcSpringRequest {
    fun getCookies(): List<JcSpringHttpCookie>
    fun getHeaders(): List<JcSpringHttpHeader>
    fun getMethod(): String
    fun getFullPath(): String
    fun getContentAsString(): String
    fun getParameters(): List<JcSpringHttpParameter>
}

class JcSpringRealRequest(private val request: Any) : JcSpringRequest {
    override fun getCookies(): List<JcSpringHttpCookie> {
        TODO("Not yet implemented")
    }

    override fun getHeaders(): List<JcSpringHttpHeader> {
        TODO("Not yet implemented")
    }

    override fun getMethod(): String {
        TODO("Not yet implemented")
    }

    override fun getFullPath(): String {
        TODO("Not yet implemented")
    }

    override fun getContentAsString(): String {
        TODO("Not yet implemented")
    }

    override fun getParameters(): List<JcSpringHttpParameter> {
        TODO("Not yet implemented")
    }

}

class JcSpringPinnedValueRequest(private val pinnedValues: Map<String, Pair<UExpr<out USort>, JcType>>) : JcSpringRequest {
    override fun getCookies(): List<JcSpringHttpCookie> {
        TODO("Not yet implemented")
    }

    override fun getHeaders(): List<JcSpringHttpHeader> {
        TODO("Not yet implemented")
    }

    override fun getMethod(): String {
        TODO("Not yet implemented")
    }

    override fun getFullPath(): String {
        TODO("Not yet implemented")
    }

    override fun getContentAsString(): String {
        TODO("Not yet implemented")
    }

    override fun getParameters(): List<JcSpringHttpParameter> {
        TODO("Not yet implemented")
    }

}