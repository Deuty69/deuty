package com.deuty.agent.agent.executor

import com.deuty.agent.agent.ElementResolver
import com.deuty.agent.core.model.AllOf
import com.deuty.agent.core.model.Always
import com.deuty.agent.core.model.AnyOf
import com.deuty.agent.core.model.ElementAbsent
import com.deuty.agent.core.model.ElementPresent
import com.deuty.agent.core.model.Expectation
import com.deuty.agent.core.model.ExpectationResult
import com.deuty.agent.core.model.FieldCleared
import com.deuty.agent.core.model.FieldContains
import com.deuty.agent.core.model.PackageIs
import com.deuty.agent.core.model.ScreenChanged
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.ScreenUnchanged
import com.deuty.agent.core.model.TextAbsent
import com.deuty.agent.core.model.TextPresent
import com.deuty.agent.core.model.Until
import com.deuty.agent.core.model.Verdict
import com.deuty.agent.core.model.WindowIs
import com.deuty.agent.core.util.TextMatch

/**
 * Verifies preconditions and postconditions (requirement 5).
 *
 * Three-valued on purpose: [Verdict.UNKNOWN] means "the screen gave me nothing
 * to judge with" (empty snapshot, capture failed, level-2 only). Treating that
 * as failure would make the agent retry pointlessly on a slow device; treating
 * it as success would let it walk off the end of a screen. The caller decides:
 * ActionRunner reports UNKNOWN as UNVERIFIED and lets RecoveryManager choose.
 */
class ExpectationEvaluator(private val resolver: ElementResolver = ElementResolver()) {

    fun evaluate(
        expectation: Expectation?,
        after: ScreenSnapshot,
        before: ScreenSnapshot? = null,
        atMs: Long = after.capturedAtMs
    ): ExpectationResult {
        if (expectation == null) {
            return ExpectationResult(Verdict.UNKNOWN, "no expectation declared", atMs)
        }
        return evaluateInner(expectation, after, before, atMs, depth = 0)
    }

