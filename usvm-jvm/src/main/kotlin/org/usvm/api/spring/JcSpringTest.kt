package org.usvm.api.spring

import org.jacodb.api.jvm.JcClassType
import org.jacodb.api.jvm.JcClasspath
import org.jacodb.api.jvm.JcType
import org.jacodb.api.jvm.JcTypedMethod
import org.jacodb.api.jvm.MethodNotFoundException
import org.usvm.machine.state.JcState

import org.jacodb.api.jvm.ext.findClass
import org.jacodb.api.jvm.ext.findMethodOrNull
import org.jacodb.api.jvm.ext.int
import org.jacodb.api.jvm.ext.toType
import org.jacodb.api.jvm.ext.findType
import org.jacodb.api.jvm.ext.objectType
import org.usvm.UExpr
import org.usvm.USort
import org.usvm.api.util.JcTestStateResolver.ResolveMode
import org.usvm.machine.JcContext
import org.usvm.machine.state.JcSpringState
import org.usvm.machine.state.concreteMemory.JcConcreteMemory
import org.usvm.test.api.UTest
import org.usvm.test.api.UTestArraySetStatement
import org.usvm.test.api.UTestCreateArrayExpression
import org.usvm.test.api.UTestExpression
import org.usvm.test.api.UTestInst
import org.usvm.test.api.UTestIntExpression
import org.usvm.test.api.UTestStringExpression


fun JcClasspath.findJcMethod(cName: String, mName: String): JcTypedMethod {
    val method = this.findClass(cName).toType().findMethodOrNull { it.name == mName }
    method?.let { return it }
    throw MethodNotFoundException("$mName not found")
}

fun List<String>.toStringArrayDsl(ctx: JcContext): Pair<UTestCreateArrayExpression, MutableList<UTestInst>> {
    val initDSL = mutableListOf<UTestInst>()
    val stringType = ctx.stringType
    val intType = ctx.cp.int

    val arrayDSL = UTestCreateArrayExpression(
        elementType = stringType,
        size = UTestIntExpression(this.size, intType),
    ).also { initDSL.add(it) }

    this.forEachIndexed { idx, str ->
        UTestArraySetStatement(
            arrayInstance = arrayDSL,
            index = UTestIntExpression(idx, intType),
            setValueExpression = UTestStringExpression(str, stringType),
        ).also { initDSL.add(it) }
    }
    return Pair(arrayDSL, initDSL)
}

// todo:(path for test pipeline) /owners/find

data class SpringReqPath(
    val path: String,
    val pathVariables: List<Any>
)

class SpringExn

class JcSpringTest private constructor(
    val ctx: JcContext,
    val generatedTestClass: JcClassType,
    private val request: JcSpringRequest,
    private val response: JcSpringResponse?,
    private val exception: SpringExn?,
//    todo: exn
) {
    companion object {
        private val REQUEST_MOD = ResolveMode.MODEL
        private val RESPONSE_MOD = ResolveMode.CURRENT

        fun generateFromState(state: JcSpringState): JcSpringTest =
            if (state.getResult() != null)
                generateResponseTest(state)
            else
                generateExnTest(state)

        private fun generateResponseTest(state: JcSpringState): JcSpringTest = JcSpringTest(
            state.ctx,
            getGeneratedClassName(state.ctx.cp),
            request = getSpringRequest(state),
            response = getSpringResponse(state.ctx.cp, state),
            exception = null
        )

        private fun generateExnTest(state: JcSpringState): JcSpringTest = JcSpringTest(
            state.ctx,
            getGeneratedClassName(state.ctx.cp),
            request = getSpringRequest(state),
            response = null,
            exception = getSpringExn(),
        )

        private fun getSpringExn(): SpringExn {
            TODO()
        }

        private fun getGeneratedClassName(cp: JcClasspath): JcClassType {
            // TODO hardcoded
            val cl = cp.findClassOrNull("org.usvm.spring.benchmarks.StartSpringTestClass") //TODO: get it from state? (it is generated in runtime)
            check(cl != null)
            return cl.toType()
        }

        private fun getSpringResponse(cp: JcClasspath, state: JcSpringState): JcSpringResponse {
            // Will be refactored with common refactor merge!!
            val result = state.getResult()
            assert(result != null)
            val expr = result ?: throw IllegalArgumentException("No Response")
            val valueExpr = state.models[0].eval(expr)

            val type = cp.findType("org.springframework.mock.web.MockHttpServletResponse")

            // TODO: problem with cast
            val response = concretizeSimple(
                RESPONSE_MOD,
                state,
                valueExpr,
                type
            )

            check(response != null)
            return JcSpringResponse(response)
        }

        private fun getSpringRequest(state: JcSpringState): JcSpringRequest {
            val requestConcretizer = { value: UExpr<out USort>, type: JcType -> concretizeSimple(REQUEST_MOD, state, value, type) }
            return JcSpringPinnedValuesRequest(state.userDefinedValues, requestConcretizer)
        }

        private fun concretizeSimple(mode: ResolveMode, state: JcState, expr: UExpr<out USort>, type: JcType) =
            (state.memory as JcConcreteMemory).concretize(state, state.models[0].eval(expr), type, mode)
    }

    val isSuccess get() = response != null
    val isFail get() = exception != null
    val res get() = assert(isSuccess).let { response!! }
    val exn get() = assert(isFail).let { exception!! }

    fun generateTestDSL(): UTest {
        val initStatements: MutableList<UTestInst> = mutableListOf()
        val testExecBuilder = SpringTestExecDSLBuilder.initTestCtx(
            ctx = ctx,
            generatedTestClass = generatedTestClass,
            fromField = generatedTestClass.fields.first { it.name.contains("mockMvc") }.field //TODO: mb error here
        ).also { initStatements.addAll(it.getInitDSL()) }

        val reqDSL = generateReqDSL(request).let { (reqDSL, reqInitDSL) ->
            initStatements.addAll(reqInitDSL)
            reqDSL
        }
        testExecBuilder.addPerformCall(reqDSL)

        val matchersDSL = generateMatchersDSL().let { (matchersDSL, matchersInitDSL) ->
            initStatements.addAll(matchersInitDSL)
            matchersDSL
        }
        matchersDSL.forEach { testExecBuilder.addAndExpectCall(listOf(it)) }

        return UTest(
            initStatements = initStatements,
            callMethodExpression = testExecBuilder.getIgnoreDsl()
        )
    }

    private fun generateMatchersDSL(): Pair<List<UTestExpression>, List<UTestInst>> {
        val matchersBuilder = SpringMatchersDSLBuilder(ctx)

        matchersBuilder.addStatusCheck(res.getStatusCode())
        matchersBuilder.addContentCheck(res.getContentAsString())
        matchersBuilder.addHeadersCheck(res.getHeaders())
//      TODO("add more matchers")

        return Pair(matchersBuilder.getMatchersDSL(), matchersBuilder.getInitDSL())
    }

    private fun generateReqDSL(
        request: JcSpringRequest
    ): Pair<UTestExpression, List<UTestInst>> {

        val builder = SpringReqDSLBuilder.createRequest(ctx, request.getMethod(), request.getPath(), request.getUriVariables())
        request.getHeaders().forEach { builder.addHeader(it) }
        request.getParameters().forEach { builder.addParameter(it) }

        return Pair(builder.getDSL(), builder.getInitDSL())
    }

    fun getPath(): String {
        return request.getPath()
    }
}
