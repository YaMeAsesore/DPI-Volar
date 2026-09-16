package com.example.dpi_volar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object NetworkDispatcher {
    private val threadCounter = AtomicInteger(0)

    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "dpi-volar-net-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    val IO: CoroutineDispatcher =
        Executors.newCachedThreadPool(threadFactory).asCoroutineDispatcher()
}
