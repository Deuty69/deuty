package com.deuty.agent.agent

import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.MatchedElement
import com.deuty.agent.core.model.MatchTier
import com.deuty.agent.core.model.Point
import com.deuty.agent.core.model.RelativePosition
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.UiNode
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.util.TextMatch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Element targeting (requirement 8).
 *
 * Locators are tried in a fixed priority order and the *first tier that yields
 * anything* wins, so a query never silently degrades to coordinates while a
 * resource-id match was available:
 *
 *   resource-id -> exact text -> content description -> partial text ->
 *   semantic properties -> relative structure -> approximate bounds ->
 *   OCR -> raw coordinates
 *
 * The resolver is pure: it takes a [ScreenSnapshot] and returns candidates. It
 * never touches the framework, never taps anything, and always reports *why* it
 * chose something (or why it found nothing), which is what makes the debug panel
 * and the recovery manager useful instead of guessy.
 */
class ElementResolver {

    data class Resolution(
        val matches: List<MatchedElement>,
        val tier: MatchTier?,
        val ambiguous: Boolean,
        val diagnostics: List<String>
    ) {
        val best: MatchedElement? get() = matches.firstOrNull()
        val isEmpty: Boolean get() = matches.isEmpty()
        val isBrittle: Boolean get() = tier?.isBrittle == true

        fun describe(): String = when {
            isEmpty -> "no match" + (diagnostics.firstOrNull()?.let { " ($it)" } ?: "")
            else -> "${matches.size} match(es) via ${tier?.label}" +
                if (ambiguous) " [AMBIGUOUS]" else ""
        }
    }

