package com.deuty.agent.core.json

import com.deuty.agent.core.model.ActionOutcome
import com.deuty.agent.core.model.AgentTask
import com.deuty.agent.core.model.AskUserRequest
import com.deuty.agent.core.model.ChatMessage
import com.deuty.agent.core.model.FailureReason
import com.deuty.agent.core.model.MatchTier
import com.deuty.agent.core.model.OutcomeStatus
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.Role
import com.deuty.agent.core.model.StepRecord
import com.deuty.agent.core.model.StepStatus
import com.deuty.agent.core.model.RetryPolicy
import com.deuty.agent.core.model.TaskBudget
import com.deuty.agent.core.model.TaskConstraints
import com.deuty.agent.core.model.TaskSource
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.model.TaskStep
import com.deuty.agent.core.model.Workflow
import com.deuty.agent.core.model.WorkflowSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence codecs for tasks, outcomes, workflows and chat.
 *
 * Redaction is NOT done here. Codecs stay dumb and reversible; the redaction
 * policy lives at the boundary where data *enters* storage (TaskManager,
 * HistoryStore, WorkflowRecorder), because "what may be stored" differs per
 * artefact: a workflow must keep the text it will type again, while a history
 * entry must not. See docs/PRIVACY.md.
 */
object TaskCodecs {

    // ------------------------------------------------------------ primitives

    fun encodeRetry(r: RetryPolicy): JSONObject = Json.obj(
        "max" to r.maxAttempts,
        "backoff" to r.backoffMs,
        "mult" to r.backoffMultiplier,
        "cap" to r.maxBackoffMs,
        "reobserve" to r.reObserveBeforeRetry
    )

    fun decodeRetry(o: JSONObject?): RetryPolicy {
        if (o == null) return RetryPolicy.DEFAULT
        return RetryPolicy(
            maxAttempts = o.intOr("max", 3).coerceIn(1, 10),
            backoffMs = o.longOr("backoff", 350L),
            backoffMultiplier = o.dblOr("mult", 1.8).coerceIn(1.0, 4.0),
            maxBackoffMs = o.longOr("cap", 3_000L),
            reObserveBeforeRetry = o.boolOr("reobserve", true)
        )
    }

    fun encodeBudget(b: TaskBudget): JSONObject = Json.obj(
        "maxActions" to b.maxActions,
        "maxMs" to b.maxDurationMs,
        "maxStepRetries" to b.maxRetriesPerStep,
        "maxRetries" to b.maxTotalRetries,
        "loopWindow" to b.loopWindow,
        "loopThreshold" to b.loopRepeatThreshold,
        "maxSteps" to b.maxSteps
    )

    fun decodeBudget(o: JSONObject?): TaskBudget {
        if (o == null) return TaskBudget.DEFAULT
        return TaskBudget(
            maxActions = o.intOr("maxActions", 60).coerceIn(1, 500),
            maxDurationMs = o.longOr("maxMs", 600_000L).coerceAtLeast(1_000L),
            maxRetriesPerStep = o.intOr("maxStepRetries", 3).coerceIn(0, 10),
            maxTotalRetries = o.intOr("maxRetries", 12).coerceIn(0, 50),
            loopWindow = o.intOr("loopWindow", 16).coerceIn(4, 64),
            loopRepeatThreshold = o.intOr("loopThreshold", 3).coerceIn(2, 10),
            maxSteps = o.intOr("maxSteps", 40).coerceIn(1, 200)
        )
    }

    fun encodeConstraints(c: TaskConstraints): JSONObject = Json.obj(
        "allow" to Json.arr(c.allowedPackages.toList()),
        "forbid" to Json.arr(c.forbiddenPackages.toList()),
        "maxRisk" to c.maxRisk.name,
        "confirmAt" to c.confirmAtOrAbove.name,
        "offline" to c.offlineOnly,
        "deadline" to c.deadlineMs,
        "notes" to c.notes
    )

