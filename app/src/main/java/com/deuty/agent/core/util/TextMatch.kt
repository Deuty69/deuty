package com.deuty.agent.core.util

/**
 * Text comparison for element matching.
 *
 * Real UI text is noisy: non-breaking spaces from HTML, unicode dashes and
 * quotes, doubled whitespace from RTL/bidi markers, and inconsistent casing
 * between the label and the content description. Matching raw strings makes the
 * agent look broken on screens that a human reads instantly, so every
 * comparison in the resolver goes through [normalize] first.
 *
 * Cost matters: this runs over every visible node on every step, so the
 * implementation is single-pass and allocation-light, and [levenshtein] is
 * bounded (it gives up instead of computing a full matrix for long strings).
 */
object TextMatch {

    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val sb = StringBuilder(input.length)
        var lastWasSpace = false
        for (ch in input) {
            val c = when (ch) {
                '\u00A0', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005',
                '\u2006', '\u2007', '\u2008', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000' -> ' '
                '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-'
                '\u2018', '\u2019', '\u201A', '\u201B', '\u00B4', '\u0060' -> '\''
                '\u201C', '\u201D', '\u201E', '\u201F' -> '"'
                '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\uFEFF' -> '\u0000'
                else -> ch
            }
            if (c == '\u0000') continue
            if (c.isWhitespace()) {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(c.lowercaseChar())
                lastWasSpace = false
            }
        }
        return sb.toString().trim()
    }

    fun equalsLoose(a: String?, b: String?): Boolean {
        val na = normalize(a)
        if (na.isEmpty()) return false
        return na == normalize(b)
    }

    fun contains(haystack: String?, needle: String?): Boolean {
        val n = normalize(needle)
        if (n.isEmpty()) return false
        return normalize(haystack).contains(n)
    }

    fun startsWith(haystack: String?, prefix: String?): Boolean {
        val p = normalize(prefix)
        if (p.isEmpty()) return false
        return normalize(haystack).startsWith(p)
    }

    fun tokens(input: String?): List<String> =
        normalize(input).split(' ').filter { it.isNotEmpty() }

    /**
     * 0..1 similarity, tuned for short UI labels.
     * Combines token overlap (order-insensitive, which is what saves us when an
     * app reorders words) with a bounded edit distance (which catches typos and
     * truncation like "Downloa").
     */
    fun similarity(a: String?, b: String?): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0f
        if (na == nb) return 1f

        val ta = tokens(na)
        val tb = tokens(nb)
        val tokenScore = if (ta.isEmpty() || tb.isEmpty()) 0f else {
            val shared = ta.count { it in tb }.toFloat()
            shared / maxOf(ta.size, tb.size).toFloat()
        }

        val maxLen = maxOf(na.length, nb.length)
        val distance = if (maxLen > 24) Int.MAX_VALUE / 2 else levenshtein(na, nb, max = 8)
        val charScore = if (distance >= Int.MAX_VALUE / 2) 0f else 1f - (distance.toFloat() / maxLen.toFloat())

        // Prefix match ("Timet" vs "Timetable") is very common after truncation.
        val prefixScore = if (na.startsWith(nb) || nb.startsWith(na)) {
            minOf(na.length, nb.length).toFloat() / maxLen.toFloat()
        } else 0f

        return maxOf(tokenScore * 0.55f + charScore * 0.45f, prefixScore * 0.9f).coerceIn(0f, 1f)
    }

    /** Bounded Levenshtein: returns [max] as soon as the distance cannot be < max. */
    fun levenshtein(a: String, b: String, max: Int = 8): Int {
        if (a == b) return 0
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > max) return max
        if (la == 0) return lb.coerceAtMost(max)
        if (lb == 0) return la.coerceAtMost(max)

        var prev = IntArray(lb + 1) { it }
        var curr = IntArray(lb + 1)
        for (i in 1..la) {
            curr[0] = i
            var rowMin = curr[0]
            val ca = a[i - 1]
            for (j in 1..lb) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > max) return max
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[lb].coerceAtMost(max)
    }

    /** True for strings that are mostly digits/secret-shaped (used by redaction hints). */
    fun looksLikeCode(input: String?): Boolean {
        val n = input?.trim() ?: return false
        if (n.length < 4) return false
        val digits = n.count { it.isDigit() }
        return digits.toFloat() / n.length.toFloat() > 0.6f
    }
}