    fun resolve(snapshot: ScreenSnapshot, query: ElementQuery, maxResults: Int = 5): Resolution {
        if (query.isUnconstrained) {
            return Resolution(emptyList(), null, false, listOf("query has no locator at all"))
        }

        val nodes = snapshot.nodes.filter { it.isOnScreen }
        val diagnostics = mutableListOf<String>()
        var passwordSkipped = 0

        fun usable(node: UiNode): Boolean {
            if (node.isPassword && !query.matchPasswordFields) {
                passwordSkipped++
                return false
            }
            if (query.enabled == null && !node.isEnabled) return false
            if (query.enabled != null && node.isEnabled != query.enabled) return false
            query.className?.let { wanted ->
                if (!classMatches(node.className, wanted)) return false
            }
            query.editable?.let { if (node.isEditable != it) return false }
            query.clickable?.let { if (it && !node.isClickable) return false }
            query.scrollable?.let { if (node.isScrollable != it) return false }
            query.checked?.let { if (node.isChecked != it) return false }
            query.focused?.let { if (it && !node.isFocused) return false }
            return true
        }

        val pool = nodes.filter { usable(it) }
        if (passwordSkipped > 0) {
            diagnostics += "skipped $passwordSkipped password field(s)"
        }

        // Tier 1 - resource id: the most stable locator an app can offer.
        tier(pool, MatchTier.VIEW_ID, query) { node, q ->
            val byFull = q.viewId?.let { node.viewIdResourceName == it } == true
            val byName = q.idName?.let { TextMatch.equalsLoose(node.idName, it) } == true
            if (!byFull && !byName) return@tier null
            1.0f + (if (node.isInteractive) 0.1f else 0f)
        }?.let { return finish(it, diagnostics, query, maxResults) }

        // Tier 2 - exact text.
        tier(pool, MatchTier.EXACT_TEXT, query) { node, q ->
            q.text?.let { wanted ->
                if (TextMatch.equalsLoose(node.text, wanted)) return@tier 1.0f + bonus(node)
            }
            null
        }?.let { return finish(it, diagnostics, query, maxResults) }

        // Tier 3 - exact content description (icon buttons, which have no text).
        tier(pool, MatchTier.CONTENT_DESCRIPTION, query) { node, q ->
            q.contentDescription?.let { wanted ->
                if (TextMatch.equalsLoose(node.contentDescription, wanted)) return@tier 1.0f + bonus(node)
            }
            null
        }?.let { return finish(it, diagnostics, query, maxResults) }

        // Tier 4 - partial text / description.
        tier(pool, MatchTier.TEXT_CONTAINS, query) { node, q ->
            var score: Float? = null
            q.textContains?.let { wanted ->
                if (TextMatch.contains(node.text, wanted) || TextMatch.contains(node.contentDescription, wanted)) {
                    score = maxOf(score ?: 0f, 0.85f + bonus(node))
                }
            }
            q.textStartsWith?.let { wanted ->
                if (TextMatch.startsWith(node.text, wanted) || TextMatch.startsWith(node.contentDescription, wanted)) {
                    score = maxOf(score ?: 0f, 0.8f + bonus(node))
                }
            }
            q.descriptionContains?.let { wanted ->
                if (TextMatch.contains(node.contentDescription, wanted)) {
                    score = maxOf(score ?: 0f, 0.8f + bonus(node))
                }
            }
            // A text query that missed exactly may still hit as a substring;
            // that is a weaker signal than an explicit contains, so it scores less.
            q.text?.let { wanted ->
                if (wanted.length >= 3 && TextMatch.contains(node.text, wanted)) {
                    score = maxOf(score ?: 0f, 0.7f + bonus(node))
                }
                if (wanted.length >= 3 && TextMatch.contains(node.contentDescription, wanted)) {
                    score = maxOf(score ?: 0f, 0.68f + bonus(node))
                }
            }
            score
        }?.let { return finish(it, diagnostics, query, maxResults) }

        // Tier 5 - semantic properties only (e.g. "the focused editable field").
        val hasSemanticQuery = query.editable != null || query.clickable != null ||
            query.scrollable != null || query.focused != null || query.checked != null ||
            !query.className.isNullOrBlank()
        if (hasSemanticQuery) {
            val semantic = pool.mapNotNull { node ->
                var score = 0.5f + bonus(node)
                if (query.focused == true && node.isFocused) score += 0.3f
                if (query.editable == true && node.isEditable) score += 0.2f
                if (query.scrollable == true && node.isScrollable) score += 0.2f
                // Position order breaks ties deterministically: top-most first.
                score -= (node.bounds.top.toFloat() / 100000f)
                MatchedElement(node, Point(node.bounds.centerX, node.bounds.centerY), MatchTier.SEMANTIC, score, "semantic properties")
            }
            if (semantic.isNotEmpty()) return finish(semantic, diagnostics, query, maxResults)
        }

        // Tier 6 - relative structure ("the switch next to Wi-Fi").
        query.nearText?.let { anchorText ->
            val anchors = nodes.filter {
                TextMatch.equalsLoose(it.text, anchorText) ||
                    TextMatch.equalsLoose(it.contentDescription, anchorText) ||
                    TextMatch.contains(it.text, anchorText)
            }
            val position = query.relativePosition ?: RelativePosition.NEAREST
            if (anchors.isEmpty()) {
                diagnostics += "relative anchor \"$anchorText\" not found"
            } else {
                val candidates = pool.filter { candidate -> anchors.none { it.key == candidate.key } }
                val relative = mutableListOf<MatchedElement>()
                anchors.forEach { anchor ->
                    candidates.forEach { candidate ->
                        val score = relativeScore(anchor, candidate, position)
                        if (score > 0f) {
                            relative += MatchedElement(
                                node = candidate,
                                point = Point(candidate.bounds.centerX, candidate.bounds.centerY),
                                tier = MatchTier.RELATIVE,
                                score = score,
                                reason = "${position.name.lowercase()} \"${anchor.label?.take(24)}\""
                            )
                        }
                    }
                }
                if (relative.isNotEmpty()) return finish(relative, diagnostics, query, maxResults)
                diagnostics += "nothing is ${position.name.lowercase()} \"$anchorText\""
            }
        }

        // Tier 7 - approximate bounds from a recording.
        query.bounds?.let { wanted ->
            val overlapping = pool.mapNotNull { node ->
                val overlap = node.bounds.overlapRatio(wanted)
                if (overlap < 0.25f) return@mapNotNull null
                var score = 0.3f + overlap * 0.5f
                query.className?.let { if (classMatches(node.className, it)) score += 0.15f }
                query.idName?.let { if (TextMatch.equalsLoose(node.idName, it)) score += 0.2f }
                MatchedElement(node, Point(node.bounds.centerX, node.bounds.centerY), MatchTier.APPROX_BOUNDS, score, "overlaps recorded bounds ${(overlap * 100).toInt()}%")
            }
            if (overlapping.isNotEmpty()) return finish(overlapping, diagnostics, query, maxResults)

            // Nothing overlaps: fall back to the closest node to the recorded centre.
            val centre = Point(wanted.centerX, wanted.centerY)
            val nearest = pool
                .map { node -> node to distance(node.bounds.centerX, node.bounds.centerY, centre.x, centre.y) }
                .filter { it.second < NEAREST_FALLBACK_PX }
                .sortedBy { it.second }
                .take(1)
            nearest.firstOrNull()?.let { (node, dist) ->
                return finish(
                    listOf(
                        MatchedElement(
                            node, Point(node.bounds.centerX, node.bounds.centerY), MatchTier.APPROX_BOUNDS,
                            0.2f, "closest node to recorded centre (${dist.toInt()}px away)"
                        )
                    ),
                    diagnostics + "recorded bounds matched nothing; used nearest node",
                    query, maxResults
                )
            }
            diagnostics += "recorded bounds matched nothing within ${NEAREST_FALLBACK_PX}px"
        }

        // Tier 8 - OCR text (only populated when Level 3 ran).
        val ocrNeedle = query.ocrText ?: query.text ?: query.textContains
        if (ocrNeedle != null && snapshot.ocrLines.isNotEmpty()) {
            val hits = snapshot.ocrLines.filter { TextMatch.contains(it.text, ocrNeedle) }
            if (hits.isNotEmpty()) {
                return finish(
                    hits.map {
                        MatchedElement(
                            node = null,
                            point = Point(it.bounds.centerX, it.bounds.centerY),
                            tier = MatchTier.OCR,
                            score = 0.4f + it.confidence * 0.3f,
                            reason = "ocr \"${it.text.take(24)}\""
                        )
                    },
                    diagnostics, query, maxResults
                )
            }
            diagnostics += "ocr saw ${snapshot.ocrLines.size} line(s), none contained \"$ocrNeedle\""
        }

        // Tier 9 - raw coordinates. Last resort, and the resolver says so.
        query.point?.let { p ->
            return finish(
                listOf(MatchedElement(null, p, MatchTier.COORDINATES, 0.1f, "raw coordinates")),
                diagnostics + "using raw coordinates; this will break if the layout moves",
                query, maxResults
            )
        }
        query.bounds?.let { b ->
            return finish(
                listOf(MatchedElement(null, Point(b.centerX, b.centerY), MatchTier.COORDINATES, 0.05f, "centre of recorded bounds")),
                diagnostics + "using the centre of recorded bounds as a coordinate tap",
                query, maxResults
            )
        }

        diagnostics += suggest(snapshot, query)
        return Resolution(emptyList(), null, false, diagnostics)
    }