    fun decodeConstraints(o: JSONObject?): TaskConstraints {
        if (o == null) return TaskConstraints()
        return TaskConstraints(
            allowedPackages = o.strList("allow").toSet(),
            forbiddenPackages = o.strList("forbid").toSet(),
            maxRisk = o.enumOrNull<RiskLevel>("maxRisk") ?: RiskLevel.HIGH,
            confirmAtOrAbove = o.enumOrNull<RiskLevel>("confirmAt") ?: RiskLevel.MEDIUM,
            offlineOnly = o.boolOr("offline", true),
            deadlineMs = if (o.has("deadline") && !o.isNull("deadline")) o.optLong("deadline") else null,
            notes = o.strOrNull("notes")
        )
    }

    // -------------------------------------------------------------- outcome

    fun encodeOutcome(outcome: ActionOutcome?): JSONObject? {
        if (outcome == null) return null
        return Json.obj(
            "s" to outcome.status.name,
            "m" to outcome.message,
            "r" to outcome.reason?.name,
            "ok" to outcome.verified,
            "tier" to outcome.tier?.name,
            "at" to outcome.startedAtMs,
            "ms" to outcome.durationMs,
            "sig0" to outcome.signatureBefore,
            "sig1" to outcome.signatureAfter,
            "att" to outcome.attempts,
            "data" to outcome.data,
            "ask" to encodeAsk(outcome.ask)
        )
    }

    fun decodeOutcome(o: JSONObject?): ActionOutcome? {
        if (o == null) return null
        val status = o.enumOrNull<OutcomeStatus>("s") ?: return null
        return ActionOutcome(
            status = status,
            message = o.strOrNull("m") ?: "",
            reason = o.enumOrNull<FailureReason>("r"),
            verified = o.boolOr("ok", false),
            tier = o.enumOrNull<MatchTier>("tier"),
            startedAtMs = o.longOr("at", 0L),
            durationMs = o.longOr("ms", 0L),
            signatureBefore = o.strOrNull("sig0"),
            signatureAfter = o.strOrNull("sig1"),
            attempts = o.intOr("att", 1),
            data = o.strOrNull("data"),
            ask = decodeAsk(o.objOrNull("ask"))
        )
    }

    fun encodeAsk(ask: AskUserRequest?): JSONObject? {
        if (ask == null) return null
        return Json.obj(
            "id" to ask.id,
            "q" to ask.question,
            "opts" to Json.arr(ask.options),
            "free" to ask.allowFreeText,
            "risk" to ask.risk.name,
            "reason" to ask.reason?.name,
            "step" to ask.stepId,
            "task" to ask.taskId,
            "at" to ask.createdAtMs,
            "denyStops" to ask.denyStopsTask
        )
    }

    fun decodeAsk(o: JSONObject?): AskUserRequest? {
        if (o == null) return null
        return AskUserRequest(
            id = o.strOrNull("id") ?: return null,
            question = o.strOrNull("q") ?: "Continue?",
            options = o.strList("opts"),
            allowFreeText = o.boolOr("free", false),
            risk = o.enumOrNull<RiskLevel>("risk") ?: RiskLevel.MEDIUM,
            reason = o.enumOrNull<FailureReason>("reason"),
            stepId = o.strOrNull("step"),
            taskId = o.strOrNull("task"),
            createdAtMs = o.longOr("at", 0L),
            denyStopsTask = o.boolOr("denyStops", false)
        )
    }

    // ----------------------------------------------------------------- step

    fun encodeStep(step: TaskStep): JSONObject = JSONObject()
        .set("id", step.id)
        .set("title", step.title)
        .set("a", ActionCodecs.encodeAction(step.action))
        .set("pre", ActionCodecs.encodeExpectation(step.precondition))
        .set("post", ActionCodecs.encodeExpectation(step.postcondition))
        .set("timeout", step.timeoutMs)
        .set("retry", encodeRetry(step.retry))
        .set("risk", step.riskOverride?.name)
        .set("status", step.status.name)
        .set("attempts", step.attempts)
        .set("note", step.note)
        .set("outcome", encodeOutcome(step.lastOutcome))

