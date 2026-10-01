package com.deuty.agent.agent

import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.util.TextMatch

/**
 * "Why is this screen not the screen I expected?" (requirement 19).
 *
 * Recovery starts with diagnosis. Most automation failures are not mysterious:
 * a login wall appeared, the network dropped, a permission dialog is covering
 * the app, or the app crashed. Each of those has a *specific* right answer, and
 * three of them (permission dialog, payment sheet, captcha) have the same right
 * answer: stop and hand the phone back to the user.
 *
 * Detection is text-based over the accessibility tree, which is why it costs
 * nothing and works offline. It is a heuristic and it is allowed to be wrong in
 * the direction of "ask the user" - never in the direction of "carry on".
 */
object ScreenDiagnostics {

    enum class Blocker(val autoRecoverable: Boolean) {
        NONE(true),
        PERMISSION_DIALOG(false),
        LOGIN_REQUIRED(false),
        PAYMENT_SHEET(false),
        CAPTCHA(false),
        CRASH_DIALOG(true),
        UPDATE_PROMPT(true),
        NETWORK_ERROR(true),
        STORAGE_FULL(false),
        BATTERY_OPTIMISATION(true),
        COOKIE_CONSENT(true),
        TUTORIAL(true),
        APP_NOT_INSTALLED(false),
        RATE_LIMITED(true),
        SECURE_SCREEN(false)
    }

    data class Diagnosis(
        val blocker: Blocker,
        val evidence: String,
        val question: String?,
        val canAutoRecover: Boolean = blocker.autoRecoverable
    ) {
        val isBlocking: Boolean get() = blocker != Blocker.NONE
    }

    fun diagnose(screen: ScreenSnapshot): Diagnosis {
        if (screen.isSecure) {
            return Diagnosis(Blocker.SECURE_SCREEN, "window is secure", "This screen is protected. Can you do this part yourself?")
        }
        val pkg = screen.packageName ?: ""
        if (RiskManager.SYSTEM_GUARD_PACKAGES.any { pkg.startsWith(it) }) {
            return Diagnosis(
                Blocker.PERMISSION_DIALOG,
                "package $pkg",
                "Android is asking for a permission. Please answer it - I won't touch system dialogs."
            )
        }
        val text = TextMatch.normalize(screen.visibleText())
        if (text.isBlank()) {
            return Diagnosis(Blocker.NONE, "no text on screen", null)
        }

        fun firstHit(vararg candidates: String): String? = candidates.firstOrNull { text.contains(it) }

        firstHit("allow", "don't allow", "while using the app", "only this time", "deny", "grant access")?.let {
            // Only treat it as a permission dialog when it looks like one, i.e.
            // there is also an allow/deny pair. Many apps have an "Allow" button.
            if (text.contains("don't allow") || text.contains("only this time") || text.contains("while using")) {
                return Diagnosis(Blocker.PERMISSION_DIALOG, "permission-like dialog ($it)",
                    "A permission dialog is on screen. Please answer it, then I'll continue.")
            }
        }

        firstHit("sign in", "log in", "login", "create account", "continue with google", "session expired", "session has expired")?.let {
            return Diagnosis(Blocker.LOGIN_REQUIRED, "\"$it\" on screen",
                "You're not signed in here (I see \"$it\"). Sign in and I'll carry on?")
        }

        firstHit("enter otp", "verify payment", "confirm payment", "amount payable", "add card", "upi pin")?.let {
            return Diagnosis(Blocker.PAYMENT_SHEET, "\"$it\" on screen",
                "This is a payment screen. I won't touch it - please complete or cancel it.")
        }

        firstHit("i'm not a robot", "select all images", "captcha", "verify you are human")?.let {
            return Diagnosis(Blocker.CAPTCHA, "\"$it\" on screen", "There's a captcha. Please solve it and I'll continue.")
        }

        firstHit("has stopped", "isn't responding", "app has crashed", "force close", "report a problem")?.let {
            return Diagnosis(Blocker.CRASH_DIALOG, "\"$it\" on screen", null)
        }

        firstHit("update available", "update now", "new version", "requires update")?.let {
            return Diagnosis(Blocker.UPDATE_PROMPT, "\"$it\" on screen", null)
        }

        firstHit("no internet", "no connection", "check your connection", "offline", "network error", "unable to load", "something went wrong")?.let {
            return Diagnosis(Blocker.NETWORK_ERROR, "\"$it\" on screen", null)
        }

        firstHit("storage full", "not enough space", "free up space")?.let {
            return Diagnosis(Blocker.STORAGE_FULL, "\"$it\" on screen", "Storage is full - I can't finish this. Free up some space?")
        }

        firstHit("battery optimization", "battery optimisation", "ignore battery")?.let {
            return Diagnosis(Blocker.BATTERY_OPTIMISATION, "\"$it\" on screen", null)
        }

        firstHit("accept all cookies", "accept cookies", "cookie policy", "got it")?.let {
            return Diagnosis(Blocker.COOKIE_CONSENT, "\"$it\" on screen", null)
        }

        firstHit("skip", "next", "get started")?.let {
            if (screen.nodes.size < 40) {
                return Diagnosis(Blocker.TUTORIAL, "sparse screen with \"$it\"", null)
            }
        }

        firstHit("too many requests", "try again later", "rate limit")?.let {
            return Diagnosis(Blocker.RATE_LIMITED, "\"$it\" on screen", null)
        }

        firstHit("app not installed", "not installed", "couldn't find app")?.let {
            return Diagnosis(Blocker.APP_NOT_INSTALLED, "\"$it\" on screen", "That app doesn't seem to be installed. Install it, or should I use another one?")
        }

        return Diagnosis(Blocker.NONE, "no blocker detected", null)
    }
}
