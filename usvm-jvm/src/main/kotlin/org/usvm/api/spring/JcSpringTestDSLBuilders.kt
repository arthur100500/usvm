package org.usvm.api.spring

import org.jacodb.api.jvm.JcClassType
import org.jacodb.api.jvm.JcField
import org.jacodb.api.jvm.JcMethod
import org.jacodb.api.jvm.ext.int
import org.usvm.jvm.util.stringType
import org.usvm.machine.JcContext
import org.usvm.test.api.ArithmeticOperationType
import org.usvm.test.api.ConditionType
import org.usvm.test.api.UTest
import org.usvm.test.api.UTestAllocateMemoryCall
import org.usvm.test.api.UTestArithmeticExpression
import org.usvm.test.api.UTestArrayGetExpression
import org.usvm.test.api.UTestArrayLengthExpression
import org.usvm.test.api.UTestArraySetStatement
import org.usvm.test.api.UTestBinaryConditionExpression
import org.usvm.test.api.UTestBinaryConditionStatement
import org.usvm.test.api.UTestBooleanExpression
import org.usvm.test.api.UTestByteExpression
import org.usvm.test.api.UTestCall
import org.usvm.test.api.UTestCastExpression
import org.usvm.test.api.UTestCharExpression
import org.usvm.test.api.UTestClassExpression
import org.usvm.test.api.UTestConstExpression
import org.usvm.test.api.UTestInst
import org.usvm.test.api.UTestExpression
import org.usvm.test.api.UTestConstructorCall
import org.usvm.test.api.UTestCreateArrayExpression
import org.usvm.test.api.UTestDoubleExpression
import org.usvm.test.api.UTestFloatExpression
import org.usvm.test.api.UTestGetFieldExpression
import org.usvm.test.api.UTestGetStaticFieldExpression
import org.usvm.test.api.UTestIntExpression
import org.usvm.test.api.UTestLongExpression
import org.usvm.test.api.UTestMethodCall
import org.usvm.test.api.UTestMockObject
import org.usvm.test.api.UTestNullExpression
import org.usvm.test.api.UTestSetFieldStatement
import org.usvm.test.api.UTestSetStaticFieldStatement
import org.usvm.test.api.UTestShortExpression
import org.usvm.test.api.UTestStatement
import org.usvm.test.api.UTestStaticMethodCall
import org.usvm.test.api.UTestStringExpression
import org.usvm.util.name


