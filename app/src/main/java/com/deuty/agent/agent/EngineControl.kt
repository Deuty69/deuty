package com.deuty.agent.agent

import com.deuty.agent.core.model.UserChoice
import com.deuty.agent.core.model.UserDecision
import com.deuty.agent.core.util.Ids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The user's controls over a running task (requirement 15).
 *
 * Two mechanisms, deliberately different:
 *   - STOP is a volatile flag *plus* a job cancellation. It must take effect
 *     within milliseconds and must work even while the runner is suspended
 *     waiting for an answer, so it also completes any pending question.
 *   - PAUSE is a StateFlow the runner suspends on. No polling, no wakeups: a
 *     paused agent costs exactly nothing until the user resumes it.
 */
class EngineControl {

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    @Volatile
    var stopRequested: Boolean = false
        private set

    @Volatile
    var skipRequested: Boolean = false
        private set

    @Volatile
    var retryRequested: Boolean = false
        private set

    private val lock = Any()
    private var pendingDecision: CompletableDeferred<UserDecision>? = null
    private var pendingRequestId: String? = null

    val waitingForUser: Boolean get() = synchronized(lock) { pendingDecision != null }

    fun requestStop() {
        stopRequested = true
        _paused.value = false
        complete(UserDecision(pendingRequestIdOrNew(), UserChoice.STOP, null, 0L))
    }

    fun requestPause() {
        _paused.value = true
    }

    fun requestResume() {
        _paused.value = false
    }

    fun togglePause() {
        _paused.value = !_paused.value
    }

    fun requestSkip() {
        skipRequested = true
        _paused.value = false
        // A skip also answers any outstanding question, otherwise the runner
        // would stay parked and the button would look broken.
        complete(UserDecision(pendingRequestIdOrNew(), UserChoice.SKIP, null, 0L))
    }

    fun requestRetry() {
        retryRequested = true
        _paused.value = false
        complete(UserDecision(pendingRequestIdOrNew(), UserChoice.RETRY, null, 0L))
    }

    fun answer(decision: UserDecision) {
        _paused.value = false
        complete(decision)
    }

    fun reset() {
        stopRequested = false
        skipRequested = false
        retryRequested = false
        _paused.value = false
        synchronized(lock) {
            pendingDecision = null
            pendingRequestId = null
        }
    }

    /** Suspends until the user resumes or stops. */
    suspend fun awaitResume() {
        if (_paused.value) _paused.first { !it }
    }

    /** Suspends until the user answers. Returns STOP if the task was stopped. */
    suspend fun awaitDecision(requestId: String): UserDecision {
        val deferred = CompletableDeferred<UserDecision>()
        synchronized(lock) {
            pendingDecision = deferred
            pendingRequestId = requestId
        }
        // The user may have hit STOP before we got here.
        if (stopRequested) {
            complete(UserDecision(requestId, UserChoice.STOP, null, 0L))
        }
        return deferred.await()
    }

    private fun complete(decision: UserDecision) {
        val deferred = synchronized(lock) {
            val current = pendingDecision
            pendingDecision = null
            pendingRequestId = null
            current
        }
        deferred?.complete(decision)
    }

    private fun pendingRequestIdOrNew(): String =
        synchronized(lock) { pendingRequestId } ?: Ids.request()

    /** Consume-once flags, so a skip does not apply to the next step too. */
    fun consumeSkip(): Boolean {
        val value = skipRequested
        skipRequested = false
        return value
    }

    fun consumeRetry(): Boolean {
        val value = retryRequested
        retryRequested = false
        return value
    }
}
