package com.deuty.agent.agent

import com.deuty.agent.agent.executor.AgentAction
import com.deuty.agent.agent.executor.AskUser
import com.deuty.agent.agent.executor.Conditional
import com.deuty.agent.agent.executor.LaunchApp
import com.deuty.agent.agent.executor.Paste
import com.deuty.agent.agent.executor.ReadScreen
import com.deuty.agent.agent.executor.Repeat
import com.deuty.agent.agent.executor.Screenshot
import com.deuty.agent.agent.executor.Type
import com.deuty.agent.core.model.AgentTask
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.agent.port.ExecContext
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Redactor
import com.deuty.agent.core.util.TextMatch

/**
 * Risk gating (requirement 14).
 *
 * The rule that makes this safe: risk can only ever be *raised*. An action
 * declares a floor ([AgentAction.baseRisk]); this manager adds evidence from the
 * target text, the current screen and the package the agent is in. No planner,
 * workflow or recording can lower a risk level, so a malicious or confused plan
 * cannot talk its way past a payment screen.
 */
class RiskManager(private val log: AgentLog) {

    data class Assessment(
        val level: RiskLevel,
        val reasons: List<String>,
        val requiresConfirmation: Boolean,
        val refuse: Boolean,
        val explanation: String
    ) {
        companion object {
            fun allow(level: RiskLevel, reasons: List<String>, confirmAt: RiskLevel) = Assessment(
                level = level,
                reasons = reasons,
                requiresConfirmation = level.atLeast(confirmAt) && level != RiskLevel.FORBIDDEN,
                refuse = level == RiskLevel.FORBIDDEN,
                explanation = reasons.joinToString("; ").ifBlank { "low risk" }
            )

            fun refuse(reasons: List<String>) = Assessment(
                level = RiskLevel.FORBIDDEN,
                reasons = reasons,
                requiresConfirmation = false,
                refuse = true,
                explanation = reasons.joinToString("; ")
            )
        }
    }

