package com.deuty.agent.agent.planner

import com.deuty.agent.core.util.AgentLog

/**
 * Extension point: an on-device model (requirement 12, roadmap V5).
 *
 * This is intentionally a stub. Shipping a half-working local model would mean
 * shipping a ~1-4 GB download and a runtime that competes for RAM with the very
 * apps the agent is trying to drive - on a 4 GB phone that is a bad trade.
 *
 * To implement it:
 *  1. Add an inference runtime (MediaPipe LLM Inference API, llama.cpp via JNI,
 *     or ONNX Runtime Mobile) as a *separate* Gradle module so the core app does
 *     not carry the dependency.
 *  2. Prompt for a [PlanResult.Plan]: the model must emit the JSON step schema in
 *     docs/ARCHITECTURE.md, which is exactly what [com.deuty.agent.core.json.ActionCodecs]
 *     already parses. Validate with ActionCodecs.decodeAction() and drop any step
 *     that does not decode - a model that invents an action type gets ignored
 *     rather than trusted.
 *  3. Keep [RuleBasedPlanner] as the fallback: return NotCapable whenever the
 *     model output does not validate, and the provider will fall through.
 *  4. Never feed raw screen text to the model without [com.deuty.agent.core.util.Redactor].
 */
class LocalModelPlanner(
    private val log: AgentLog,
    private val modelPath: String? = null
) : Planner {

    override val id = "local"
    override val displayName = "On-device model"
    override val available: Boolean get() = false
    override val requiresNetwork = false
    override val costRank = 20

    override suspend fun plan(request: PlanRequest): PlanResult {
        log.d(id, "local planner asked for \"${request.instruction.take(40)}\" but no model is installed")
        return PlanResult.NotCapable(
            "no on-device model is installed (set modelPath and implement LocalModelPlanner)", id
        )
    }
}

/**
 * Extension point: a user-configured remote model (roadmap V3+).
 *
 * Deliberately inert in this build, and it cannot be turned on by accident:
 *   - The app declares no INTERNET permission, so any HTTP call here would throw
 *     SecurityException even if [available] returned true.
 *   - Screen content is the most sensitive data on the device. Sending it to a
 *     third party must be an explicit, per-task, user-visible decision, not a
 *     default that a settings toggle forgot to mention.
 *
 * To implement it: add INTERNET to the manifest, require the user to enter an
 * endpoint + key, add a "send screen summary to the model" confirmation to the
 * task's risk gate, redact everything with [com.deuty.agent.core.util.Redactor],
 * and cache nothing.
 */
class RemotePlanner(
    private val log: AgentLog,
    private val endpoint: String? = null,
    private val apiKey: String? = null,
    private val model: String? = null
) : Planner {

    override val id = "remote"
    override val displayName = "Remote model"

    override val available: Boolean
        get() = false // no INTERNET permission in this build; see the class doc

    override val requiresNetwork = true
    override val costRank = 50

    override suspend fun plan(request: PlanRequest): PlanResult {
        log.w(
            id, "remote planner refused: this build has no INTERNET permission and no endpoint configured"
        )
        return PlanResult.Rejected(
            "Remote planning is disabled in this build. It needs the INTERNET permission, a user-configured " +
                "endpoint, and explicit consent to send screen content off-device.",
            id
        )
    }
}

/**
 * Never plans anything. Useful as an explicit "automation off" mode: workflows
 * and direct commands still run, free-form sentences are politely refused.
 */
class NoPlanner : Planner {
    override val id = "none"
    override val displayName = "No planner"
    override val available = true
    override val requiresNetwork = false
    override val costRank = 100

    override suspend fun plan(request: PlanRequest): PlanResult = PlanResult.NotCapable(
        "planning is disabled; run a saved workflow or use direct commands", id
    )
}