    // ------------------------------------------------------------------ tiers

    private inline fun tier(
        pool: List<UiNode>,
        tier: MatchTier,
        query: ElementQuery,
        score: (UiNode, ElementQuery) -> Float?
    ): List<MatchedElement>? {
        val out = mutableListOf<MatchedElement>()
        for (node in pool) {
            val s = score(node, query) ?: continue
            out += MatchedElement(
                node = node,
                point = Point(node.bounds.centerX, node.bounds.centerY),
                tier = tier,
                score = s,
                reason = "${tier.label}: ${node.label?.take(28) ?: node.simpleClassName ?: "?"}"
            )
        }
        return if (out.isEmpty()) null else out
    }

    private fun bonus(node: UiNode): Float {
        var b = 0f
        if (node.isClickable) b += 0.12f
        if (node.isEditable) b += 0.06f
        if (node.isEnabled) b += 0.04f
        if (node.isFocused) b += 0.03f
        // Prefer reasonably sized targets; a 1x1 px "match" is usually a divider.
        if (node.bounds.width in 24..2000 && node.bounds.height in 20..600) b += 0.05f
        return b
    }

    private fun classMatches(actual: String?, wanted: String): Boolean {
        if (actual == null || wanted.isBlank()) return false
        val a = actual.substringAfterLast('.')
        val w = wanted.substringAfterLast('.')
        return a.equals(w, ignoreCase = true) || actual.equals(wanted, ignoreCase = true) ||
            a.endsWith(w, ignoreCase = true)
    }