class SpringTestExecDSLBuilder private constructor(
    private val ctx: JcContext,
    private val initStatements: MutableList<UTestStatement>,
    private var mockMvcDSL: UTestExpression,
    private var isPerformed: Boolean = false,
    private var generatedTestClass: JcClassType,
    private var testClassInstDSL: UTestExpression
) {
    companion object {
        /*
        * DSL STEPS:
        *   ctxManager: TestContextManager = new TestContextManager(<GENERATED-CLASS>.class)
        *   generatedClass: <GENERATED-CLASS> = new <GENERATED-CLASS>()
        *   ctxManager.prepareTestInstance(generatedClass)
        *   mockMvc: MockMvc = generatedClass.<FIELD-WITH-MOCKMVC>
        * */
        fun initTestCtx(
            ctx: JcContext,
            generatedTestClass: JcClassType,
            fromField: JcField
        ): SpringTestExecDSLBuilder {
            val initStatements = mutableListOf<UTestStatement>()

            val testCtxManagerName = "org.springframework.test.context.TestContextManager"
            val testCtxManagerDSL = UTestConstructorCall(
                method = ctx.cp.findJcMethod(testCtxManagerName, "<init>").method,
                args = listOf(UTestClassExpression(generatedTestClass))
            )

            val generatedClassInstDSL = UTestConstructorCall(
                method = ctx.cp.findJcMethod(generatedTestClass.name, "<init>").method,
                args = listOf()
            )

            UTestMethodCall(
                instance = testCtxManagerDSL,
                method = ctx.cp.findJcMethod(testCtxManagerName, "prepareTestInstance").method,
                args = listOf(generatedClassInstDSL)
            )

            val mockMvcDSL = UTestGetFieldExpression(
                instance = generatedClassInstDSL,
                field = fromField,
            )

            return SpringTestExecDSLBuilder(
                ctx = ctx,
                initStatements = initStatements,
                mockMvcDSL = mockMvcDSL,
                generatedTestClass = generatedTestClass,
                testClassInstDSL = generatedClassInstDSL
            )
        }
    }

    fun getTestClassInstance() = testClassInstDSL

    fun addPerformCall(reqDSL: UTestExpression): SpringTestExecDSLBuilder {
        UTestMethodCall(
            instance = mockMvcDSL,
            method = ctx.cp.findJcMethod("org.springframework.test.web.servlet.MockMvc", "perform").method,
            args = listOf(reqDSL)
        ).also {
            mockMvcDSL = it
            isPerformed = true
        }
        return this
    }

    fun addAndExpectCall(args: List<UTestExpression>): SpringTestExecDSLBuilder {
        assert(isPerformed)

        UTestMethodCall(
            instance = mockMvcDSL,
            method = ctx.cp.findJcMethod("org.springframework.test.web.servlet.ResultActions", "andExpect").method,
            args = args
        ).also {
            mockMvcDSL = it
        }
        return this
    }

    fun getInitDSL(): List<UTestInst> = initStatements

    fun getIgnoreDsl(): UTestCall {
        assert(isPerformed)
        UTestStaticMethodCall(
            method = ctx.cp.findJcMethod(generatedTestClass.name, "ignoreResult").method,
            args = listOf(mockMvcDSL)
        ).also {
            mockMvcDSL = it
        }
        return mockMvcDSL as UTestCall
    }
}

