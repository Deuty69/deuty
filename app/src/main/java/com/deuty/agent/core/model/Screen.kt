package com.deuty.agent.core.model

/**
 * Screen understanding levels (requirement 4).
 *
 * The agent always knows which level produced the data it is reasoning about,
 * and it always prefers the cheapest level that can answer the question:
 *
 *   ACCESSIBILITY_TREE  Level 1 - free, already in memory, structured. Default.
 *   SCREENSHOT          Level 2 - one bitmap, only when the tree is empty or
 *                                 unusable (WebView, games, custom-drawn UI).
 *   OCR                 Level 3 - text extracted from a *cropped region*.
 *   VISUAL_MODEL        Level 4 - an AI model looks at the screen. Genuinely
 *                                 ambiguous cases only. Never in V1.
 *   SIMULATED           Sandbox / unit tests. Not a real device.
 *   UNAVAILABLE         No screen could be read.
 *
 * Cost ordering matters for 4 GB devices: a tree walk is a few milliseconds of
 * CPU and no allocation churn beyond our own snapshot; a screenshot is a
 * hardware buffer copy plus a bitmap; OCR is a model; a visual model is a
 * round trip.
 */
enum class ScreenSource(val level: Int, val label: String) {
    ACCESSIBILITY_TREE(1, "tree"),
    SCREENSHOT(2, "screenshot"),
    OCR(3, "ocr"),
    VISUAL_MODEL(4, "vision-model"),
    SIMULATED(0, "simulated"),
    UNAVAILABLE(-1, "unavailable");

    /** True when this source can answer structural questions (ids, bounds). */
    val isStructured: Boolean get() = this == ACCESSIBILITY_TREE || this == SIMULATED
}

enum class Direction {
    UP, DOWN, LEFT, RIGHT;

    val isVertical: Boolean get() = this == UP || this == DOWN

    fun opposite(): Direction = when (this) {
        UP -> DOWN
        DOWN -> UP
        LEFT -> RIGHT
        RIGHT -> LEFT
    }

    companion object {
        /** Tolerant parsing for natural language: "scroll downwards", "swipe up". */
        fun parse(raw: String?): Direction? = when (raw?.trim()?.lowercase()) {
            null, "", "down", "downwards", "d" -> DOWN
            "up", "upwards", "u" -> UP
            "left", "l" -> LEFT
            "right", "r" -> RIGHT
            else -> null
        }
    }
}

/**
 * Axis-aligned bounds in screen pixels. Deliberately *not*
 * android.graphics.Rect: everything under core/ and agent/ must stay free of
 * framework types so it can be unit-tested on a plain JVM.
 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    fun intersects(other: Bounds): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    /** Fraction of this box that is covered by [other]; used for "same element" checks. */
    fun overlapRatio(other: Bounds): Float {
        if (isEmpty || other.isEmpty) return 0f
        val ix = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
        val iy = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
        val inter = ix.toFloat() * iy.toFloat()
        val smallest = minOf(width.toFloat() * height.toFloat(), other.width.toFloat() * other.height.toFloat())
        return if (smallest <= 0f) 0f else (inter / smallest).coerceIn(0f, 1f)
    }

    companion object {
        val ZERO = Bounds(0, 0, 0, 0)
    }
}

data class Point(val x: Int, val y: Int)

/**
 * One node of the flattened accessibility tree.
 *
 * Flattened (depth + parentKey) rather than a tree of object references for
 * three reasons: it is cheap to copy, trivial to persist for the inspector,
 * and it cannot leak a live AccessibilityNodeInfo that we forgot to recycle.
 */
