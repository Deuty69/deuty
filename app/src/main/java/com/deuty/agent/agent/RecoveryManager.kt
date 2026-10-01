package com.deuty.agent.agent

import com.deuty.agent.agent.executor.AgentAction
import com.deuty.agent.agent.executor.Back
import com.deuty.agent.agent.executor.Scroll
import com.deuty.agent.agent.executor.Tap
import com.deuty.agent.agent.executor.Wait
import com.deuty.agent.agent.executor.Relaunch
import com.deuty.agent.agent.port.ExecContext
import com.deuty.agent.core.model.ActionOutcome
import com.deuty.agent.core.model.AskUserRequest
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.FailureReason
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.TaskStep
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.Ids

/**
 * Error recovery as a first-class system (requirement 19).
 *
 * Input: a typed failure + the screen we are actually on.
 * Output: a [RecoveryPlan] - either a bounded set of corrective actions followed
 * by a retry, or a question for the user, or "give up".
 *
 * The design constraint that shapes everything here: a recovery plan may never
 * be more dangerous than the step it is recovering. Recovery therefore never
 * taps something it has not seen, never dismisses a system dialog, and every
 * strategy has an attempt budget attached by the caller.
 */
class RecoveryManager(
    private val clock: Clock,
    private val log: AgentLog
) {

    enum class Strategy {
        /** Just look again; the screen was probably still animating. */
        RE_OBSERVE,
        /** The target is probably below the fold. */
        SCROLL_THEN_RETRY,
        /** Re-read the screen and accept a weaker locator (contains, description). */
        SWITCH_LOCATOR,
        /** Something is covering the app; press Back once and look again. */
        DISMISS_BY_BACK,
        /** The app is gone or crashed; relaunch it. */
        RELAUNCH_APP,
        /** Slow network/render; wait, then look again. */
        WAIT_AND_RETRY,
        /** Tree is empty/opaque: try Level 2/3 (screenshot + OCR) if enabled. */
        ESCALATE_TO_VISION,
        /** Only a human can resolve this. */
        ASK_USER,
        GIVE_UP
    }

    data class RecoveryPlan(
        val strategy: Strategy,
        val message: String,
        val preActions: List<AgentAction> = emptyList(),
        val retryStep: Boolean = true,
        val ask: AskUserRequest? = null,
        val relaxedQuery: ElementQuery? = null
    ) {
        val needsUser: Boolean get() = strategy == Strategy.ASK_USER
    }

    fun planFor(
        outcome: ActionOutcome,
        step: TaskStep,
        screen: ScreenSnapshot,
        attempt: Int,
        ctx: ExecContext,
        appPackage: String?
    ): RecoveryPlan {
        val diagnosis = ScreenDiagnostics.diagnose(screen)

        // A diagnosed blocker always wins over the generic failure reason: the
        // failure is a symptom, the blocker is the cause.
        if (diagnosis.isBlocking && diagnosis.blocker != ScreenDiagnostics.Blocker.NONE) {
            log.w(TAG, "blocker detected: ${diagnosis.blocker} (${diagnosis.evidence})")
            return when (diagnosis.blocker) {
                ScreenDiagnostics.Blocker.PERMISSION_DIALOG,
                ScreenDiagnostics.Blocker.PAYMENT_SHEET,
                ScreenDiagnostics.Blocker.CAPTCHA,
                ScreenDiagnostics.Blocker.SECURE_SCREEN,
                ScreenDiagnostics.Blocker.STORAGE_FULL -> askUser(
                    diagnosis.question ?: "I need you to handle this screen.",
                    diagnosis.blocker.name,
                    ctx,
                    stopOnDeny = true
                )

                ScreenDiagnostics.Blocker.LOGIN_REQUIRED -> askUser(
                    diagnosis.question ?: "You're not signed in. Sign in and I'll continue?",
                    diagnosis.blocker.name,
                    ctx
                )

                ScreenDiagnostics.Blocker.APP_NOT_INSTALLED -> askUser(
                    diagnosis.question ?: "That app isn't installed.",
                    diagnosis.blocker.name,
                    ctx
                )

                ScreenDiagnostics.Blocker.CRASH_DIALOG -> RecoveryPlan(
                    strategy = Strategy.RELAUNCH_APP,
                    message = "the app crashed; dismissing and relaunching",
                    preActions = listOfNotNull(dismissCrashDialog(screen), appPackage?.let { Relaunch(it) }),
                    retryStep = attempt < 2
                )

                ScreenDiagnostics.Blocker.NETWORK_ERROR -> RecoveryPlan(
                    strategy = Strategy.WAIT_AND_RETRY,
                    message = "network problem on screen; waiting before retrying",
                    preActions = listOf(Wait(1_500L)),
                    retryStep = attempt < 3
                )

                ScreenDiagnostics.Blocker.UPDATE_PROMPT -> askUser(
                    "This app wants to update. Update it, skip it, or stop?",
                    diagnosis.blocker.name,
                    ctx,
                    options = listOf("Skip it", "Stop the task")
                )

                ScreenDiagnostics.Blocker.COOKIE_CONSENT -> RecoveryPlan(
                    strategy = Strategy.DISMISS_BY_BACK,
                    message = "cookie banner; trying the on-screen dismiss first",
                    preActions = listOf(dismissBanner(screen) ?: Wait(300L)),
                    retryStep = true
                )

                ScreenDiagnostics.Blocker.TUTORIAL -> RecoveryPlan(
                    strategy = Strategy.DISMISS_BY_BACK,
                    message = "tutorial/onboarding screen in the way",
                    preActions = listOf(dismissBanner(screen) ?: Back),
                    retryStep = true
                )

                ScreenDiagnostics.Blocker.BATTERY_OPTIMISATION,
                ScreenDiagnostics.Blocker.RATE_LIMITED -> RecoveryPlan(
                    strategy = Strategy.WAIT_AND_RETRY,
                    message = diagnosis.blocker.name.lowercase() + "; waiting",
                    preActions = listOf(Wait(2_000L)),
                    retryStep = attempt < 2
                )

                ScreenDiagnostics.Blocker.NONE -> RecoveryPlan(Strategy.RE_OBSERVE, "re-observing")
            }
        }

        return when (outcome.reason) {
            FailureReason.ELEMENT_NOT_FOUND -> when (attempt) {
                0 -> RecoveryPlan(
                    strategy = Strategy.RE_OBSERVE,
                    message = "element not found; re-reading the screen (it may still have been loading)"
                )
                1 -> RecoveryPlan(
                    strategy = Strategy.SCROLL_THEN_RETRY,
                    message = "element not found; scrolling once in case it is below the fold",
                    preActions = listOf(Scroll(direction = Direction.DOWN, times = 1)),
                    relaxedQuery = relax(step.action.targetQuery())
                )
                2 -> RecoveryPlan(
                    strategy = Strategy.SWITCH_LOCATOR,
                    message = "element not found after scrolling; trying a looser locator",
                    relaxedQuery = relax(step.action.targetQuery())
                )
                else -> askUser(
                    "I can't find ${step.action.targetQuery()?.primaryLocator() ?: "what I need"} on this screen. " +
                        "Point me at it, or should I stop?",
                    "element_not_found",
                    ctx
                )
            }

            FailureReason.AMBIGUOUS_MATCH -> askUser(
                "Several things match ${step.action.targetQuery()?.describe()}. Which one?",
                "ambiguous",
                ctx
            )

            FailureReason.POSTCONDITION_FAILED -> when (attempt) {
                0 -> RecoveryPlan(
                    strategy = Strategy.RE_OBSERVE,
                    message = "the action ran but nothing changed; re-reading and retrying once"
                )
                1 -> RecoveryPlan(
                    strategy = Strategy.WAIT_AND_RETRY,
                    message = "still no change; the screen may be slow, waiting then retrying",
                    preActions = listOf(Wait(900L))
                )
                else -> askUser(
                    "\"${step.title}\" doesn't seem to be doing anything. ${outcome.message}. Keep trying or stop?",
                    "postcondition_failed",
                    ctx
                )
            }

            FailureReason.TIMEOUT -> RecoveryPlan(
                strategy = Strategy.WAIT_AND_RETRY,
                message = "timed out; waiting for the UI to settle",
                preActions = listOf(Wait(700L)),
                retryStep = attempt < 2
            )

            FailureReason.GESTURE_FAILED -> when (attempt) {
                0 -> RecoveryPlan(Strategy.RE_OBSERVE, "gesture was rejected; re-reading the screen")
                else -> askUser(
                    "Android rejected my gesture on this screen (some apps block injected input). Do it manually?",
                    "gesture_failed",
                    ctx,
                    stopOnDeny = true
                )
            }

            FailureReason.TEXT_INPUT_FAILED,
            FailureReason.KEYBOARD_DID_NOT_APPEAR -> RecoveryPlan(
                strategy = Strategy.RE_OBSERVE,
                message = "text input failed; tapping the field again to raise the keyboard",
                preActions = listOfNotNull(step.action.targetQuery()?.let { Tap(it) }, Wait(600L)),
                retryStep = attempt < 2
            )

            FailureReason.APP_NOT_FOUND -> askUser(
                "I couldn't find that app. Is it installed under a different name?",
                "app_not_found",
                ctx
            )

            FailureReason.APP_CRASHED -> RecoveryPlan(
                strategy = Strategy.RELAUNCH_APP,
                message = "app disappeared; relaunching",
                preActions = listOfNotNull(appPackage?.let { Relaunch(it) }),
                retryStep = attempt < 2
            )

            FailureReason.SCREEN_UNREADABLE -> if (ctx.allowScreenshots) {
                RecoveryPlan(Strategy.ESCALATE_TO_VISION, "tree unreadable; escalating to screenshot/OCR")
            } else {
                askUser(
                    "I can't read this screen (no accessibility data, and screen capture is off). " +
                        "Enable capture in settings, or tell me what's on screen.",
                    "screen_unreadable",
                    ctx
                )
            }

            FailureReason.SECURE_SCREEN,
            FailureReason.PERMISSION_DIALOG,
            FailureReason.RISK_BLOCKED -> askUser(
                outcome.message.ifBlank { "I'm not allowed to do this part." },
                "blocked",
                ctx,
                stopOnDeny = true
            )

            FailureReason.NETWORK_UNAVAILABLE -> RecoveryPlan(
                strategy = Strategy.WAIT_AND_RETRY,
                message = "no network; waiting and retrying",
                preActions = listOf(Wait(2_000L)),
                retryStep = attempt < 3
            )

            FailureReason.LOOP_DETECTED,
            FailureReason.BUDGET_ACTIONS,
            FailureReason.BUDGET_TIME,
            FailureReason.BUDGET_RETRIES -> RecoveryPlan(
                strategy = Strategy.GIVE_UP,
                message = outcome.message,
                retryStep = false
            )

            FailureReason.USER_CANCELLED,
            FailureReason.USER_DENIED -> RecoveryPlan(
                strategy = Strategy.GIVE_UP,
                message = outcome.message,
                retryStep = false
            )

            FailureReason.SERVICE_UNAVAILABLE -> RecoveryPlan(
                strategy = Strategy.GIVE_UP,
                message = "the accessibility service went away; enable it again to continue",
                retryStep = false
            )

            FailureReason.PRECONDITION_FAILED -> when (attempt) {
                0 -> RecoveryPlan(Strategy.RE_OBSERVE, "precondition failed; re-reading the screen")
                1 -> RecoveryPlan(
                    strategy = Strategy.SCROLL_THEN_RETRY,
                    message = "precondition still failing; scrolling to find it",
                    preActions = listOf(Scroll(direction = Direction.DOWN, times = 1))
                )
                else -> askUser("The screen isn't in the state this step needs (${outcome.message}). What now?", "precondition", ctx)
            }

            FailureReason.NOT_IMPLEMENTED,
            FailureReason.UNKNOWN,
            null -> RecoveryPlan(
                strategy = if (attempt == 0) Strategy.RE_OBSERVE else Strategy.GIVE_UP,
                message = outcome.message.ifBlank { "unknown failure" },
                retryStep = attempt == 0
            )
        }
    }

    /**
     * Weaken a locator so a retry has a chance: drop the exact-text requirement
     * in favour of "contains", keep ids (they are the most reliable thing we
     * have), and drop recorded bounds only as a last resort.
     */
    private fun relax(query: ElementQuery?): ElementQuery? {
        if (query == null) return null
        val text = query.text
        return when {
            !text.isNullOrBlank() && text.length > 3 -> query.copy(
                text = null,
                textContains = text,
                label = query.label?.let { "$it (loosened)" } ?: "contains \"$text\""
            )
            query.bounds != null -> query.copy(bounds = null)
            query.index > 0 -> query.copy(index = 0)
            else -> null
        }
    }

    private fun dismissCrashDialog(screen: ScreenSnapshot): AgentAction? {
        val candidate = screen.nodes.firstOrNull {
            it.isClickable && (
                it.text?.contains("Close", true) == true ||
                    it.text?.contains("OK", true) == true ||
                    it.text?.contains("Wait", true) == true
                )
        }
        return candidate?.let { Tap(ElementQuery.byAnyLabel(it.text ?: "Close")) } ?: Back
    }

    /**
     * Dismiss a banner *only* using a control that is plainly a dismiss control.
     * Guessing here is how an agent deletes something.
     */
    private fun dismissBanner(screen: ScreenSnapshot): AgentAction? {
        val dismissLabels = listOf("Accept all", "Accept", "Got it", "Skip", "Not now", "No thanks", "Close", "Dismiss")
        val node = screen.nodes.firstOrNull { n ->
            n.isClickable && dismissLabels.any { label ->
                n.text?.equals(label, ignoreCase = true) == true ||
                    n.contentDescription?.equals(label, ignoreCase = true) == true
            }
        } ?: return null
        return Tap(node.let {
            ElementQuery(
                text = it.text,
                contentDescription = it.contentDescription,
                viewId = it.viewIdResourceName,
                label = "dismiss \"${(it.text ?: it.contentDescription)?.take(20)}\""
            )
        })
    }

    private fun askUser(
        question: String,
        kind: String,
        ctx: ExecContext,
        stopOnDeny: Boolean = false,
        options: List<String> = listOf("I've handled it, continue", "Stop the task")
    ): RecoveryPlan {
        val ask = AskUserRequest(
            id = Ids.request(),
            question = question,
            options = options,
            allowFreeText = true,
            risk = com.deuty.agent.core.model.RiskLevel.MEDIUM,
            reason = FailureReason.UNKNOWN,
            stepId = ctx.stepId,
            taskId = ctx.taskId,
            createdAtMs = clock.nowMs(),
            denyStopsTask = stopOnDeny
        )
        return RecoveryPlan(
            strategy = Strategy.ASK_USER,
            message = "asking the user ($kind)",
            ask = ask,
            retryStep = false
        )
    }

    companion object {
        private const val TAG = "recovery"
    }
}
