package com.deuty.agent.core.util

import com.deuty.agent.core.model.AgentEvent
import com.deuty.agent.core.model.EventLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory ring buffer for the debug view (requirement 27).
 *
 * Not persisted by default: a log of everything the agent saw is exactly the
 * artefact that should not survive on disk. If the user turns on "log
 * accessibility events" it stays in RAM until the process dies.
 *
 * Publishing is done through a version counter rather than a flow of the whole
 * buffer, so a verbose event storm does not copy a 500-entry list per event.
 */
class AgentLog(
    private val capacity: Int = 500,
    private val clock: Clock = SystemTime
) {
    private val lock = Any()
    private val buffer = ArrayDeque<AgentEvent>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** When false, DEBUG-level events are dropped before they cost anything. */
    @Volatile
    var verbose: Boolean = false

    @Volatile
    var redactEnabled: Boolean = true

    fun log(level: EventLevel, tag: String, message: String) {
        if (level == EventLevel.DEBUG && !verbose) return
        val safe = Redactor.text(message, redactEnabled) ?: message
        val event = AgentEvent(clock.nowMs(), level, tag.take(11), safe)
        synchronized(lock) {
            buffer.addLast(event)
            while (buffer.size > capacity) buffer.removeFirst()
        }
        _version.value = _version.value + 1
    }

    fun d(tag: String, message: String) = log(EventLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(EventLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(EventLevel.WARN, tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) =
        log(EventLevel.ERROR, tag, if (error == null) message else "$message (${error.javaClass.simpleName}: ${error.message})")

    /** Newest last. */
    fun snapshot(limit: Int = capacity): List<AgentEvent> = synchronized(lock) {
        if (buffer.size <= limit) buffer.toList() else buffer.takeLast(limit)
    }

    fun last(n: Int = 1): List<AgentEvent> = synchronized(lock) { buffer.takeLast(n) }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _version.value = _version.value + 1
    }
}
