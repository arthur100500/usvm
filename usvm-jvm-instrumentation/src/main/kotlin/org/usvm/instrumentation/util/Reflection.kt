package org.usvm.instrumentation.util

import org.usvm.jvm.util.withAccessibility
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.*

fun Method.invokeWithAccessibility(instance: Any?, args: List<Any?>, executor: TestTaskExecutor): Any? =
    executeWithTimeout(executor) {
        withAccessibility {
            invoke(instance, *args.toTypedArray())
        }
    }

fun Constructor<*>.newInstanceWithAccessibility(args: List<Any?>, executor: TestTaskExecutor): Any =
    executeWithTimeout(executor) {
        withAccessibility {
            newInstance(*args.toTypedArray())
        }
    } ?: error("Cant instantiate class ${this.declaringClass.name}")

private fun unwrapTargetInvocationException(exception: Throwable): Throwable {
    if (exception is InvocationTargetException) 
        return exception.targetException
    return exception
}

fun executeWithTimeout(executor: TestTaskExecutor, body: () -> Any?): Any? {
    var result: Any? = null
    val timeout = InstrumentationModuleConstants.methodExecutionTimeout
    executor.runWithTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) {
        result = try {
            body()
        } catch (e: Throwable) {
            throw unwrapTargetInvocationException(e)
        }
    }
    return result
}
