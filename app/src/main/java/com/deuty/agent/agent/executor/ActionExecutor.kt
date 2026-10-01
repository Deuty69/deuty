package com.deuty.agent.agent.executor

import com.deuty.agent.agent.ElementResolver
import com.deuty.agent.agent.port.DeviceSession
import com.deuty.agent.agent.port.ExecContext
import com.deuty.agent.agent.port.GlobalAction
import com.deuty.agent.core.model.ActionOutcome
import com.deuty.agent.core.model.AskUserRequest
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.FailureReason
import com.deuty.agent.core.model.MatchedElement
import com.deuty.agent.core.model.OutcomeStatus
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Redactor

/**
 * Turns one [AgentAction] into engine calls.
 *
 * Responsibilities, and deliberately *not* responsibilities:
 *   DOES    resolve the target, choose node-action vs gesture, talk to the
 *           engine, translate engine failures into typed [FailureReason]s.
 *   DOESN'T verify anything (that is [com.deuty.agent.agent.ActionRunner]),
 *           decide risk (RiskManager), retry (TaskRunner), or touch the
 *           framework directly.
 *
 * Every branch returns an [ActionOutcome]; it never throws. An executor that
 * throws inside an accessibility-driven loop is how an agent dies mid-task with
 * the UI stuck on "Working".
 */