    private fun relativeScore(anchor: UiNode, candidate: UiNode, position: RelativePosition): Float {
        val a = anchor.bounds
        val c = candidate.bounds
        val tolerance = 8
        val hOverlap = c.left < a.right && c.right > a.left
        val vOverlap = c.top < a.bottom && c.bottom > a.top
        val dist = distance(a.centerX, a.centerY, c.centerX, c.centerY)
        val closeness = (1f - (dist / 1200f)).coerceIn(0f, 1f) * 0.4f

        return when (position) {
            RelativePosition.BELOW -> if (c.top >= a.bottom - tolerance && hOverlap) 0.5f + closeness else 0f
            RelativePosition.ABOVE -> if (c.bottom <= a.top + tolerance && hOverlap) 0.5f + closeness else 0f
            RelativePosition.RIGHT_OF -> if (c.left >= a.right - tolerance && vOverlap) 0.5f + closeness else 0f
            RelativePosition.LEFT_OF -> if (c.right <= a.left + tolerance && vOverlap) 0.5f + closeness else 0f
            RelativePosition.INSIDE -> if (c.left >= a.left && c.right <= a.right && c.top >= a.top && c.bottom <= a.bottom) 0.6f + closeness else 0f
            RelativePosition.NEAREST -> if (dist < 600) 0.3f + closeness else 0f
        }
    }

    private fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Float {
        val dx = (x1 - x2).toFloat()
        val dy = (y1 - y2).toFloat()
        return sqrt(dx * dx + dy * dy)
    }

    /** Deterministic ordering: best score first, then reading order. */
    private fun finish(
        matches: List<MatchedElement>,
        diagnostics: List<String>,
        query: ElementQuery,
        maxResults: Int
    ): Resolution {
        if (matches.isEmpty()) return Resolution(matches, null, false, diagnostics)

        val sorted = matches.sortedWith(
            compareByDescending<MatchedElement> { it.score }
                .thenBy { it.node?.bounds?.top ?: Int.MAX_VALUE }
                .thenBy { it.node?.bounds?.left ?: Int.MAX_VALUE }
        )

        val index = query.index
        val selected = if (index > 0 && index < sorted.size) {
            listOf(sorted[index])
        } else {
            sorted.take(maxResults)
        }

        val topScore = selected.first().score
        val equalTop = sorted.count { abs(it.score - topScore) < 0.001f }
        val ambiguous = equalTop > 1 && selected.size > 1 && matches.size > 2

        val notes = diagnostics.toMutableList()
        if (index > 0 && index >= sorted.size) {
            notes += "index=$index requested but only ${sorted.size} candidate(s); used the best one"
        }
        if (ambiguous) notes += "${equalTop} candidates scored equally; picked the top-most"

        return Resolution(selected, selected.first().tier, ambiguous, notes)
    }

    /** "Did you mean..." - the single most useful thing in the debug panel. */
    private fun suggest(snapshot: ScreenSnapshot, query: ElementQuery): String {
        val wanted = query.primaryLocator()
        if (wanted.isBlank() || wanted == "any") return "nothing matched"
        val labelled = snapshot.nodes.filter { !it.label.isNullOrBlank() && it.isOnScreen }
        val close = labelled
            .map { it to TextMatch.similarity(it.label, wanted) }
            .filter { it.second >= 0.45f }
            .sortedByDescending { it.second }
            .take(4)
        return if (close.isEmpty()) {
            "nothing similar to \"$wanted\" among ${labelled.size} labelled node(s)"
        } else {
            "closest to \"$wanted\": " + close.joinToString(", ") {
                "\"${it.first.label?.take(24)}\" (${(it.second * 100).toInt()}%)"
            }
        }
    }

    companion object {
        private const val NEAREST_FALLBACK_PX = 160f
    }
}

/** Small helper used by the recorder to build a query from a node. */
fun UiNode.toQuery(allowBoundsFallback: Boolean = true): ElementQuery {
    val text = this.text?.takeIf { it.isNotBlank() }?.trim()
    val desc = this.contentDescription?.takeIf { it.isNotBlank() }?.trim()
    return ElementQuery(
        viewId = viewIdResourceName?.takeIf { it.isNotBlank() },
        idName = idName,
        text = text,
        contentDescription = desc,
        className = className?.takeIf { text == null && desc == null },
        editable = if (isEditable) true else null,
        clickable = if (isClickable && text == null && desc == null) true else null,
        scrollable = if (isScrollable) true else null,
        bounds = if (allowBoundsFallback && text == null && desc == null && viewIdResourceName == null) bounds else null,
        label = (text ?: desc ?: idName ?: simpleClassName)?.take(48),
        matchPasswordFields = false
    )
}

/** Bounds of a snapshot, used when a gesture needs the screen size. */
fun ScreenSnapshot.screenBounds(): Bounds = Bounds(0, 0, displayWidth, displayHeight)
