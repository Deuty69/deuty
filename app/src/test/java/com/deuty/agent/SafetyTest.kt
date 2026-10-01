package com.deuty.agent

import com.deuty.agent.agent.RiskManager
import com.deuty.agent.agent.ScreenDiagnostics
import com.deuty.agent.agent.executor.AgentAction
import com.deuty.agent.agent.executor.Screenshot
import com.deuty.agent.agent.executor.Tap
import com.deuty.agent.agent.executor.Type
import com.deuty.agent.agent.port.ExecContext
import com.deuty.agent.core.model.AgentTask
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.TaskConstraints
import com.deuty.agent.core.model.TaskSource
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Risk gating, refusals and redaction (requirements 8, 14, 16, 23).
 *
 * These are the tests that protect the user from the agent. Every one of them
 * asserts a *refusal* or an *escalation*, because the failure mode that matters is
 * not "the agent did nothing" but "the agent tapped Pay Now without asking".
 */
class SafetyTest {

    private val clock = testClock()
    private val log = testLog(clock)
    private val risk = RiskManager(log)
    private val ctx = ExecContext()

    private fun assess(
        action: AgentAction,
        screen: ScreenSnapshot,
        task: AgentTask? = null,
        context: ExecContext = ctx
    ) = risk.assess(action, screen, context, task)

    private fun plainScreen(packageName: String = "com.example.app", secure: Boolean = false) = screenOf(
        packageName = packageName,
        secure = secure,
        nodes = listOf(uiNode(1, text = "Wi-Fi"), uiNode(2, text = "Bluetooth")),
        clock = clock
    )

    // ------------------------------------------------------------------ risk

    @Test
    fun paymentTargetsAreHighRiskAndGated() {
        val assessment = assess(Tap(ElementQuery.byAnyLabel("Pay now")), plainScreen())
        assertEquals(RiskLevel.HIGH, assessment.level)
        assertTrue(assessment.requiresConfirmation)
        assertFalse(assessment.refuse)
        assertTrue(assessment.explanation.isNotBlank())
    }

    @Test
    fun sendTargetsAreMediumRiskAndGated() {
        val assessment = assess(Tap(ElementQuery.byAnyLabel("Send")), plainScreen())
        assertEquals(RiskLevel.MEDIUM, assessment.level)
        assertTrue(assessment.requiresConfirmation)
    }

    @Test
    fun plainNavigationIsLowRiskAndNotGated() {
        val assessment = assess(Tap(ElementQuery.byAnyLabel("Wi-Fi")), plainScreen())
        assertEquals(RiskLevel.LOW, assessment.level)
        assertFalse(assessment.requiresConfirmation)
        assertFalse(assessment.refuse)
    }

    @Test
    fun riskNeverDropsBelowTheActionFloor() {
        val action = Tap(ElementQuery.byAnyLabel("Wi-Fi"))
        val assessment = assess(action, plainScreen())
        assertTrue(assessment.level.rank >= action.baseRisk.rank)
    }

    @Test
    fun secureScreensAreRefusedOutright() {
        val assessment = assess(Tap(ElementQuery.byAnyLabel("Anything")), plainScreen(secure = true))
        assertTrue("a secure screen must be refused, not merely gated", assessment.refuse)
    }

    @Test
    fun systemPermissionDialogsAreNeverAnswered() {
        val screen = plainScreen(packageName = "com.android.permissioncontroller")
        val assessment = assess(Tap(ElementQuery.byAnyLabel("Allow")), screen)
        assertTrue(assessment.refuse)
        assertTrue(assessment.explanation.contains("permission") || assessment.explanation.contains("system"))
    }

    @Test
    fun bankingPackagesEscalateToHigh() {
        val screen = plainScreen(packageName = "com.example.bank")
        val assessment = assess(Tap(ElementQuery.byAnyLabel("Wi-Fi")), screen)
        assertEquals(RiskLevel.HIGH, assessment.level)
        assertTrue(assessment.requiresConfirmation)
    }

    @Test
    fun typingAnOtpIsHighRisk() {
        val assessment = assess(
            Type("483920", ElementQuery(editable = true, focused = true)),
            plainScreen()
        )
        assertEquals(RiskLevel.HIGH, assessment.level)
    }

