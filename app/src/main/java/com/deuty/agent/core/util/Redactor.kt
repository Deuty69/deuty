package com.deuty.agent.core.util

/**
 * Redaction (requirement 23).
 *
 * The agent can see whatever the user can see, which includes passwords, OTPs
 * and card numbers. Nothing is allowed to reach the persisted task history, the
 * debug log, a workflow file or an "AI provider" without passing through here.
 *
 * The rules are deliberately over-eager: a false positive that hides a year is
 * annoying, a false negative that stores a password is not acceptable.
 */
object Redactor {

    private const val MASK = "\u2022\u2022\u2022\u2022"

    private val DIGIT_RUN = Regex("\\d{4,19}")
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@([A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})")
    private val LONG_ALNUM_TOKEN = Regex("\\b[A-Za-z0-9_\\-]{20,}\\b")

    /** Words after which the rest of the line is presumed secret. */
    private val SECRET_KEYWORDS = listOf(
        "password", "passwd", "pwd", "passcode", "pass phrase", "passphrase",
        "otp", "one time", "one-time", "pin", "cvv", "cvv2", "cvc",
        "secret", "private key", "seed phrase", "recovery code", "token",
        "credit card", "card number", "card no", "debit card", "account number",
        "account no", "ifsc", "upi id", "vpa", "ssn", "national id", "aadhaar",
        "security answer", "2fa", "mfa code", "verification code", "auth code"
    )

    /** Words that mark a *field* as sensitive, used before typing into it. */
    private val SENSITIVE_FIELD_HINTS = listOf(
        "password", "passwd", "pwd", "otp", "pin", "cvv", "cvv2", "cvc",
        "secret", "card number", "card no", "account number", "account no",
        "upi", "aadhaar", "ssn", "verification code", "security code",
        "two-factor", "2fa", "recovery"
    )

    fun text(input: String?, enabled: Boolean = true): String? {
        if (input == null) return null
        if (!enabled) return input
        if (input.isEmpty()) return input

        var out = input
        // 1. Whole-line masking after a secret keyword: "OTP: 483920" -> "OTP: ***"
        for (keyword in SECRET_KEYWORDS) {
            val idx = out.lowercase().indexOf(keyword)
            if (idx >= 0) {
                val lineStart = out.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
                val lineEnd = out.indexOf('\n', idx).let { if (it < 0) out.length else it }
                val line = out.substring(lineStart, lineEnd)
                out = out.substring(0, lineStart) + maskAfterKeyword(line, keyword) + out.substring(lineEnd)
            }
        }
        // 2. Structured secrets.
        out = EMAIL.replace(out) { m -> MASK + "@" + m.groupValues[1] }
        out = LONG_ALNUM_TOKEN.replace(out) { MASK }
        out = DIGIT_RUN.replace(out) { m -> maskDigits(m.value) }
        return out
    }

    private fun maskAfterKeyword(line: String, keyword: String): String {
        val lower = line.lowercase()
        val idx = lower.indexOf(keyword)
        if (idx < 0) return line
        val cut = idx + keyword.length
        // Keep "Password" but hide everything after the separator that follows.
        val tail = line.substring(cut)
        val separator = tail.indexOfFirst { it == ':' || it == '=' || it == ' ' }
        val head = if (separator < 0) tail else tail.substring(0, separator + 1)
        return line.substring(0, cut) + head + MASK
    }

    private fun maskDigits(digits: String): String = when {
        digits.length >= 12 -> MASK // card numbers, account numbers
        digits.length >= 6 -> MASK // OTPs
        else -> digits.first() + MASK.take(3) // 4-5 digits: keep a hint, hide the rest
    }

    /** True when a field/label looks like it must never be auto-filled. */
    fun isSensitiveLabel(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val lower = label.lowercase()
        return SENSITIVE_FIELD_HINTS.any { lower.contains(it) }
    }

    /** Redact every value of a map (used for task context). */
    fun map(input: Map<String, String>, enabled: Boolean = true): Map<String, String> =
        if (!enabled) input else input.mapValues { (k, v) ->
            if (isSensitiveLabel(k)) MASK else (text(v, true) ?: v)
        }

    /** Redact a list of strings, dropping empties. */
    fun list(input: List<String>, enabled: Boolean = true): List<String> =
        input.mapNotNull { text(it, enabled) }
}
