package com.deuty.agent.platform

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.InteractionKind
import com.deuty.agent.core.model.RecordedInteraction
import com.deuty.agent.core.model.UiNode
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import android.graphics.Rect

/**
 * AccessibilityEvent -> [RecordedInteraction] (the recording half of
 * requirement 9).
 *
 * This is the only place in the app that turns framework events into agent data,
 * and it is deliberately tiny: everything judgemental (collapsing keystrokes,
 * merging scrolls, dropping our own UI) lives in the pure
 * [com.deuty.agent.workflow.WorkflowRecorder], where it can be unit-tested.
 *
 * Two rules that are easy to get wrong and expensive when you do:
 *   - `event.source` returns a node WE own and must recycle. On API 26-32 the
 *     shared node pool is 50 entries; leaking them makes the whole system's
 *     accessibility layer start returning null nodes.
 *   - Text from a password field is never read into our data structures at all.
 *     Not redacted later: not captured.
 */
class InteractionMapper(
    private val clock: Clock,
    private val log: AgentLog,
    private val ownPackage: String
) {

    /** Last known scroll offsets per window, used to infer direction pre-API 28. */
    private val lastScroll = HashMap<String, Pair<Int, Int>>()

    fun map(event: AccessibilityEvent): RecordedInteraction? {
        val packageName = event.packageName?.toString()
        if (packageName == ownPackage) {
            // Touches on our own overlay/chat must never become workflow steps.
            return RecordedInteraction(
                kind = InteractionKind.WINDOW_CHANGE,
                atMs = clock.nowMs(),
                packageName = packageName,
                node = null,
                ownApp = true
            )
        }

        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> InteractionKind.TAP
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> InteractionKind.LONG_PRESS
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> InteractionKind.TEXT_CHANGE
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> InteractionKind.SCROLL
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> InteractionKind.WINDOW_CHANGE
            AccessibilityEvent.TYPE_VIEW_SELECTED -> InteractionKind.SELECTION
            else -> return null
        }

        var owned: AccessibilityNodeInfo? = null
        try {
            owned = runCatching { event.source }.getOrNull()
            if (owned != null && owned.isPassword && kind == InteractionKind.TEXT_CHANGE) {
                log.d(TAG, "ignored a text change in a password field")
                return null
            }
            val node = owned?.let { toUiNode(it) }
            val text = when (kind) {
                InteractionKind.TEXT_CHANGE -> eventText(event)
                else -> null
            }
            val direction = if (kind == InteractionKind.SCROLL) scrollDirection(event, packageName) else null
            return RecordedInteraction(
                kind = kind,
                atMs = clock.nowMs(),
                packageName = packageName,
                node = node,
                text = text,
                direction = direction
            )
        } catch (error: Throwable) {
            // A recording must never crash the service: the event stream is shared
            // with the running task, and losing a recording is recoverable.
            log.w(TAG, "could not map event ${event.eventType}: ${error.javaClass.simpleName}")
            return null
        } finally {
            owned?.let { node -> runCatching { node.recycle() } }
        }
    }

    private fun eventText(event: AccessibilityEvent): String? {
        val text = event.text
        if (text.isEmpty()) return null
        // TYPE_VIEW_TEXT_CHANGED carries the field's full new content as one entry.
        return text.joinToString("").take(MAX_TEXT)
    }

    /**
     * Direction of a scroll event. API 28+ gives us the delta directly; before
     * that we compare against the last offsets we saw for this window, which is
     * good enough to record "scrolled down three times" and is never used for
     * anything that must be exact.
     */
    private fun scrollDirection(event: AccessibilityEvent, packageName: String?): Direction? {
        val key = packageName ?: "?"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dy = runCatching { event.scrollDeltaY }.getOrDefault(0)
            val dx = runCatching { event.scrollDeltaX }.getOrDefault(0)
            return when {
                dy > 0 -> Direction.DOWN
                dy < 0 -> Direction.UP
                dx > 0 -> Direction.RIGHT
                dx < 0 -> Direction.LEFT
                else -> null
            }
        }
        val previous = lastScroll[key]
        val current = event.scrollX to event.scrollY
        lastScroll[key] = current
        if (previous == null) return Direction.DOWN
        val dy = current.second - previous.second
        val dx = current.first - previous.first
        return when {
            dy > 0 -> Direction.DOWN
            dy < 0 -> Direction.UP
            dx > 0 -> Direction.RIGHT
            dx < 0 -> Direction.LEFT
            else -> Direction.DOWN
        }
    }

    /**
     * A standalone [UiNode] for the event's source.
     *
     * `key = 0` on purpose: this node is NOT in the [NodeTable] generation, so it
     * must never be used as a tap target. It exists to produce a semantic
     * [com.deuty.agent.core.model.ElementQuery] for the recording.
     */
    private fun toUiNode(node: AccessibilityNodeInfo): UiNode {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        val className = runCatching { node.className?.toString() }.getOrNull()
        // `isEditable` is the reliable signal (API 21+). The class-name check is a
        // fallback for the handful of custom widgets that report a TextView class
        // but accept text; deliberately NOT the deprecated getActions() list, whose
        // contents vary by OEM and would need a hidden-API call to interpret.
        val editable = runCatching {
            node.isEditable ||
                className?.contains("EditText") == true ||
                className?.contains("AutoCompleteTextView") == true
        }.getOrDefault(false)
        return UiNode(
            key = 0,
            parentKey = null,
            depth = 0,
            text = runCatching { node.text?.toString() }.getOrNull(),
            contentDescription = runCatching { node.contentDescription?.toString() }.getOrNull(),
            viewIdResourceName = runCatching { node.viewIdResourceName }.getOrNull(),
            className = className,
            packageName = runCatching { node.packageName?.toString() }.getOrNull(),
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            isClickable = runCatching { node.isClickable }.getOrDefault(false),
            isLongClickable = runCatching { node.isLongClickable }.getOrDefault(false),
            isScrollable = runCatching { node.isScrollable }.getOrDefault(false),
            isEditable = editable,
            isChecked = runCatching { node.isChecked }.getOrDefault(false),
            isSelected = runCatching { node.isSelected }.getOrDefault(false),
            isEnabled = runCatching { node.isEnabled }.getOrDefault(true),
            isFocused = runCatching { node.isFocused }.getOrDefault(false),
            isPassword = runCatching { node.isPassword }.getOrDefault(false),
            childCount = runCatching { node.childCount }.getOrDefault(0)
        )
    }

    companion object {
        private const val TAG = "events"
        private const val MAX_TEXT = 4_000
    }
}