    @Test
    fun screenshotsAreTreatedAsPrivacySensitive() {
        val assessment = assess(Screenshot("test"), plainScreen())
        assertTrue(assessment.level.rank >= RiskLevel.MEDIUM.rank)
    }

    @Test
    fun aTaskCeilingRefusesAnythingAboveIt() {
        val task = AgentTask(
            id = Ids.task(),
            goal = "read only",
            source = TaskSource.USER_TEXT,
            createdAtMs = clock.nowMs(),
            constraints = TaskConstraints(maxRisk = RiskLevel.LOW),
            state = TaskState.CREATED
        )
        val assessment = assess(Tap(ElementQuery.byAnyLabel("Send")), plainScreen(), task)
        assertTrue("the plan said LOW, so a MEDIUM step must be refused", assessment.refuse)
    }

    @Test
    fun theConfirmationThresholdIsUserConfigurable() {
        val relaxed = ExecContext(confirmAtOrAbove = RiskLevel.HIGH)
        val assessment = assess(Tap(ElementQuery.byAnyLabel("Send")), plainScreen(), context = relaxed)
        assertEquals(RiskLevel.MEDIUM, assessment.level)
        assertFalse("the user raised the threshold, so MEDIUM is no longer gated", assessment.requiresConfirmation)
    }

    @Test
    fun aPaymentScreenEscalatesEvenAnOrdinaryTap() {
        val screen = screenOf(
            packageName = "com.example.shop",
            nodes = listOf(uiNode(1, text = "Total \$49.00"), uiNode(2, text = "Checkout")),
            clock = clock
        )
        val assessment = assess(Tap(ElementQuery.byAnyLabel("Checkout")), screen)
        assertTrue(assessment.level.rank >= RiskLevel.HIGH.rank)
    }

    // -------------------------------------------------------------- redaction

    @Test
    fun secretsAreMaskedBeforeTheyCanBeStored() {
        val masked = Redactor.text("Password: hunter2", true).orEmpty()
        assertFalse(masked.contains("hunter2"))

        val otp = Redactor.text("Your OTP is 483920", true).orEmpty()
        assertFalse(otp.contains("483920"))

        val card = Redactor.text("card number 4111111111111111", true).orEmpty()
        assertFalse(card.contains("4111111111111111"))

        val email = Redactor.text("contact me at someone@example.com", true).orEmpty()
        assertFalse(email.contains("someone@"))
    }

    @Test
    fun ordinaryTextSurvivesRedaction() {
        val text = "Scroll down and tap Download."
        assertEquals(text, Redactor.text(text, true))
    }

    @Test
    fun redactionCanBeSwitchedOffForLocalDebugging() {
        val text = "Password: hunter2"
        assertEquals(text, Redactor.text(text, false))
    }

    @Test
    fun sensitiveFieldLabelsAreRecognised() {
        assertTrue(Redactor.isSensitiveLabel("Enter your PIN"))
        assertTrue(Redactor.isSensitiveLabel("CVV"))
        assertFalse(Redactor.isSensitiveLabel("Search settings"))
        assertFalse(Redactor.isSensitiveLabel(null))
    }

    // ------------------------------------------------------------ diagnostics

    @Test
    fun diagnosticsSpotSecureScreens() {
        val diagnosis = ScreenDiagnostics.diagnose(plainScreen(secure = true))
        assertEquals(ScreenDiagnostics.Blocker.SECURE_SCREEN, diagnosis.blocker)
        assertTrue(diagnosis.isBlocking)
        assertFalse("a secure screen is not something we can auto-recover from", diagnosis.canAutoRecover)
    }

    @Test
    fun diagnosticsSpotPermissionDialogs() {
        val screen = screenOf(
            packageName = "com.android.permissioncontroller",
            nodes = listOf(uiNode(1, text = "Allow"), uiNode(2, text = "Don't allow")),
            clock = clock
        )
        val diagnosis = ScreenDiagnostics.diagnose(screen)
        assertEquals(ScreenDiagnostics.Blocker.PERMISSION_DIALOG, diagnosis.blocker)
        assertTrue(diagnosis.isBlocking)
    }

    @Test
    fun anOrdinaryScreenIsNotABlocker() {
        val diagnosis = ScreenDiagnostics.diagnose(plainScreen())
        assertEquals(ScreenDiagnostics.Blocker.NONE, diagnosis.blocker)
        assertFalse(diagnosis.isBlocking)
    }
}
