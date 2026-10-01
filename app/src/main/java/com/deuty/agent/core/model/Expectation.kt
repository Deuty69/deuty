package com.deuty.agent.core.model

/**
 * Preconditions and postconditions (requirement 5).
 *
 * Every step may declare what must be true before it runs and what must be
 * true afterwards. Expectations are pure data over a [ScreenSnapshot], so they
 * can be evaluated identically by the real device engine, the workflow
 * replayer and the sandbox.
 */
sealed interface Expectation {
    fun describe(): String
}

/** Always satisfied. Used for steps whose effect is not observable (Wait). */
data object Always : Expectation {
    override fun describe() = "always"
}

data class ElementPresent(val query: ElementQuery) : Expectation {
    override fun describe() = "element present: ${query.describe()}"
}

data class ElementAbsent(val query: ElementQuery) : Expectation {
    override fun describe() = "element gone: ${query.describe()}"
}

data class TextPresent(val text: String, val ignoreCase: Boolean = true) : Expectation {
    override fun describe() = "text present: \"$text\""
}

data class TextAbsent(val text: String, val ignoreCase: Boolean = true) : Expectation {
    override fun describe() = "text gone: \"$text\""
}

/**
 * The screen changed after the action. This is the default postcondition for
 * taps: it is cheap (a signature comparison) and it catches the most common
 * failure, which is "the tap did nothing".
 */
data class ScreenChanged(val fromSignature: String? = null) : Expectation {
    override fun describe() = "screen changed"
}

data class ScreenUnchanged(val fromSignature: String? = null) : Expectation {
    override fun describe() = "screen unchanged"
}

data class PackageIs(val packageName: String) : Expectation {
    override fun describe() = "package is $packageName"
}

data class WindowIs(val titleContains: String) : Expectation {
    override fun describe() = "window contains \"$titleContains\""
}

/** An editable field is empty - the classic "message was sent" check. */
data class FieldCleared(val query: ElementQuery? = null) : Expectation {
    override fun describe() = "field cleared" + (query?.let { ": ${it.describe()}" } ?: "")
}

/** A field now holds exactly [text]. */
data class FieldContains(val text: String, val query: ElementQuery? = null) : Expectation {
    override fun describe() = "field contains \"$text\""
}

data class AnyOf(val options: List<Expectation>) : Expectation {
    override fun describe() = "any of (" + options.joinToString(" | ") { it.describe() } + ")"
}

data class AllOf(val options: List<Expectation>) : Expectation {
    override fun describe() = "all of (" + options.joinToString(" & ") { it.describe() } + ")"
}

/** Repeat [body] until this holds - the only looping construct the agent has. */
data class Until(val expectation: Expectation, val maxRounds: Int = 8) : Expectation {
    override fun describe() = "until ${expectation.describe()} (max $maxRounds)"
}

enum class Verdict { SATISFIED, NOT_SATISFIED, UNKNOWN }

data class ExpectationResult(
    val verdict: Verdict,
    val message: String,
    val evaluatedAtMs: Long
) {
    val satisfied: Boolean get() = verdict == Verdict.SATISFIED
}
