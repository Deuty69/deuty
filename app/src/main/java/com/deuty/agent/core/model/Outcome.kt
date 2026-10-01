package com.deuty.agent.core.model

enum class OutcomeStatus {
    SUCCESS,
    /** The action ran but the postcondition was not met. */
    UNVERIFIED,
    FAILURE,
    /** Refused before acting (risk gate, secure screen, forbidden package). */
    BLOCKED,
    /** Handed to the human. */
    NEEDS_USER,
    SKIPPED,
    CANCELLED
}

/**
 * Why something did not work. Recovery strategies are selected from this, not
 * from free-form error strings, so recovery stays testable.
 */
enum class FailureReason {
    ELEMENT_NOT_FOUND,
    AMBIGUOUS_MATCH,
    PRECONDITION_FAILED,
    POSTCONDITION_FAILED,
    TIMEOUT,
    GESTURE_FAILED,
    TEXT_INPUT_FAILED,
    KEYBOARD_DID_NOT_APPEAR,
    PERMISSION_DIALOG,
    SECURE_SCREEN,
    SERVICE_UNAVAILABLE,
    APP_NOT_FOUND,
    APP_CRASHED,
    NETWORK_UNAVAILABLE,
    SCREEN_UNREADABLE,
    RISK_BLOCKED,
    LOOP_DETECTED,
    BUDGET_ACTIONS,
    BUDGET_TIME,
    BUDGET_RETRIES,
    USER_CANCELLED,
    USER_DENIED,
    NOT_IMPLEMENTED,
    UNKNOWN
}

/**
 * Flat result of one action attempt.
 *
 * Flat (rather than a sealed hierarchy) because it is persisted verbatim into
 * the task history and rendered in the inspector; a value type keeps that
 * boring and cheap.
 */
data class ActionOutcome(
    val status: OutcomeStatus,
    val message: String,
    val reason: FailureReason? = null,
    val verified: Boolean = false,
    val tier: MatchTier? = null,
    val startedAtMs: Long = 0L,
    val durationMs: Long = 0L,
    val signatureBefore: String? = null,
    val signatureAfter: String? = null,
    val attempts: Int = 1,
    /** Present when the outcome requires a human decision. */
    val ask: AskUserRequest? = null,
    /** Structured payload for steps that produce data (ReadScreen, FindElement). */
    val data: String? = null
) {
    val succeeded: Boolean get() = status == OutcomeStatus.SUCCESS
    val needsUser: Boolean get() = status == OutcomeStatus.NEEDS_USER
    val blocked: Boolean get() = status == OutcomeStatus.BLOCKED

    val shortLabel: String
        get() = when (status) {
            OutcomeStatus.SUCCESS -> if (verified) "SUCCESS (verified)" else "SUCCESS"
            OutcomeStatus.UNVERIFIED -> "UNVERIFIED"
            OutcomeStatus.FAILURE -> "FAILURE: ${reason?.name ?: "UNKNOWN"}"
            OutcomeStatus.BLOCKED -> "BLOCKED: ${reason?.name ?: "POLICY"}"
            OutcomeStatus.NEEDS_USER -> "WAITING FOR USER"
            OutcomeStatus.SKIPPED -> "SKIPPED"
            OutcomeStatus.CANCELLED -> "CANCELLED"
        }

    companion object {
        fun success(message: String, verified: Boolean = true, tier: MatchTier? = null, atMs: Long = 0L, durationMs: Long = 0L) =
            ActionOutcome(OutcomeStatus.SUCCESS, message, verified = verified, tier = tier, startedAtMs = atMs, durationMs = durationMs)

        fun failure(message: String, reason: FailureReason, tier: MatchTier? = null, atMs: Long = 0L, durationMs: Long = 0L) =
            ActionOutcome(OutcomeStatus.FAILURE, message, reason = reason, tier = tier, startedAtMs = atMs, durationMs = durationMs)

        fun blocked(message: String, reason: FailureReason = FailureReason.RISK_BLOCKED, ask: AskUserRequest? = null) =
            ActionOutcome(OutcomeStatus.BLOCKED, message, reason = reason, ask = ask)

        fun needsUser(message: String, ask: AskUserRequest, reason: FailureReason? = null) =
            ActionOutcome(OutcomeStatus.NEEDS_USER, message, reason = reason, ask = ask)

        fun unverified(message: String, atMs: Long = 0L, durationMs: Long = 0L) =
            ActionOutcome(OutcomeStatus.UNVERIFIED, message, startedAtMs = atMs, durationMs = durationMs)
    }
}

/** A question the agent needs answered before it can continue. */
data class AskUserRequest(
    val id: String,
    val question: String,
    val options: List<String> = emptyList(),
    val allowFreeText: Boolean = false,
    val risk: RiskLevel = RiskLevel.MEDIUM,
    val reason: FailureReason? = null,
    val stepId: String? = null,
    val taskId: String? = null,
    val createdAtMs: Long = 0L,
    /** True when answering "no" should stop the task rather than skip the step. */
    val denyStopsTask: Boolean = false
) {
    companion object {
        val APPROVE = "Do it"
        val DENY = "Don't"
        val SKIP = "Skip this step"
        val STOP = "Stop the task"

        fun confirmRisk(
            id: String,
            question: String,
            risk: RiskLevel,
            stepId: String? = null,
            taskId: String? = null,
            atMs: Long = 0L
        ) = AskUserRequest(
            id = id,
            question = question,
            options = listOf(APPROVE, DENY, STOP),
            allowFreeText = false,
            risk = risk,
            reason = FailureReason.RISK_BLOCKED,
            stepId = stepId,
            taskId = taskId,
            createdAtMs = atMs,
            denyStopsTask = risk.atLeast(RiskLevel.HIGH)
        )

        fun clarify(
            id: String,
            question: String,
            options: List<String> = emptyList(),
            stepId: String? = null,
            taskId: String? = null,
            atMs: Long = 0L
        ) = AskUserRequest(
            id = id,
            question = question,
            options = options,
            allowFreeText = true,
            risk = RiskLevel.LOW,
            stepId = stepId,
            taskId = taskId,
            createdAtMs = atMs
        )
    }
}

enum class UserChoice { APPROVE, DENY, SKIP, STOP, RETRY, REPLY }

data class UserDecision(
    val requestId: String,
    val choice: UserChoice,
    val text: String? = null,
    val atMs: Long = 0L
) {
    companion object {
        /** Maps a tapped option label to a choice. */
        fun fromLabel(requestId: String, label: String, atMs: Long): UserDecision {
            val normalized = label.trim().lowercase()
            val choice = when {
                normalized in setOf("do it", "yes", "ok", "okay", "continue", "approve", "confirm") -> UserChoice.APPROVE
                normalized in setOf("don't", "dont", "no", "cancel", "deny") -> UserChoice.DENY
                normalized.startsWith("skip") -> UserChoice.SKIP
                normalized.startsWith("retry") -> UserChoice.RETRY
                normalized.startsWith("stop") -> UserChoice.STOP
                else -> UserChoice.REPLY
            }
            return UserDecision(requestId, choice, if (choice == UserChoice.REPLY) label else null, atMs)
        }
    }
}
