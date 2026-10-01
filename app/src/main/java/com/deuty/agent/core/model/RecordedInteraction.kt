package com.deuty.agent.core.model

/**
 * What the user just did, in framework-free terms.
 *
 * The platform layer turns an AccessibilityEvent (+ its source node) into one of
 * these; the recorder turns a stream of these into semantic steps. Splitting it
 * this way means the recorder - the part with all the interesting judgement in
 * it - has no Android dependency and can be unit-tested.
 */
enum class InteractionKind {
    TAP,
    LONG_PRESS,
    TEXT_CHANGE,
    SCROLL,
    WINDOW_CHANGE,
    SELECTION
}

data class RecordedInteraction(
    val kind: InteractionKind,
    val atMs: Long,
    val packageName: String?,
    val node: UiNode?,
    /** For TEXT_CHANGE: the field's new content (never for password fields). */
    val text: String? = null,
    val direction: Direction? = null,
    /** True when the interaction happened in our own UI and must be ignored. */
    val ownApp: Boolean = false
)
