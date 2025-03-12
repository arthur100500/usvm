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

interface SpringReqAttr

data class ParamAttr(
    val name: String,
    val values: List<Any>,
//    val valueType: JcClassOrInterface, TODO: mb use it to generate DSL or concretize? (but it need support from Arthur)
) : SpringReqAttr

data class HeaderAttr(
    val name: String,
    val values: List<Any>,
//    val valueType: JcClassOrInterface, TODO: mb use it to generate DSL or concretize? (but it need support from Arthur)
) : SpringReqAttr

data class SpringReqPath(
    val path: String,
    val pathVariables: List<Any>
)

enum class SpringReqKind {
    GET,
    PUT,
    POST,
    PATCH,
    DELETE;

    override fun toString(): String {
        return when (this) {
            GET -> "get"
            PUT -> "put"
            POST -> "post"
            PATCH -> "patch"
            DELETE -> "delete"
        }
    }

    companion object {
        fun fromString(str: String): SpringReqKind =
            when (str) {
                GET.toString() -> GET
                PUT.toString() -> PUT
                POST.toString() -> POST
                PATCH.toString() -> PATCH
                DELETE.toString() -> DELETE
                else -> throw IllegalArgumentException("Unsupported kind: $str")
            }
    }

}

enum class SpringReqSettings {
    PATH,
    KIND,
}

class SpringExn

class JcSpringTest private constructor(
    val ctx: JcContext,
    val generatedTestClass: JcClassType,
    /* Request information */
    val reqAttrs: List<SpringReqAttr>,
    val reqKind: SpringReqKind,
    val reqPath: SpringReqPath,
    /* Response information */
    private val response: JcSpringResponse?,
    private val exception: SpringExn?,
//    todo: exn
) {
    companion object {
        val REQUEST_MOD = ResolveMode.MODEL
        val RESPONSE_MOD = ResolveMode.CURRENT

        fun generateFromState(state: JcSpringState): JcSpringTest =
            if (state.res != null)
                generateResponseTest(state)
            else
                generateExnTest(state)

        private fun generateResponseTest(state: JcSpringState): JcSpringTest = JcSpringTest(
            state.ctx,
            getGeneratedClassName(state.ctx.cp),
            getReqAttrs(state),
            getReqKind(state),
            getReqPath(state),
            response = getSpringResponse(state.ctx.cp, state),
            exception = null
        )

        private fun generateExnTest(state: JcSpringState): JcSpringTest = JcSpringTest(
            state.ctx,
            getGeneratedClassName(state.ctx.cp),
            getReqAttrs(state),
            getReqKind(state),
            getReqPath(state),
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
            return cl!!.toType()
        }

        private fun getReqKind(state: JcSpringState): SpringReqKind {
            val expr = state.reqSetup[SpringReqSettings.KIND] ?: throw IllegalArgumentException("No path found")
            val type = state.ctx.stringType as JcClassType
            val kind = concretizeSimple(REQUEST_MOD, state, expr, type)
            assert(kind != null)

            return SpringReqKind.fromString(kind as String)
        }

        private fun getReqPath(state: JcSpringState): SpringReqPath {
            fun sortReqParam(path: String, params: Map<String, Any>): List<Any> {
//                TODO: check it
                val paramNames = Regex("\\{([^}]*)}").findAll(path)
                    .map { it.groupValues[1] }
                    .toList()

                return paramNames.map {
                    params.getValue(it)
                }.also { check(it.size == params.size) }
            }

            val expr = state.reqSetup[SpringReqSettings.PATH] ?: throw IllegalArgumentException("No path found")
            val type = state.ctx.stringType as JcClassType
            val path = concretizeSimple(REQUEST_MOD, state, expr, type)
            assert(path != null)

            val concreteReqParams = mutableMapOf<String, Any>().also { map ->
                state.userDefinedValues.forEach { (key, value) ->
                    if (key.contains("PATH_*".toRegex())) {
                        val name = key.split("_").also { it.subList(1, it.size) }.joinToString("_")
                        concretizeSimple(REQUEST_MOD, state, value.first, value.second).also {
                            assert(it != null) //TODO: is it correct? (param have name? but == null)
                            map[name] = it!!
                        }
                    }
                }
            }

            return SpringReqPath(
                path = path!! as String,
                pathVariables = sortReqParam(path as String, concreteReqParams)
            )
        }

        private fun getReqAttrs(state: JcSpringState): MutableList<SpringReqAttr> {
            fun concretize(expr: UExpr<out USort>, type: JcType) =
                concretizeAsList(REQUEST_MOD, state, expr, type)

            fun getHeaderAttr(name: String, expr: UExpr<out USort>, type: JcType) = concretize(expr, type)?.let {
                HeaderAttr(
                    name = name,
                    values = it
                )
            }

            fun getParamAttr(name: String, expr: UExpr<out USort>, type: JcType) = concretize(expr, type)?.let {
                ParamAttr(
                    name = name,
                    values = it
                )
            }

            return mutableListOf<SpringReqAttr>().also { list ->
                state.userDefinedValues.forEach { (key, value) ->
                    //TODO: (MCHK): null -> no attr
                    when {
                        key.contains("PARAM_*".toRegex()) -> {
                            val name = key.split("_").let { it.subList(1, it.size) }.joinToString("_")
                            getHeaderAttr(name, value.first, value.second)
                        }

                        key.contains("HEADER_*".toRegex()) -> {
                            val name = key.split("_").let { it.subList(1, it.size) }.joinToString("_")
                            getParamAttr(name, value.first, value.second)
                        }

                        else -> null
                    }?.also { list.add(it) }
                }
            }
        }

        private fun getSpringResponse(cp: JcClasspath, state: JcSpringState): JcSpringResponse {
            assert(state.res != null)
            val expr = state.res ?: throw IllegalArgumentException("No Response")
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

        private fun concretizeSimple(mode: ResolveMode, state: JcState, expr: UExpr<out USort>, type: JcType) =
            (state.memory as JcConcreteMemory).concretize(
                state, state.models[0].eval(expr), type, mode
            )

        private fun concretizeAsList(mode: ResolveMode, state: JcState, expr: UExpr<out USort>, type: JcType) =
            concretizeSimple(mode, state, expr, type)?.let { value ->
                if (value is Iterable<*>) value.map { it!! }.toList()
                else listOf(value)
            }
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

        val reqDSL = generateReqDSL(reqKind, reqPath, reqAttrs).let { (reqDSL, reqInitDSL) ->
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
        reqKind: SpringReqKind,
        reqPath: SpringReqPath,
        reqAttrs: List<SpringReqAttr>
    ): Pair<UTestExpression, List<UTestInst>> {
        val builder = SpringReqDSLBuilder.createReq(ctx, reqKind, reqPath).addAttrs(reqAttrs)
        return Pair(builder.getDSL(), builder.getInitDSL())
    }
}
