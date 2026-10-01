package com.deuty.agent.workflow

import com.deuty.agent.agent.toQuery
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.InteractionKind
import com.deuty.agent.core.model.RecordedInteraction
import com.deuty.agent.core.model.RetryPolicy
import com.deuty.agent.core.model.ScreenChanged
import com.deuty.agent.core.model.FieldContains
import com.deuty.agent.core.model.PackageIs
import com.deuty.agent.core.model.TaskStep
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.TextMatch
import com.deuty.agent.agent.executor.LaunchApp
import com.deuty.agent.agent.executor.LongPress
import com.deuty.agent.agent.executor.Scroll
import com.deuty.agent.agent.executor.Tap
import com.deuty.agent.agent.executor.Type

/**
 * Workflow recording (requirement 9).
 *
 * The important line in this file is [toQuery]: a recording stores
 * `Tap(text="Download", id="com.x:id/download", bounds=[...])`, never
 * `tap(823, 176)`. Coordinates survive exactly one layout change; a resource id
 * or a label survives almost all of them, and the bounds stay behind as a last
 * resort for apps with no metadata at all.
 *
 * Recording rules:
 *   - Our own UI is ignored, so pressing STOP does not become a step.
 *   - Consecutive text changes in the same field collapse into ONE Type step
 *     holding the final text. Recording every keystroke would make replay type
 *     character by character, which is both slow and wrong after a retry.
 *   - Consecutive scrolls in the same direction collapse into one Scroll(times=n).
 *   - A package change becomes an explicit LaunchApp step, so replay works even
 *     when the app is not already in the foreground.
 *   - Text typed into a password field is never recorded. Not "redacted": never
 *     captured at all.
 *
 * Deliberately NOT done: risk levels are not baked into the recording. Risk is
 * re-assessed by RiskManager against the live screen at replay time, because the
 * same "Save" button can be harmless in one app and a purchase confirmation in
 * another.
 */