class ActionExecutor(
    private val resolver: ElementResolver,
    private val clock: Clock,
    private val log: AgentLog
) {

    suspend fun execute(
        action: AgentAction,
        screen: ScreenSnapshot,
        session: DeviceSession,
        ctx: ExecContext
    ): ActionOutcome {
        val started = clock.elapsedRealtimeMs()
        val engine = session.interaction
        if (!session.available || !engine.available) {
            return ActionOutcome.failure(
                "no device session attached (accessibility service off?)",
                FailureReason.SERVICE_UNAVAILABLE
            )
        }

        return try {
            when (action) {
                is Tap -> tap(action, screen, session, ctx, started)
                is LongPress -> longPress(action, screen, session, started)
                is Swipe -> swipe(action, screen, session, started)
                is Scroll -> scroll(action, screen, session, ctx, started)
                is Type -> type(action, screen, session, ctx, started)
                is Paste -> paste(action, screen, session, ctx, started)
                Back -> global(GlobalAction.BACK, "back", started) { engine.globalAction(it) }
                Home -> global(GlobalAction.HOME, "home", started) { engine.globalAction(it) }
                Recents -> global(GlobalAction.RECENTS, "recents", started) { engine.globalAction(it) }
                is LaunchApp -> launchApp(action, session, started)
                is Relaunch -> relaunch(action, session, started)
                is Wait -> wait(action, session, started)
                is ReadScreen -> readScreen(action, screen, ctx, started)
                is Screenshot -> screenshot(action, session, ctx, started)
                is FindElement -> findElement(action, screen, started)
                is Report -> ActionOutcome.success(action.message, verified = true, atMs = started)
                is AskUser -> askUser(action, ctx)
                is RunWorkflow -> ActionOutcome.failure(
                    "run_workflow must be expanded into steps at plan time",
                    FailureReason.NOT_IMPLEMENTED
                )
                is Conditional, is Repeat -> ActionOutcome.failure(
                    "composite actions are executed by the runner, not the executor",
                    FailureReason.NOT_IMPLEMENTED
                )
            }
        } catch (t: Throwable) {
            log.e(TAG, "executor crashed on ${action.describe()}", t)
            ActionOutcome.failure(
                "executor error: ${t.javaClass.simpleName}",
                FailureReason.UNKNOWN,
                atMs = started,
                durationMs = clock.elapsedRealtimeMs() - started
            )
        }
    }

    // ------------------------------------------------------------------- tap

    private suspend fun tap(
        action: Tap,
        screen: ScreenSnapshot,
        session: DeviceSession,
        ctx: ExecContext,
        started: Long
    ): ActionOutcome {
        val resolution = resolver.resolve(screen, action.target)
        val match = resolution.best
        if (match == null) {
            return notFound(action.target.describe(), resolution.diagnostics, started)
        }
        var last: com.deuty.agent.agent.port.EngineResult? = null
        repeat(action.times.coerceIn(1, 5)) { i ->
            last = session.interaction.click(match)
            if (last?.ok != true) return@repeat
            if (i < action.times - 1) clock.sleep(action.intervalMs)
        }
        return finish(last, "tap ${action.target.describe()}", resolution.tier, match, started, screen)
    }

    private suspend fun longPress(
        action: LongPress,
        screen: ScreenSnapshot,
        session: DeviceSession,
        started: Long
    ): ActionOutcome {
        val resolution = resolver.resolve(screen, action.target)
        val match = resolution.best ?: return notFound(action.target.describe(), resolution.diagnostics, started)
        val result = session.interaction.longClick(match, action.durationMs)
        return finish(result, "long press ${action.target.describe()}", resolution.tier, match, started, screen)
    }

    private suspend fun swipe(
        action: Swipe,
        screen: ScreenSnapshot,
        session: DeviceSession,
        started: Long
    ): ActionOutcome {
        val origin = action.startPoint?.let { p ->
            MatchedElement(null, p, com.deuty.agent.core.model.MatchTier.COORDINATES, 1f, "explicit start point")
        }
        val result = session.interaction.swipe(action.direction, origin, action.distanceRatio, action.durationMs)
        return finish(result, "swipe ${action.direction.name.lowercase()}", null, origin, started, screen)
    }

    private suspend fun scroll(
        action: Scroll,
        screen: ScreenSnapshot,
        session: DeviceSession,
        ctx: ExecContext,
        started: Long
    ): ActionOutcome {
        val containerMatch = action.container?.let { resolver.resolve(screen, it).best }
            ?: screen.scrollableNodes.firstOrNull()?.let {
                MatchedElement(it, com.deuty.agent.core.model.Point(it.bounds.centerX, it.bounds.centerY),
                    com.deuty.agent.core.model.MatchTier.SEMANTIC, 0.5f, "first scrollable container")
            }

        // "scroll until X is visible": bounded loop, re-observing each round.
        // This is the single most common real-world need (long lists) and the
        // bound is what keeps it from becoming an infinite scroll.
        action.untilVisible?.let { wanted ->
            val rounds = action.maxTimes.coerceIn(1, 30)
            for (round in 1..rounds) {
                val current = session.screens.observe("scroll-until round $round")
                if (resolver.resolve(current, wanted).best != null) {
                    return ActionOutcome.success(
                        "found ${wanted.describe()} after $round scroll(s)",
                        verified = true, atMs = started, durationMs = clock.elapsedRealtimeMs() - started
                    )
                }
                if (round == rounds) break
                val r = session.interaction.scroll(action.direction, containerMatch, 1)
                if (!r.ok) {
                    return ActionOutcome.failure(r.message, r.failure ?: FailureReason.GESTURE_FAILED,
                        atMs = started, durationMs = clock.elapsedRealtimeMs() - started)
                }
                // Let the list settle. Scrolling too fast is the usual reason a
                // "scroll until" loop misses its target.
                clock.sleep(SETTLE_AFTER_SCROLL_MS)
            }
            return notFound(wanted.describe(), listOf("scrolled $rounds time(s), never appeared"), started)
        }

        var last: com.deuty.agent.agent.port.EngineResult? = null
        val times = action.times.coerceIn(1, 20)
        repeat(times) { i ->
            last = session.interaction.scroll(action.direction, containerMatch, 1)
            if (last?.ok != true || i == times - 1) return@repeat
            clock.sleep(SETTLE_AFTER_SCROLL_MS)
        }
        val usedContainer = containerMatch?.let { " in ${it.node?.simpleClassName ?: "container"}" } ?: ""
        return finish(last, "scroll ${action.direction.name.lowercase()}$usedContainer",
            containerMatch?.tier, containerMatch, started, screen)
    }

    // ------------------------------------------------------------------ type

    private suspend fun type(
        action: Type,
        screen: ScreenSnapshot,
        session: DeviceSession,
        ctx: ExecContext,
        started: Long
    ): ActionOutcome {
        val resolution = action.target?.let { resolver.resolve(screen, it) }
        val match = resolution?.best

        if (action.target != null && match == null) {
            return notFound(action.target.describe(), resolution?.diagnostics ?: emptyList(), started)
        }

        // Password guard: refuse rather than "help". The value is never logged.
        // Note this also covers target == null ("type into whatever has focus"),
        // where the sensitive field is the focused node rather than a resolved one.
        val targetIsPassword = match?.node?.isPassword == true ||
            (match == null && screen.focusedNode?.isPassword == true) ||
            (match?.node?.let { Redactor.isSensitiveLabel(it.label) } == true && match.node.isEditable)
        if (targetIsPassword && !ctx.allowPasswordEntry) {
            return ActionOutcome.needsUser(
                "refusing to type into a password/OTP field",
                AskUserRequest(
                    id = Ids.request(),
                    question = "This looks like a password or OTP field. Please type it yourself - I will wait.",
                    options = listOf("I typed it, continue", "Stop the task"),
                    allowFreeText = false,
                    risk = RiskLevel.FORBIDDEN,
                    reason = FailureReason.RISK_BLOCKED,
                    stepId = ctx.stepId,
                    taskId = ctx.taskId,
                    createdAtMs = clock.nowMs(),
                    denyStopsTask = true
                ),
                reason = FailureReason.RISK_BLOCKED
            )
        }

        val result = session.interaction.setText(match, action.text, action.clearFirst)
        if (!result.ok) {
            return ActionOutcome.failure(result.message, result.failure ?: FailureReason.TEXT_INPUT_FAILED,
                atMs = started, durationMs = clock.elapsedRealtimeMs() - started)
        }

        if (action.pressEnter) {
            val ime = session.interaction.pressImeAction()
            if (!ime.ok) {
                // Not fatal: the text is in. Tell the user what to do.
                val safe = Redactor.text(action.text, ctx.redact) ?: ""
                return ActionOutcome(
                    status = OutcomeStatus.SUCCESS,
                    message = "typed ${action.text.length} chars, but this device cannot inject the Enter key" +
                        (if (safe.isNotBlank()) " - press the keyboard action key yourself" else ""),
                    verified = false,
                    startedAtMs = started,
                    durationMs = clock.elapsedRealtimeMs() - started
                )
            }
        }
        return ActionOutcome.success(
            "typed ${action.text.length} char(s)",
            verified = false,
            tier = resolution?.tier,
            atMs = started,
            durationMs = clock.elapsedRealtimeMs() - started
        )
    }

    private suspend fun paste(
        action: Paste,
        screen: ScreenSnapshot,
        session: DeviceSession,
        ctx: ExecContext,
        started: Long
    ): ActionOutcome {
        val match = action.target?.let { resolver.resolve(screen, it).best }
        val text = action.text
        if (text.isNullOrBlank()) {
            return ActionOutcome.failure("nothing to paste", FailureReason.UNKNOWN, atMs = started)
        }
        val result = session.interaction.paste(text)
        return finish(result, "paste ${text.length} char(s)", null, match, started, screen)
    }

    // ------------------------------------------------------------ app/global

    private suspend fun global(
        action: GlobalAction,
        label: String,
        started: Long,
        invoke: suspend (GlobalAction) -> com.deuty.agent.agent.port.EngineResult
    ): ActionOutcome {
        val result = invoke(action)
        return if (result.ok) {
            ActionOutcome.success(label, verified = false, atMs = started, durationMs = clock.elapsedRealtimeMs() - started)
        } else {
            ActionOutcome.failure(result.message, result.failure ?: FailureReason.GESTURE_FAILED,
                atMs = started, durationMs = clock.elapsedRealtimeMs() - started)
        }
    }

    private suspend fun launchApp(
        action: LaunchApp,
        session: DeviceSession,
        started: Long
    ): ActionOutcome {
        val pkg = action.packageName ?: action.appLabel?.let { session.apps.resolve(it) }
        if (pkg.isNullOrBlank()) {
            return ActionOutcome.failure(
                "no app found for \"${action.appLabel ?: "?"}\"",
                FailureReason.APP_NOT_FOUND,
                atMs = started,
                durationMs = clock.elapsedRealtimeMs() - started
            )
        }
        val result = session.interaction.launchApp(pkg)
        return if (result.ok) {
            ActionOutcome.success("launched $pkg", verified = false, atMs = started,
                durationMs = clock.elapsedRealtimeMs() - started, data = pkg)
        } else {
            ActionOutcome.failure(result.message, result.failure ?: FailureReason.APP_NOT_FOUND,
                atMs = started, durationMs = clock.elapsedRealtimeMs() - started)
        }
    }

    private suspend fun relaunch(
        action: Relaunch,
        session: DeviceSession,
        started: Long
    ): ActionOutcome {
        val result = session.interaction.launchApp(action.packageName)
        return if (result.ok) {
            ActionOutcome.success("relaunched ${action.packageName}", verified = false, atMs = started,
                durationMs = clock.elapsedRealtimeMs() - started)
        } else {
            ActionOutcome.failure(result.message, result.failure ?: FailureReason.APP_NOT_FOUND,
                atMs = started, durationMs = clock.elapsedRealtimeMs() - started)
        }
    }

    private suspend fun wait(
        action: Wait,
        session: DeviceSession,
        started: Long
    ): ActionOutcome {
        if (action.until == null) {
            clock.sleep(action.millis.coerceIn(0L, 30_000L))
            return ActionOutcome.success("waited ${action.millis}ms", verified = true,
                atMs = started, durationMs = clock.elapsedRealtimeMs() - started)
        }
        // Bounded wait-for-condition. The evaluator is run by the runner; here we
        // only do the cheap part: give the UI time and report the final state.
        val deadline = started + action.millis.coerceIn(100L, 60_000L)
        var lastSignature = session.screens.lastSignature()
        while (clock.elapsedRealtimeMs() < deadline) {
            clock.sleep(POLL_WAIT_MS)
            val next = session.screens.lastSignature()
            if (next != lastSignature) {
                lastSignature = next
                break
            }
        }
        return ActionOutcome.success(
            "waited for the screen to settle",
            verified = false,
            atMs = started,
            durationMs = clock.elapsedRealtimeMs() - started
        )
    }

    private fun readScreen(
        action: ReadScreen,
        screen: ScreenSnapshot,
        ctx: ExecContext,
        started: Long
    ): ActionOutcome {
        val summary = screen.summary(action.maxItems)
        val safe = Redactor.text(summary, ctx.redact) ?: summary
        return ActionOutcome(
            status = OutcomeStatus.SUCCESS,
            message = if (action.reportToUser) safe else "screen read",
            verified = true,
            startedAtMs = started,
            durationMs = 0L,
            data = safe
        )
    }

    private suspend fun screenshot(
        action: Screenshot,
        session: DeviceSession,
        ctx: ExecContext,
        started: Long
    ): ActionOutcome {
        if (!ctx.allowScreenshots) {
            return ActionOutcome.blocked(
                "screen capture is disabled in settings",
                FailureReason.RISK_BLOCKED
            )
        }
        if (!session.interaction.capabilities.screenshots) {
            return ActionOutcome.failure(
                "this device/API cannot capture the screen from an accessibility service (needs Android 11+)",
                FailureReason.NOT_IMPLEMENTED,
                atMs = started
            )
        }
        val result = session.interaction.captureScreen(action.region)
        val capture = result.capture
        if (!result.ok || capture == null) {
            return ActionOutcome.failure(result.message, result.failure ?: FailureReason.SCREEN_UNREADABLE,
                atMs = started, durationMs = clock.elapsedRealtimeMs() - started)
        }
        var message = "captured ${capture.width}x${capture.height}"
        var data: String? = null
        if (action.runOcr && session.vision.available) {
            val lines = session.vision.readText(capture, action.region)
            message += ", ocr read ${lines.size} line(s)"
            data = lines.joinToString("\n") { it.text }
        } else if (action.runOcr) {
            message += " (no OCR engine configured)"
        }
        return ActionOutcome(
            status = OutcomeStatus.SUCCESS,
            message = message,
            verified = true,
            startedAtMs = started,
            durationMs = clock.elapsedRealtimeMs() - started,
            data = data
        )
    }

    private fun findElement(
        action: FindElement,
        screen: ScreenSnapshot,
        started: Long
    ): ActionOutcome {
        val resolution = resolver.resolve(screen, action.query)
        val match = resolution.best
        return if (match != null) {
            ActionOutcome(
                status = OutcomeStatus.SUCCESS,
                message = "found ${action.query.describe()} -> ${match.describe()}",
                verified = true,
                tier = resolution.tier,
                startedAtMs = started,
                data = resolution.matches.joinToString("\n") { it.describe() }
            )
        } else {
            notFound(action.query.describe(), resolution.diagnostics, started)
        }
    }

    private fun askUser(action: AskUser, ctx: ExecContext): ActionOutcome = ActionOutcome.needsUser(
        action.question,
        AskUserRequest(
            id = Ids.request(),
            question = action.question,
            options = action.options,
            allowFreeText = action.allowFreeText,
            risk = action.risk,
            stepId = ctx.stepId,
            taskId = ctx.taskId,
            createdAtMs = clock.nowMs(),
            denyStopsTask = action.denyStopsTask
        )
    )

    // ------------------------------------------------------------- plumbing

    private fun finish(
        result: com.deuty.agent.agent.port.EngineResult?,
        description: String,
        tier: com.deuty.agent.core.model.MatchTier?,
        match: MatchedElement?,
        started: Long,
        screen: ScreenSnapshot
    ): ActionOutcome {
        val duration = clock.elapsedRealtimeMs() - started
        if (result == null) {
            return ActionOutcome.failure("engine returned nothing for $description", FailureReason.UNKNOWN,
                tier = tier, atMs = started, durationMs = duration)
        }
        return if (result.ok) {
            ActionOutcome(
                status = OutcomeStatus.SUCCESS,
                message = "$description -> ${result.message}",
                verified = false,
                tier = tier,
                startedAtMs = started,
                durationMs = duration,
                signatureBefore = screen.signature
            )
        } else {
            ActionOutcome(
                status = if (result.unsupported) OutcomeStatus.BLOCKED else OutcomeStatus.FAILURE,
                message = "$description failed: ${result.message}",
                reason = result.failure ?: FailureReason.GESTURE_FAILED,
                tier = tier,
                startedAtMs = started,
                durationMs = duration,
                signatureBefore = screen.signature
            )
        }
    }

    private fun notFound(what: String, diagnostics: List<String>, started: Long): ActionOutcome =
        ActionOutcome(
            status = OutcomeStatus.FAILURE,
            message = "could not find $what" + (diagnostics.firstOrNull()?.let { " ($it)" } ?: ""),
            reason = FailureReason.ELEMENT_NOT_FOUND,
            startedAtMs = started,
            durationMs = clock.elapsedRealtimeMs() - started,
            data = diagnostics.joinToString(" | ").ifBlank { null }
        )

    companion object {
        private const val TAG = "executor"
        private const val SETTLE_AFTER_SCROLL_MS = 420L
        private const val POLL_WAIT_MS = 120L
    }
}
