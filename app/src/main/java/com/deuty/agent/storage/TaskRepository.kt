package com.deuty.agent.storage

import com.deuty.agent.core.json.TaskCodecs
import com.deuty.agent.core.model.AgentTask
import com.deuty.agent.core.model.ChatMessage
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.util.AgentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Task persistence (requirement 6) + the "history" part of memory (#18).
 *
 * Two separate concerns that share a store:
 *   current.json  - the live task, rewritten on every state transition so that
 *                   process death, activity recreation or a rotation can resume
 *                   exactly where the agent stopped. It is *not* auto-resumed:
 *                   resuming automation after an unattended restart would be
 *                   surprising and possibly dangerous, so the UI offers
 *                   "Resume" / "Discard" instead.
 *   history.json  - finished tasks, capped, compacted and redacted.
 */
class TaskRepository(
    private val store: JsonFileStore,
    private val log: AgentLog
) {

    suspend fun saveCurrent(task: AgentTask): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { store.writeObject(CURRENT, TaskCodecs.encodeTask(task)) }.getOrElse {
            log.e(TAG, "failed to persist current task", it)
            false
        }
        if (!ok) log.w(TAG, "current task not persisted; a crash now loses in-progress state")
        ok
    }

    suspend fun loadCurrent(): AgentTask? = withContext(Dispatchers.IO) {
        val decoded = runCatching { TaskCodecs.decodeTask(store.readObject(CURRENT)) }.getOrNull()
        decoded?.also {
            // A task that was RUNNING when the process died is by definition not
            // running any more. Park it in PAUSED so the UI can offer a resume.
            if (it.state == TaskState.RUNNING || it.state == TaskState.VERIFYING ||
                it.state == TaskState.RECOVERING || it.state == TaskState.WAITING_FOR_UI
            ) {
                it.state = TaskState.PAUSED
                it.stopReason = it.stopReason ?: "interrupted by process restart"
                log.w(TAG, "recovered an interrupted task (${it.id}); parked as PAUSED")
            }
        }
    }

    suspend fun clearCurrent() = withContext(Dispatchers.IO) {
        store.delete(CURRENT)
        Unit
    }

    /** Append a finished task to history, compacted and capped at [limit]. */
    suspend fun appendHistory(task: AgentTask, limit: Int): Boolean = withContext(Dispatchers.IO) {
        val encoded = runCatching { compact(TaskCodecs.encodeTask(task)) }.getOrNull() ?: return@withContext false
        val existing = store.readArray(HISTORY) ?: JSONArray()
        existing.put(encoded)
        while (existing.length() > limit.coerceAtLeast(1)) existing.remove(0)
        runCatching { store.writeArray(HISTORY, existing) }.getOrElse {
            log.e(TAG, "failed to write history", it)
            false
        }
    }

    suspend fun history(limit: Int = 50): List<AgentTask> = withContext(Dispatchers.IO) {
        val array = store.readArray(HISTORY) ?: return@withContext emptyList()
        val all = (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { TaskCodecs.decodeTask(it) }
        }
        all.takeLast(limit).asReversed()
    }

    suspend fun historyCount(): Int = withContext(Dispatchers.IO) {
        store.readArray(HISTORY)?.length() ?: 0
    }

    suspend fun clearHistory(): Boolean = withContext(Dispatchers.IO) { store.delete(HISTORY) }

    // --- chat transcript ----------------------------------------------------

    suspend fun appendMessages(messages: List<ChatMessage>, limit: Int = MAX_MESSAGES): Boolean =
        withContext(Dispatchers.IO) {
            val existing = store.readArray(CHAT) ?: JSONArray()
            messages.forEach { m -> runCatching { existing.put(TaskCodecs.encodeMessage(m)) } }
            while (existing.length() > limit) existing.remove(0)
            runCatching { store.writeArray(CHAT, existing) }.getOrDefault(false)
        }

    /**
     * Overwrite the transcript with [messages].
     *
     * The engine keeps the whole conversation in memory and re-saves it on a
     * debounce, so appending would duplicate the transcript on every write and
     * grow the file without bound. Append is still used for one-shot additions.
     */
    suspend fun saveMessages(messages: List<ChatMessage>, limit: Int = MAX_MESSAGES): Boolean =
        withContext(Dispatchers.IO) {
            val array = JSONArray()
            messages.takeLast(limit).forEach { m -> runCatching { array.put(TaskCodecs.encodeMessage(m)) } }
            runCatching { store.writeArray(CHAT, array) }.getOrElse {
                log.e(TAG, "failed to write the chat transcript", it)
                false
            }
        }

    suspend fun loadMessages(limit: Int = MAX_MESSAGES): List<ChatMessage> = withContext(Dispatchers.IO) {
        val array = store.readArray(CHAT) ?: return@withContext emptyList()
        TaskCodecs.decodeMessageList(array).takeLast(limit)
    }

    suspend fun clearMessages(): Boolean = withContext(Dispatchers.IO) { store.delete(CHAT) }

    suspend fun clearEverything(): Boolean = withContext(Dispatchers.IO) {
        listOf(CURRENT, HISTORY, CHAT).all { store.delete(it) }
    }

    /**
     * Drop the bulky, potentially sensitive parts of a finished task before it
     * goes into history: screen dumps, resolution diagnostics and step payloads.
     * The audit trail keeps *what* happened and *whether it worked*, not the
     * contents of other people's screens.
     */
    private fun compact(encoded: JSONObject): JSONObject {
        encoded.optJSONArray("steps")?.let { steps ->
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                step.optJSONObject("outcome")?.apply {
                    remove("data")
                    remove("ask")
                }
                step.remove("note")
            }
        }
        encoded.optJSONArray("history")?.let { records ->
            for (i in 0 until records.length()) {
                records.optJSONObject(i)?.remove("sig")
            }
        }
        return encoded
    }

    companion object {
        private const val TAG = "taskrepo"
        private const val CURRENT = "current-task.json"
        private const val HISTORY = "history.json"
        private const val CHAT = "chat.json"
        private const val MAX_MESSAGES = 300
    }
}