    private fun evaluateInner(
        expectation: Expectation,
        after: ScreenSnapshot,
        before: ScreenSnapshot?,
        atMs: Long,
        depth: Int
    ): ExpectationResult {
        if (depth > MAX_DEPTH) {
            return ExpectationResult(Verdict.UNKNOWN, "expectation nested too deep", atMs)
        }
        return when (expectation) {
            Always -> ExpectationResult(Verdict.SATISFIED, "always", atMs)

            is ElementPresent -> {
                if (after.isEmpty) return unknown(after, atMs)
                val resolution = resolver.resolve(after, expectation.query)
                if (resolution.isEmpty) {
                    ExpectationResult(
                        Verdict.NOT_SATISFIED,
                        "expected ${expectation.query.describe()} - ${resolution.diagnostics.firstOrNull() ?: "not found"}",
                        atMs
                    )
                } else {
                    ExpectationResult(Verdict.SATISFIED, "found ${resolution.best?.describe()}", atMs)
                }
            }

            is ElementAbsent -> {
                if (after.isEmpty) return unknown(after, atMs)
                val resolution = resolver.resolve(after, expectation.query)
                if (resolution.isEmpty) {
                    ExpectationResult(Verdict.SATISFIED, "${expectation.query.describe()} is gone", atMs)
                } else {
                    ExpectationResult(Verdict.NOT_SATISFIED, "${expectation.query.describe()} is still on screen", atMs)
                }
            }

            is TextPresent -> textCheck(expectation.text, expectation.ignoreCase, after, atMs, expectPresent = true)
            is TextAbsent -> textCheck(expectation.text, expectation.ignoreCase, after, atMs, expectPresent = false)

            is ScreenChanged -> {
                val from = expectation.fromSignature ?: before?.signature
                if (from.isNullOrEmpty()) {
                    ExpectationResult(Verdict.UNKNOWN, "no baseline signature to compare with", atMs)
                } else if (after.signature.isEmpty()) {
                    unknown(after, atMs)
                } else if (after.signature != from) {
                    ExpectationResult(Verdict.SATISFIED, "screen changed ($from -> ${after.signature})", atMs)
                } else {
                    ExpectationResult(Verdict.NOT_SATISFIED, "screen did not change (still $from)", atMs)
                }
            }

            is ScreenUnchanged -> {
                val from = expectation.fromSignature ?: before?.signature
                if (from.isNullOrEmpty() || after.signature.isEmpty()) {
                    ExpectationResult(Verdict.UNKNOWN, "cannot compare signatures", atMs)
                } else if (after.signature == from) {
                    ExpectationResult(Verdict.SATISFIED, "screen unchanged as expected", atMs)
                } else {
                    ExpectationResult(Verdict.NOT_SATISFIED, "screen changed unexpectedly ($from -> ${after.signature})", atMs)
                }
            }

            is PackageIs -> {
                val actual = after.packageName
                if (actual == null) {
                    ExpectationResult(Verdict.UNKNOWN, "foreground package unknown", atMs)
                } else {
                    val ok = actual == expectation.packageName
                    ExpectationResult(
                        if (ok) Verdict.SATISFIED else Verdict.NOT_SATISFIED,
                        if (ok) "in ${expectation.packageName}" else "expected ${expectation.packageName}, got $actual",
                        atMs
                    )
                }
            }

            is WindowIs -> {
                val title = after.windowTitle
                if (title.isNullOrBlank()) {
                    ExpectationResult(Verdict.UNKNOWN, "window title unavailable", atMs)
                } else {
                    val ok = TextMatch.contains(title, expectation.titleContains)
                    ExpectationResult(
                        if (ok) Verdict.SATISFIED else Verdict.NOT_SATISFIED,
                        if (ok) "window \"$title\"" else "window \"$title\" does not contain \"${expectation.titleContains}\"",
                        atMs
                    )
                }
            }

            is FieldCleared -> {
                if (after.isEmpty) return unknown(after, atMs)
                val fields = expectation.query
                    ?.let { resolver.resolve(after, it).matches.mapNotNull { m -> m.node } }
                    ?: after.editableNodes
                if (fields.isEmpty()) {
                    // No field at all is a *good* sign for "message sent": the
                    // composer often disappears entirely after sending.
                    ExpectationResult(Verdict.SATISFIED, "no text field on screen any more", atMs)
                } else {
                    val filled = fields.filter { !it.text.isNullOrBlank() }
                    if (filled.isEmpty()) {
                        ExpectationResult(Verdict.SATISFIED, "field(s) empty", atMs)
                    } else {
                        ExpectationResult(Verdict.NOT_SATISFIED, "field still holds ${filled.first().text?.length ?: 0} char(s)", atMs)
                    }
                }
            }

            is FieldContains -> {
                if (after.isEmpty) return unknown(after, atMs)
                val fields = expectation.query
                    ?.let { resolver.resolve(after, it).matches.mapNotNull { m -> m.node } }
                    ?: after.editableNodes
                if (fields.isEmpty()) {
                    ExpectationResult(Verdict.NOT_SATISFIED, "no text field found to check", atMs)
                } else {
                    val hit = fields.firstOrNull { TextMatch.contains(it.text, expectation.text) }
                    if (hit != null) {
                        ExpectationResult(Verdict.SATISFIED, "field contains the expected text", atMs)
                    } else {
                        ExpectationResult(Verdict.NOT_SATISFIED, "field text does not match", atMs)
                    }
                }
            }

            is AnyOf -> {
                val results = expectation.options.map { evaluateInner(it, after, before, atMs, depth + 1) }
                val hit = results.firstOrNull { it.satisfied }
                when {
                    hit != null -> ExpectationResult(Verdict.SATISFIED, "any-of: ${hit.message}", atMs)
                    results.any { it.verdict == Verdict.UNKNOWN } ->
                        ExpectationResult(Verdict.UNKNOWN, "any-of: " + results.joinToString(" / ") { it.message }, atMs)
                    else -> ExpectationResult(Verdict.NOT_SATISFIED, "any-of failed: " + results.joinToString(" / ") { it.message }, atMs)
                }
            }

            is AllOf -> {
                val results = expectation.options.map { evaluateInner(it, after, before, atMs, depth + 1) }
                val miss = results.firstOrNull { !it.satisfied }
                when {
                    miss == null -> ExpectationResult(Verdict.SATISFIED, "all-of: ${results.size} condition(s) hold", atMs)
                    miss.verdict == Verdict.UNKNOWN -> ExpectationResult(Verdict.UNKNOWN, "all-of: ${miss.message}", atMs)
                    else -> ExpectationResult(Verdict.NOT_SATISFIED, "all-of: ${miss.message}", atMs)
                }
            }

            is Until -> {
                // `Until` is evaluated by the runner (it owns the loop and the
                // clock); here it degrades to its inner expectation.
                evaluateInner(expectation.expectation, after, before, atMs, depth + 1)
            }
        }
    }

    private fun textCheck(
        needle: String,
        ignoreCase: Boolean,
        snapshot: ScreenSnapshot,
        atMs: Long,
        expectPresent: Boolean
    ): ExpectationResult {
        if (snapshot.isEmpty) return unknown(snapshot, atMs)
        val hay = snapshot.visibleText()
        if (hay.isBlank()) {
            return ExpectationResult(Verdict.UNKNOWN, "screen has no readable text", atMs)
        }
        val found = if (ignoreCase) TextMatch.contains(hay, needle) else hay.contains(needle)
        val satisfied = found == expectPresent
        return ExpectationResult(
            if (satisfied) Verdict.SATISFIED else Verdict.NOT_SATISFIED,
            when {
                expectPresent && found -> "\"$needle\" is on screen"
                expectPresent && !found -> "\"$needle\" is not on screen"
                !expectPresent && !found -> "\"$needle\" is gone"
                else -> "\"$needle\" is still on screen"
            },
            atMs
        )
    }

    private fun unknown(snapshot: ScreenSnapshot, atMs: Long) = ExpectationResult(
        Verdict.UNKNOWN,
        "screen unreadable (source=${snapshot.source.label}, nodes=${snapshot.nodes.size})",
        atMs
    )

    companion object {
        private const val MAX_DEPTH = 6
    }
}