    fun assess(
        action: AgentAction,
        screen: ScreenSnapshot,
        ctx: ExecContext,
        task: AgentTask? = null
    ): Assessment {
        val reasons = mutableListOf<String>()
        var level = action.baseRisk
        val pkg = screen.packageName ?: ""

        fun raise(to: RiskLevel, why: String) {
            if (to.rank > level.rank) level = to
            reasons += why
        }

        // --- 1. Screens we never touch ------------------------------------
        if (screen.isSecure) {
            return Assessment.refuse(listOf("screen is marked secure (FLAG_SECURE or payment surface)"))
        }
        if (ctx.blockSensitiveScreens) {
            SYSTEM_GUARD_PACKAGES.firstOrNull { pkg.startsWith(it) }?.let {
                return Assessment.refuse(
                    listOf("Android is showing a system permission/security dialog ($pkg). I will not answer it for you.")
                )
            }
            if (pkg.isNotEmpty() && SENSITIVE_PACKAGE_HINTS.any { pkg.contains(it) }) {
                raise(RiskLevel.HIGH, "financial/security app ($pkg)")
            }
        }

        // --- 2. What the action is aimed at --------------------------------
        val targetText = action.targetQuery()?.let { q ->
            listOfNotNull(q.text, q.textContains, q.textStartsWith, q.contentDescription, q.descriptionContains, q.label)
                .joinToString(" ")
        } ?: ""
        val normalizedTarget = TextMatch.normalize(targetText)

        HIGH_RISK_TERMS.firstOrNull { normalizedTarget.contains(it) }?.let {
            raise(RiskLevel.HIGH, "target \"$it\" is irreversible or financial")
        }
        if (level.rank < RiskLevel.MEDIUM.rank) {
            MEDIUM_RISK_TERMS.firstOrNull { normalizedTarget.contains(it) }?.let {
                raise(RiskLevel.MEDIUM, "target \"$it\" changes state outside the app")
            }
        }

        // --- 3. Action-specific rules --------------------------------------
        when (action) {
            is Type -> {
                if (TextMatch.looksLikeCode(action.text)) raise(RiskLevel.HIGH, "typing an OTP/verification code")
                if (Redactor.isSensitiveLabel(action.text)) raise(RiskLevel.HIGH, "text looks like a secret")
            }
            is Paste -> raise(RiskLevel.MEDIUM, "pasting clipboard content into another app")
            is Screenshot -> raise(RiskLevel.MEDIUM, "capturing the screen is a privacy-sensitive act")
            is ReadScreen -> Unit // reading is the cheapest, safest thing we do
            is LaunchApp -> Unit
            is AskUser -> Unit
            is Conditional -> level = maxOf(level, action.children().maxRisk())
            is Repeat -> {
                level = maxOf(level, action.children().maxRisk())
                if (action.maxTimes > 5) raise(RiskLevel.MEDIUM, "repeating ${action.maxTimes} times")
            }
            else -> Unit
        }

        // --- 4. Screen context ---------------------------------------------
        val screenText = TextMatch.normalize(screen.visibleText()).take(4000)
        PAYMENT_CONTEXT.firstOrNull { screenText.contains(it) }?.let {
            if (level.rank >= RiskLevel.MEDIUM.rank || action.targetQuery() != null) {
                raise(RiskLevel.HIGH, "screen mentions \"$it\"")
            }
        }
        if (screenText.contains("sign in") || screenText.contains("log in")) {
            if (action.targetQuery() != null && level.rank < RiskLevel.MEDIUM.rank) {
                raise(RiskLevel.MEDIUM, "screen asks for a login")
            }
        }

        // --- 5. Task-level ceiling -----------------------------------------
        val maxRisk = task?.constraints?.maxRisk ?: RiskLevel.HIGH
        if (level.rank > maxRisk.rank) {
            return Assessment.refuse(listOf("task allows up to $maxRisk, this step is $level"))
        }

        val assessment = Assessment.allow(level, reasons.distinct(), ctx.confirmAtOrAbove)
        if (assessment.requiresConfirmation) {
            log.i(TAG, "risk gate: ${action.describe()} -> $level (${assessment.explanation})")
        }
        return assessment
    }

    /** Highest risk across a list of actions (used by plans and workflows). */
    fun highest(actions: List<AgentAction>): RiskLevel = actions.maxRisk()

    private fun List<AgentAction>.maxRisk(): RiskLevel =
        fold(RiskLevel.LOW) { acc, a -> if (a.baseRisk.rank > acc.rank) a.baseRisk else acc }

    companion object {
        private const val TAG = "risk"

        /** Packages whose dialogs the agent must never answer itself. */
        val SYSTEM_GUARD_PACKAGES = listOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.settings.intelligence",
            "com.android.systemui"
        )

        /** Substrings that mark an app as financial/security-sensitive. */
        val SENSITIVE_PACKAGE_HINTS = listOf(
            "bank", "pay", "wallet", "money", "invest", "broker", "trading",
            "crypto", "finance", "loan", "insurance", "upi", "authenticator"
        )

        val HIGH_RISK_TERMS = listOf(
            "pay now", "pay ", "payment", "purchase", "buy now", "checkout", "place order",
            "confirm order", "send money", "transfer", "subscribe", "unsubscribe",
            "delete account", "close account", "factory reset", "erase all", "format",
            "delete all", "remove all", "clear all data", "reset password", "change password",
            "change pin", "add card", "link account", "verify identity", "kyc",
            "uninstall", "revoke", "sign out of all", "install now", "update now"
        )

        val MEDIUM_RISK_TERMS = listOf(
            "send", "post", "share", "submit", "publish", "upload", "save", "apply",
            "delete", "remove", "install", "update", "enable", "disable", "allow",
            "grant", "sign out", "log out", "connect", "pair", "sync", "restore",
            "confirm", "next", "continue", "done", "ok"
        )

        val PAYMENT_CONTEXT = listOf(
            "amount", "total payable", "otp", "upi", "card number", "cvv", "expire",
            "payment method", "₹", "$", "€", "£", "invoice", "billing", "emi"
        )
    }
}