data class UiNode(
    val key: Int,
    val parentKey: Int?,
    val depth: Int,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val className: String?,
    val packageName: String?,
    val bounds: Bounds,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isChecked: Boolean,
    val isSelected: Boolean,
    val isEnabled: Boolean,
    val isFocused: Boolean,
    val isPassword: Boolean,
    val childCount: Int
) {
    /** Bare id, e.g. "com.android.settings:id/search" -> "search". */
    val idName: String?
        get() = viewIdResourceName?.substringAfterLast('/', "")?.takeIf { it.isNotEmpty() }

    val isOnScreen: Boolean get() = !bounds.isEmpty

    /** Anything a human would call "a thing you can point at". */
    val isInteractive: Boolean get() = isClickable || isLongClickable || isScrollable || isEditable

    val label: String?
        get() = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }

    val simpleClassName: String?
        get() = className?.substringAfterLast('.')

    fun describe(): String = buildString {
        append(simpleClassName ?: "?")
        label?.let { append(" \"").append(it.take(60)).append('"') }
        idName?.let { append(" id=").append(it) }
        if (isClickable) append(" click")
        if (isEditable) append(" edit")
        if (isScrollable) append(" scroll")
        if (isLongClickable) append(" longClick")
        if (isPassword) append(" PASSWORD")
        append(" [").append(bounds.left).append(',').append(bounds.top)
            .append("][").append(bounds.right).append(',').append(bounds.bottom).append(']')
    }

    /**
     * A short, stable-ish identity used for screen signatures and loop
     * detection. Text is truncated so that a clock in the status bar or a
     * blinking cursor cannot make every signature unique.
     */
    fun signaturePart(): String = buildString {
        append(idName ?: "-").append('|')
        append((label ?: "-").take(16)).append('|')
        append(simpleClassName ?: "-")
    }
}

/** Level-3 OCR output. Empty in V1 unless the user enables screen capture. */
data class OcrLine(
    val text: String,
    val bounds: Bounds,
    val confidence: Float
)

/**
 * An immutable observation of the screen at one instant.
 *
 * This is the only screen representation the agent reasons about; the planner,
 * executor, verifier and recovery manager never touch framework objects. That
 * is what makes "the engine works without an AI model" and "the engine works
 * in a sandbox" both true.
 */
data class ScreenSnapshot(
    val packageName: String?,
    val windowTitle: String?,
    val nodes: List<UiNode>,
    val source: ScreenSource,
    val capturedAtMs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val isSecure: Boolean = false,
    val ocrLines: List<OcrLine> = emptyList(),
    val signature: String = "",
    val truncated: Boolean = false,
    val captureDurationMs: Long = 0L
) {
    val isEmpty: Boolean get() = nodes.isEmpty() && ocrLines.isEmpty()

    val interactiveNodes: List<UiNode> get() = nodes.filter { it.isInteractive && it.isOnScreen }

    val editableNodes: List<UiNode> get() = nodes.filter { it.isEditable && it.isOnScreen }

    val scrollableNodes: List<UiNode> get() = nodes.filter { it.isScrollable && it.isOnScreen }

    val focusedNode: UiNode? get() = nodes.firstOrNull { it.isFocused }

    /** All human-visible strings, used by TextPresent expectations and reports. */
    fun visibleText(): String = buildString {
        nodes.forEach { n ->
            n.text?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
            n.contentDescription?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
        }
        ocrLines.forEach { append(it.text).append('\n') }
    }

    fun nodeAt(x: Int, y: Int): UiNode? =
        nodes.lastOrNull { it.isOnScreen && it.bounds.contains(x, y) && it.isInteractive }
            ?: nodes.lastOrNull { it.isOnScreen && it.bounds.contains(x, y) }

    /** Compact human summary; this is what "read the screen" reports. */
    fun summary(maxItems: Int = 14): String {
        if (isEmpty) return "Screen is empty or unreadable (source=${source.label})."
        val sb = StringBuilder()
        sb.append("Package: ").append(packageName ?: "?").append('\n')
        if (!windowTitle.isNullOrBlank()) sb.append("Window: ").append(windowTitle).append('\n')
        sb.append("Nodes: ").append(nodes.size)
            .append(" (interactive=").append(interactiveNodes.size)
            .append(", editable=").append(editableNodes.size)
            .append(", scrollable=").append(scrollableNodes.size).append(")\n")
        sb.append("Source: ").append(source.label)
        if (isSecure) sb.append(" [SECURE SCREEN - acting is disabled]")
        sb.append('\n')
        val highlights = interactiveNodes.filter { it.label != null }.take(maxItems)
        if (highlights.isNotEmpty()) {
            sb.append("You can act on:\n")
            highlights.forEachIndexed { i, n -> sb.append("  ").append(i + 1).append(". ").append(n.describe()).append('\n') }
        }
        val fields = editableNodes.take(4)
        if (fields.isNotEmpty()) {
            sb.append("Text fields:\n")
            fields.forEach { n -> sb.append("  - ").append(n.describe()).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    companion object {
        fun unavailable(atMs: Long, reason: String? = null) = ScreenSnapshot(
            packageName = null,
            windowTitle = reason,
            nodes = emptyList(),
            source = ScreenSource.UNAVAILABLE,
            capturedAtMs = atMs,
            displayWidth = 0,
            displayHeight = 0
        )
    }
}
