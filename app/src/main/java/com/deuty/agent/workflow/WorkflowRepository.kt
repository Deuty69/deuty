package com.deuty.agent.workflow

import com.deuty.agent.core.json.TaskCodecs
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.TaskStep
import com.deuty.agent.core.model.Workflow
import com.deuty.agent.core.model.WorkflowSource
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.TextMatch
import com.deuty.agent.storage.JsonFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Workflow memory (requirement 18).
 *
 * One JSON array, an in-memory copy for the planner (which must be able to ask
 * "do I have a workflow called X?" synchronously while parsing a sentence), and
 * a StateFlow for the UI.
 *
 * Scale note: this is fine up to a few hundred workflows. Beyond that the right
 * move is a per-workflow file plus an index, not a database - see
 * docs/DECISIONS.md ADR-011.
 */
class WorkflowRepository(
    private val store: JsonFileStore,
    private val clock: Clock,
    private val log: AgentLog
) {

    private val _workflows = MutableStateFlow<List<Workflow>>(emptyList())
    val workflows: StateFlow<List<Workflow>> = _workflows.asStateFlow()

    @Volatile
    private var loaded = false

    suspend fun load(): List<Workflow> = withContext(Dispatchers.IO) {
        val array = store.readArray(FILE) ?: JSONArray()
        val list = TaskCodecs.decodeWorkflowList(array)
        _workflows.value = list
        loaded = true
        log.d(TAG, "loaded ${list.size} workflow(s)")
        list
    }

    /** Synchronous view for the planner; empty until [load] has run. */
    fun cached(): List<Workflow> = _workflows.value

    suspend fun all(): List<Workflow> {
        if (!loaded) load()
        return _workflows.value
    }

    suspend fun get(id: String): Workflow? = all().firstOrNull { it.id == id }

    /** Name match, tolerant of "run download college timetable" style phrasing. */
    suspend fun findByName(name: String): Workflow? {
        val wanted = TextMatch.normalize(name)
        if (wanted.isEmpty()) return null
        val list = all()
        return list.firstOrNull { TextMatch.normalize(it.name) == wanted }
            ?: list.firstOrNull { TextMatch.normalize(it.name).contains(wanted) }
            ?: list.firstOrNull { wanted.contains(TextMatch.normalize(it.name)) }
            ?: list.map { it to TextMatch.similarity(it.name, name) }
                .filter { it.second >= 0.8f }
                .maxByOrNull { it.second }?.first
    }

    suspend fun save(
        name: String,
        steps: List<TaskStep>,
        source: WorkflowSource = WorkflowSource.RECORDED,
        packages: List<String> = emptyList(),
        notes: String? = null,
        id: String? = null
    ): Workflow = withContext(Dispatchers.IO) {
        val now = clock.nowMs()
        val risk = steps.fold(RiskLevel.LOW) { acc, s -> if (s.risk.rank > acc.rank) s.risk else acc }
        val workflow = Workflow(
            id = id ?: Ids.workflow(),
            name = name.trim().ifBlank { "Workflow ${now}" },
            steps = steps,
            createdAtMs = now,
            updatedAtMs = now,
            source = source,
            packages = packages,
            notes = notes,
            riskLevel = risk
        )
        val list = all().filterNot { it.id == workflow.id } + workflow
        _workflows.value = list.sortedByDescending { it.updatedAtMs }
        persist()
        log.i(TAG, "saved workflow \"${workflow.name}\" (${steps.size} steps)")
        workflow
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val before = all().size
        _workflows.value = all().filterNot { it.id == id }
        persist()
        val ok = _workflows.value.size < before
        if (ok) log.i(TAG, "deleted workflow $id")
        ok
    }

    suspend fun recordRun(id: String, success: Boolean): Workflow? = withContext(Dispatchers.IO) {
        val updated = all().map {
            if (it.id == id) {
                it.copy(
                    lastRunAtMs = clock.nowMs(),
                    runCount = it.runCount + 1,
                    successCount = it.successCount + if (success) 1 else 0
                )
            } else it
        }
        _workflows.value = updated
        persist()
        updated.firstOrNull { it.id == id }
    }

    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        val count = all().size
        _workflows.value = emptyList()
        store.delete(FILE)
        count
    }

    private fun persist() {
        val ok = runCatching { store.writeArray(FILE, TaskCodecs.encodeWorkflowList(_workflows.value)) }
            .getOrDefault(false)
        if (!ok) log.e(TAG, "could not persist workflows")
    }

    /** Steps of a workflow, ready to become a task. Fresh ids, reset statuses. */
    fun instantiate(workflow: Workflow): List<TaskStep> = workflow.steps.mapIndexed { index, step ->
        step.copy(
            id = "w${index + 1}",
            status = com.deuty.agent.core.model.StepStatus.PENDING,
            attempts = 0,
            note = null,
            lastOutcome = null
        )
    }

    companion object {
        private const val TAG = "workflows"
        private const val FILE = "workflows.json"
    }
}
