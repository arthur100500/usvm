package org.usvm.instrumentation.util

import org.usvm.jvm.util.JcExecutor
import org.usvm.jvm.util.withAccessibility
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ExecutionException

fun Method.invokeWithAccessibility(instance: Any?, args: List<Any?>, executor: JcExecutor): Any? =
    executeWithTimeout(executor) {
        withAccessibility {
            invoke(instance, *args.toTypedArray())
        }
    }

fun Constructor<*>.newInstanceWithAccessibility(args: List<Any?>, executor: JcExecutor): Any =
    executeWithTimeout(executor) {
        withAccessibility {
            newInstance(*args.toTypedArray())
        }
    } ?: error("Cant instantiate class ${this.declaringClass.name}")

private fun unfoldException(e: Throwable): Throwable {
    return when {
        e is InvocationTargetException && e.targetException != null -> e.targetException
        else -> e
    }
}

fun executeWithTimeout(executor: JcExecutor, body: () -> Any?): Any? {
    val timeout = InstrumentationModuleConstants.methodExecutionTimeout
    val (result, exception) = executor.executeWithResult(timeout, body)
    if (exception != null)
        throw unfoldException(exception)

    return result
}