    fun decodeStep(o: JSONObject?): TaskStep? {
        if (o == null) return null
        val action = ActionCodecs.decodeAction(o.objOrNull("a")) ?: return null
        return TaskStep(
            id = o.strOrNull("id") ?: "s?",
            title = o.strOrNull("title") ?: action.describe(),
            action = action,
            precondition = ActionCodecs.decodeExpectation(o.objOrNull("pre")),
            postcondition = ActionCodecs.decodeExpectation(o.objOrNull("post")),
            timeoutMs = o.longOr("timeout", 8_000L).coerceIn(200L, 120_000L),
            retry = decodeRetry(o.objOrNull("retry")),
            riskOverride = o.enumOrNull<RiskLevel>("risk"),
            status = o.enumOrNull<StepStatus>("status") ?: StepStatus.PENDING,
            attempts = o.intOr("attempts", 0),
            note = o.strOrNull("note"),
            lastOutcome = decodeOutcome(o.objOrNull("outcome"))
        )
    }

    fun encodeSteps(list: List<TaskStep>): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(encodeStep(it)) }
        return a
    }

    fun decodeSteps(a: JSONArray?): List<TaskStep> = a?.objects()?.mapNotNull { decodeStep(it) } ?: emptyList()

    fun encodeRecord(r: StepRecord): JSONObject = Json.obj(
        "step" to r.stepId,
        "title" to r.title,
        "action" to r.actionLabel,
        "outcome" to r.outcome,
        "status" to r.status.name,
        "risk" to r.risk.name,
        "tier" to r.tier?.name,
        "at" to r.startedAtMs,
        "ms" to r.durationMs,
        "sig" to r.signature,
        "msg" to r.message
    )

    fun decodeRecord(o: JSONObject?): StepRecord? {
        if (o == null) return null
        return StepRecord(
            stepId = o.strOrNull("step") ?: "?",
            title = o.strOrNull("title") ?: "",
            actionLabel = o.strOrNull("action") ?: "",
            outcome = o.strOrNull("outcome") ?: "",
            status = o.enumOrNull<OutcomeStatus>("status") ?: OutcomeStatus.FAILURE,
            risk = o.enumOrNull<RiskLevel>("risk") ?: RiskLevel.LOW,
            tier = o.enumOrNull<MatchTier>("tier"),
            startedAtMs = o.longOr("at", 0L),
            durationMs = o.longOr("ms", 0L),
            signature = o.strOrNull("sig"),
            message = o.strOrNull("msg")
        )
    }

    // ----------------------------------------------------------------- task

    fun encodeTask(task: AgentTask): JSONObject = JSONObject()
        .set("v", ActionCodecs.FORMAT_VERSION)
        .set("id", task.id)
        .set("goal", task.goal)
        .set("source", task.source.name)
        .set("created", task.createdAtMs)
        .set("constraints", encodeConstraints(task.constraints))
        .set("budget", encodeBudget(task.budget))
        .set("steps", encodeSteps(task.steps))
        .set("context", JSONObject().also { ctx -> task.context.forEach { (k, v) -> ctx.set(k, v) } })
        .set("history", JSONArray().also { arr -> task.history.forEach { arr.put(encodeRecord(it)) } })
        .set("current", task.currentStep)
        .set("state", task.state.name)
        .set("risk", task.riskLevel.name)
        .set("started", task.startedAtMs)
        .set("finished", task.finishedAtMs)
        .set("updated", task.updatedAtMs)
        .set("actions", task.actionsUsed)
        .set("retries", task.retriesUsed)
        .set("wf", task.workflowId)
        .set("result", task.resultMessage)
        .set("stop", task.stopReason)

    fun decodeTask(o: JSONObject?): AgentTask? {
        if (o == null) return null
        val id = o.strOrNull("id") ?: return null
        val created = o.longOr("created", 0L)
        return AgentTask(
            id = id,
            goal = o.strOrNull("goal") ?: "",
            source = o.enumOrNull<TaskSource>("source") ?: TaskSource.USER_TEXT,
            createdAtMs = created,
            constraints = decodeConstraints(o.objOrNull("constraints")),
            budget = decodeBudget(o.objOrNull("budget")),
            steps = decodeSteps(o.arrOrNull("steps")).toMutableList(),
            context = o.strMap("context").toMutableMap(),
            history = o.arrOrNull("history")?.objects()?.mapNotNull { decodeRecord(it) }?.toMutableList()
                ?: mutableListOf(),
            currentStep = o.intOr("current", 0).coerceAtLeast(0),
            state = o.enumOrNull<TaskState>("state") ?: TaskState.CREATED,
            riskLevel = o.enumOrNull<RiskLevel>("risk") ?: RiskLevel.LOW,
            startedAtMs = if (o.has("started") && !o.isNull("started")) o.optLong("started") else null,
            finishedAtMs = if (o.has("finished") && !o.isNull("finished")) o.optLong("finished") else null,
            updatedAtMs = o.longOr("updated", created),
            actionsUsed = o.intOr("actions", 0),
            retriesUsed = o.intOr("retries", 0),
            workflowId = o.strOrNull("wf"),
            resultMessage = o.strOrNull("result"),
            stopReason = o.strOrNull("stop")
        )
    }

    // ------------------------------------------------------------- workflow

    fun encodeWorkflow(w: Workflow): JSONObject = JSONObject()
        .set("v", ActionCodecs.FORMAT_VERSION)
        .set("id", w.id)
        .set("name", w.name)
        .set("steps", encodeSteps(w.steps))
        .set("created", w.createdAtMs)
        .set("updated", w.updatedAtMs)
        .set("source", w.source.name)
        .set("lastRun", w.lastRunAtMs)
        .set("runs", w.runCount)
        .set("ok", w.successCount)
        .setStrings("packages", w.packages)
        .set("notes", w.notes)
        .setStrings("tags", w.tags)
        .set("risk", w.riskLevel.name)

    fun decodeWorkflow(o: JSONObject?): Workflow? {
        if (o == null) return null
        val id = o.strOrNull("id") ?: return null
        val created = o.longOr("created", 0L)
        return Workflow(
            id = id,
            name = o.strOrNull("name") ?: "Untitled workflow",
            steps = decodeSteps(o.arrOrNull("steps")),
            createdAtMs = created,
            updatedAtMs = o.longOr("updated", created),
            source = o.enumOrNull<WorkflowSource>("source") ?: WorkflowSource.RECORDED,
            lastRunAtMs = if (o.has("lastRun") && !o.isNull("lastRun")) o.optLong("lastRun") else null,
            runCount = o.intOr("runs", 0),
            successCount = o.intOr("ok", 0),
            packages = o.strList("packages"),
            notes = o.strOrNull("notes"),
            tags = o.strList("tags"),
            riskLevel = o.enumOrNull<RiskLevel>("risk") ?: RiskLevel.LOW
        )
    }

    fun encodeWorkflowList(list: List<Workflow>): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(encodeWorkflow(it)) }
        return a
    }

    fun decodeWorkflowList(a: JSONArray?): List<Workflow> =
        a?.objects()?.mapNotNull { decodeWorkflow(it) } ?: emptyList()

    // ------------------------------------------------------------- messages

    fun encodeMessage(m: ChatMessage): JSONObject = Json.obj(
        "id" to m.id,
        "role" to m.role.name,
        "text" to m.text,
        "at" to m.atMs,
        "task" to m.taskId,
        "p" to m.progressAtSend?.toDouble()
    )

    fun decodeMessage(o: JSONObject?): ChatMessage? {
        if (o == null) return null
        return ChatMessage(
            id = o.strOrNull("id") ?: return null,
            role = o.enumOrNull<Role>("role") ?: Role.AGENT,
            text = o.strOrNull("text") ?: "",
            atMs = o.longOr("at", 0L),
            taskId = o.strOrNull("task"),
            progressAtSend = if (o.has("p") && !o.isNull("p")) o.optDouble("p").toFloat() else null
        )
    }

    fun encodeMessageList(list: List<ChatMessage>): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(encodeMessage(it)) }
        return a
    }

    fun decodeMessageList(a: JSONArray?): List<ChatMessage> =
        a?.objects()?.mapNotNull { decodeMessage(it) } ?: emptyList()
}