class SpringMatchersDSLBuilder(
    val ctx: JcContext
) {
    private val SPRING_RESULT_PACK = "org.springframework.test.web.servlet.result"

    private val initStatements: MutableList<UTestStatement> = mutableListOf()
    private val matchers: MutableList<UTestExpression> = mutableListOf()

    private fun wrapStringList(list: List<Any>): UTestCreateArrayExpression {
        val listDsl = UTestCreateArrayExpression(ctx.cp.stringType(), UTestIntExpression(list.size, ctx.cp.int))
        val listInitializer = List(list.size) {
            UTestArraySetStatement(
                listDsl,
                UTestIntExpression(it, ctx.cp.int),
                // TODO: Learn how to do object expression #AA
                UTestStringExpression(list[it].toString(), ctx.stringType)
            )
        }
        initStatements.addAll(listInitializer)
        return listDsl
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrapArgument(argument: Any): UTestExpression {
        // TODO: other types #AA
        if (argument is List<*>)
            return wrapStringList(argument as List<Any>)
        return when (argument.javaClass) {
            Integer::class.java -> UTestIntExpression(argument as Int, ctx.cp.int)
            String::class.java -> UTestStringExpression(argument as String, ctx.cp.stringType())
            else -> error("TODO #AA")
        }
    }

    private fun addMatcher(matcherName: String, matcherArguments: List<Any> = listOf()): UTestCall {
        val createMatcherMethod = ctx.cp.findJcMethod(
            "$SPRING_RESULT_PACK.MockMvcResultMatchers",
            matcherName
        ).method

        val matcherDsl = UTestStaticMethodCall(
            method = createMatcherMethod,
            args = matcherArguments.map { wrapArgument(it) }.toList()
        )

        return matcherDsl
    }

    private fun addCondition(matcherSourceDsl: UTestCall, conditionName: String, conditionArguments: List<Any> = listOf()): UTestExpression {
        val conditionMethod = ctx.cp.findJcMethod(
            matcherSourceDsl.method!!.returnType.typeName,
            conditionName
        ).method

        val conditionDsl = UTestMethodCall(
            instance = matcherSourceDsl,
            method = conditionMethod,
            args = conditionArguments.map { wrapArgument(it) }.toList()
        ).also { matchers.add(it) }

        return conditionDsl
    }

    fun addStatusCheck(int: Int): SpringMatchersDSLBuilder {
        addCondition(addMatcher("status"), "is", listOf(int))
        return this
    }

    fun addContentCheck(content: String): SpringMatchersDSLBuilder {
        addCondition(addMatcher("content"), "string", listOf(content))
        return this
    }

    fun addHeadersCheck(headers: List<JcSpringHttpHeader>): SpringMatchersDSLBuilder {
        val matcher = addMatcher("header")
        headers.forEach { addCondition(matcher, "stringValues", listOf(it.getName(), it.getValues())) }
        return this
    }

    fun getInitDSL(): List<UTestInst> = initStatements
    fun getMatchersDSL(): List<UTestExpression> = matchers
}


class SpringReqDSLBuilder private constructor(
    private val initStatements: MutableList<UTestStatement>,
    private var reqDSL: UTestExpression,
    private val ctx: JcContext
) {
    companion object {

        fun createRequest(ctx: JcContext, method: JcSpringRequestMethod, path: String, pathVariables: List<Any?>): SpringReqDSLBuilder =
            commonReqDSLBuilder(ctx, method, path, pathVariables)

        private const val MOCK_MVC_REQUEST_BUILDERS_CP =
            "org.springframework.test.web.servlet.request.MockMvcRequestBuilders"

        private const val MOCK_HTTP_SERVLET_REQUEST_BUILDER_CP =
            "org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder"

        private fun commonReqDSLBuilder(
            ctx: JcContext,
            method: JcSpringRequestMethod,
            path: String,
            pathVariables: List<Any?>
        ): SpringReqDSLBuilder {
            val requestMethodName = method.name.lowercase()
            val staticMethod = ctx.cp.findJcMethod(MOCK_MVC_REQUEST_BUILDERS_CP, requestMethodName).method
            val initDSL = mutableListOf<UTestStatement>()
            val pathArgs = pathVariables.map { it }
            val pathArgsArray = UTestCreateArrayExpression(ctx.stringType, UTestIntExpression(pathArgs.size, ctx.cp.int))
            val pathArgsInitializer = List(pathArgs.size) {
                UTestArraySetStatement(
                    pathArgsArray,
                    UTestIntExpression(it, ctx.cp.int),
                    // TODO: Learn how to do object expression #AA
                    UTestStringExpression(pathArgs[it].toString(), ctx.stringType)
                )
            }
            initDSL.addAll(pathArgsInitializer)
            val argsDSL = mutableListOf<UTestExpression>()
            argsDSL.add(UTestStringExpression(path, ctx.stringType))
            argsDSL.add(pathArgsArray)
            return SpringReqDSLBuilder(
                initStatements = initDSL,
                reqDSL = UTestStaticMethodCall(staticMethod, argsDSL),
                ctx = ctx
            )
        }
    }

    fun getInitDSL(): List<UTestInst> = initStatements
    fun getDSL() = reqDSL

    fun addParameter(attr: JcSpringHttpParameter): SpringReqDSLBuilder {
        val method = ctx.cp.findJcMethod(MOCK_HTTP_SERVLET_REQUEST_BUILDER_CP, "param").method
        addStrArrOfStrCallDSL(method, attr.getName(), attr.getValues())
        return this
    }

    fun addHeader(attr: JcSpringHttpHeader): SpringReqDSLBuilder {
        val method = ctx.cp.findJcMethod(MOCK_HTTP_SERVLET_REQUEST_BUILDER_CP, "header").method
        addStrArrOfStrCallDSL(method, attr.getName(), attr.getValues())
        return this
    }

    private fun addStrArrOfStrCallDSL(mName: JcMethod, str: String, arrOfStr: List<Any>) {
        val argsDSL = mutableListOf<UTestExpression>()
        argsDSL.add(UTestStringExpression(str, ctx.stringType))
        argsDSL.addAll(arrOfStr.map { UTestStringExpression(it.toString(), ctx.stringType) })
        UTestMethodCall(
            instance = reqDSL,
            method = mName,
            args = argsDSL,
        ).also { reqDSL = it }
    }
}