package com.deuty.agent.core.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * Ids.
 *
 * Not UUID.randomUUID(): it uses SecureRandom, which on some low-end devices
 * blocks briefly on entropy at first use - and the agent generates ids inside
 * accessibility callbacks, which run on the main thread. Time + counter is
 * unique within a process, sortable, and free.
 */
object Ids {
    private val counter = AtomicInteger(0)

    private fun next(prefix: String): String {
        val seq = counter.incrementAndGet() and 0xFFFF
        return "$prefix-${java.lang.Long.toString(System.currentTimeMillis(), 36)}-$seq"
    }

    fun task(): String = next("task")
    fun step(): String = next("step")
    fun workflow(): String = next("wf")
    fun message(): String = next("msg")
    fun request(): String = next("ask")
    fun dump(): String = next("dump")
}