class WorkflowRecorder(
    private val clock: Clock,
    private val log: AgentLog
) {

    private val lock = Any()
    private var recording = false
    private var startedAtMs = 0L
    private val interactions = mutableListOf<RecordedInteraction>()

    val isRecording: Boolean get() = synchronized(lock) { recording }

    val interactionCount: Int get() = synchronized(lock) { interactions.size }

    fun start() {
        synchronized(lock) {
            recording = true
            startedAtMs = clock.nowMs()
            interactions.clear()
        }
        log.i(TAG, "recording started")
    }

    fun cancel() {
        synchronized(lock) {
            recording = false
            interactions.clear()
        }
        log.i(TAG, "recording cancelled")
    }

    fun onInteraction(interaction: RecordedInteraction) {
        synchronized(lock) {
            if (!recording) return
            if (interaction.ownApp) return
            interactions += interaction
        }
    }

    /** Steps so far, for live feedback in the UI. Cheap: pure transformation. */
    fun preview(): List<TaskStep> = synchronized(lock) { normalise(interactions.toList()) }

    /** Stop recording and return the normalised, replayable steps. */
    fun stop(): List<TaskStep> {
        val captured = synchronized(lock) {
            recording = false
            val copy = interactions.toList()
            interactions.clear()
            copy
        }
        val steps = normalise(captured)
        log.i(TAG, "recording stopped: ${captured.size} interaction(s) -> ${steps.size} step(s)")
        return steps
    }

    // ------------------------------------------------------------ normalise

    private fun normalise(raw: List<RecordedInteraction>): List<TaskStep> {
        val steps = mutableListOf<TaskStep>()
        var lastPackage: String? = null
        var pendingText: RecordedInteraction? = null
        var pendingScroll: Pair<Direction, Int>? = null

        fun flushText() {
            val pending = pendingText ?: return
            pendingText = null
            val node = pending.node ?: return
            val text = pending.text ?: return
            if (node.isPassword) {
                log.i(TAG, "dropped a recorded password entry")
                return
            }
            val query = node.toQuery(allowBoundsFallback = true)
            steps += TaskStep(
                id = Ids.step(),
                title = "Type into ${query.label ?: "field"}",
                action = Type(text = text, target = query, clearFirst = true),
                postcondition = FieldContains(text, query),
                timeoutMs = 6_000L,
                retry = RetryPolicy.PATIENT
            )
        }

        fun flushScroll() {
            val (direction, times) = pendingScroll ?: return
            pendingScroll = null
            steps += TaskStep(
                id = Ids.step(),
                title = "Scroll ${direction.name.lowercase()}" + if (times > 1) " x$times" else "",
                action = Scroll(direction = direction, times = times),
                postcondition = ScreenChanged(),
                timeoutMs = 5_000L
            )
        }

        raw.forEach { interaction ->
            // A package change ends whatever was pending: text typed into one app
            // must not be flushed as a step belonging to the next one.
            if (interaction.packageName != null && interaction.packageName != lastPackage) {
                flushText()
                flushScroll()
                if (lastPackage != null || steps.isNotEmpty()) {
                    steps += TaskStep(
                        id = Ids.step(),
                        title = "Open ${interaction.packageName.substringAfterLast('.')}",
                        action = LaunchApp(packageName = interaction.packageName),
                        postcondition = PackageIs(interaction.packageName),
                        timeoutMs = 8_000L
                    )
                }
                lastPackage = interaction.packageName
            }

            when (interaction.kind) {
                InteractionKind.TAP -> {
                    flushText()
                    flushScroll()
                    val node = interaction.node ?: return@forEach
                    val query = node.toQuery()
                    steps += TaskStep(
                        id = Ids.step(),
                        title = "Tap ${query.label ?: query.primaryLocator()}",
                        action = Tap(query),
                        postcondition = ScreenChanged(),
                        timeoutMs = 6_000L
                    )
                }

                InteractionKind.LONG_PRESS -> {
                    flushText()
                    flushScroll()
                    val node = interaction.node ?: return@forEach
                    val query = node.toQuery()
                    steps += TaskStep(
                        id = Ids.step(),
                        title = "Long press ${query.label ?: query.primaryLocator()}",
                        action = LongPress(query),
                        postcondition = ScreenChanged(),
                        timeoutMs = 6_000L
                    )
                }

                InteractionKind.TEXT_CHANGE -> {
                    flushScroll()
                    val node = interaction.node
                    if (node?.isPassword == true) {
                        log.i(TAG, "ignoring text change in a password field")
                        return@forEach
                    }
                    val previous = pendingText
                    val sameField = previous != null && node != null &&
                        (previous.node?.key == node.key ||
                            TextMatch.equalsLoose(previous.node?.viewIdResourceName, node.viewIdResourceName) ||
                            previous.node?.bounds == node.bounds)
                    if (sameField) {
                        // Keep the latest text, but the *first* node (its locator is
                        // usually more stable than the one after the IME opened).
                        pendingText = previous.copy(text = interaction.text, atMs = interaction.atMs)
                    } else {
                        flushText()
                        pendingText = interaction
                    }
                }

                InteractionKind.SCROLL -> {
                    flushText()
                    val direction = interaction.direction ?: Direction.DOWN
                    val (pendingDirection, times) = pendingScroll ?: (direction to 0)
                    pendingScroll = if (pendingDirection == direction) {
                        direction to (times + 1)
                    } else {
                        flushScroll()
                        direction to 1
                    }
                }

                InteractionKind.SELECTION -> {
                    // Selection events fire constantly in lists; recording them
                    // would bury the real steps. Ignored on purpose.
                }

                InteractionKind.WINDOW_CHANGE -> {
                    // Package changes are handled above; a window change inside the
                    // same package (dialog opening) needs no step of its own.
                }
            }
        }

        flushText()
        flushScroll()
        return mergeDuplicates(steps)
    }

    /**
     * A recorded tap on a text field followed by a Type step on the same field is
     * redundant: Type focuses the field itself. Dropping the tap removes a step
     * that can fail (and cost a retry) for no benefit.
     */
    private fun mergeDuplicates(steps: List<TaskStep>): List<TaskStep> {
        if (steps.size < 2) return steps
        val out = mutableListOf<TaskStep>()
        steps.forEachIndexed { index, step ->
            val next = steps.getOrNull(index + 1)
            val tapOnField = step.action is Tap &&
                step.action.target.let { it.editable == true || it.className?.contains("EditText") == true }
            if (tapOnField && next?.action is Type) {
                val sameTarget = (step.action as Tap).target.primaryLocator() == next.action.target?.primaryLocator()
                if (sameTarget) {
                    log.d(TAG, "merged a focus tap into the following Type step")
                    return@forEachIndexed
                }
            }
            out += step
        }
        return out
    }

    /** Packages touched by a recording, for display and for replay preconditions. */
    fun packagesOf(steps: List<TaskStep>): List<String> =
        steps.mapNotNull { step ->
            when (val action = step.action) {
                is LaunchApp -> action.packageName
                else -> null
            }
        }.distinct()

    companion object {
        private const val TAG = "recorder"

        /** A query that keeps the locator but drops recorded bounds. */
        fun semanticOnly(query: ElementQuery): ElementQuery = query.withoutFallbacks()
    }
}
