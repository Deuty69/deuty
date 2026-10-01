package com.deuty.agent.agent

import com.deuty.agent.core.model.AgentTask
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.storage.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Durable task state (requirement 6).
 *
 * The agent writes state that outlives the process, because an accessibility
 * service is killed by the system whenever it wants: on memory pressure, on a
 * settings toggle, or when the user swipes the app away.
 *
 * Writes are debounced. A task can transition several times a second (RUNNING ->
 * VERIFYING -> RUNNING), and each transition would otherwise be a JSON encode
 * plus a file replace. Debouncing to [DEBOUNCE_MS] keeps the disk quiet while
 * still guaranteeing that a state older than ~300ms is on disk. Terminal states
 * are always flushed synchronously - that is the one write we cannot lose.
 */
class StateManager(
    private val repository: TaskRepository,
    private val log: AgentLog,
    private val clock: Clock,
    private val scope: CoroutineScope
) {
    private var saveJob: Job? = null
    private var dirty: AgentTask? = null

    /** Queue a write. Safe to call from any thread. */
    fun persistSoon(task: AgentTask, delayMs: Long = DEBOUNCE_MS) {
        dirty = task
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(delayMs)
            val pending = dirty ?: return@launch
            dirty = null
            repository.saveCurrent(pending)
        }
    }

    /** Flush immediately. Used for terminal states and before process exit. */
    suspend fun persistNow(task: AgentTask) {
        saveJob?.cancel()
        dirty = null
        repository.saveCurrent(task)
    }

    /** Called when the service unbinds or the process is going away. */
    suspend fun flush() {
        val pending = dirty ?: return
        dirty = null
        repository.saveCurrent(pending)
    }

    /** The task that was running when we last died, parked as PAUSED. */
    suspend fun loadInterrupted(): AgentTask? = repository.loadCurrent()

    suspend fun finish(task: AgentTask, keepHistory: Boolean, historyLimit: Int) {
        repository.clearCurrent()
        if (keepHistory && task.state != TaskState.CANCELLED) {
            repository.appendHistory(task, historyLimit)
        }
        log.i(TAG, "task ${task.id} finished as ${task.state}")
    }

    suspend fun clearAll() {
        saveJob?.cancel()
        dirty = null
        repository.clearEverything()
    }

    companion object {
        private const val TAG = "state"
        private const val DEBOUNCE_MS = 300L
    }
}
