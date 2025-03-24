package org.usvm.instrumentation.util

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

class TestTaskExecutor(private val customClassLoader: ClassLoader? = null) {    
    private fun setClassLoader() {
        if (customClassLoader == null) return
        Thread.currentThread().contextClassLoader = customClassLoader
    }

    private val threadFactory = ThreadFactory { runnable ->
        Thread { setClassLoader(); runnable.run() }
    }

    private val executor = Executors.newSingleThreadExecutor(threadFactory)

    fun runWithTimeout(timeout: Long, timeUnit: TimeUnit, task: Runnable): Any? {
        val future = executor.submit(task)
        return future.get(timeout, timeUnit)
    }
}