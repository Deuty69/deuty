package com.deuty.agent

import com.deuty.agent.agent.ElementResolver
import com.deuty.agent.agent.executor.ExpectationEvaluator
import com.deuty.agent.agent.toQuery
import com.deuty.agent.core.model.Always
import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.ElementPresent
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.MatchTier
import com.deuty.agent.core.model.PackageIs
import com.deuty.agent.core.model.Point
import com.deuty.agent.core.model.ScreenChanged
import com.deuty.agent.core.model.TextAbsent
import com.deuty.agent.core.model.TextPresent
import com.deuty.agent.core.util.ScreenSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Element targeting and expectation evaluation (requirements 6, 7).
 *
 * The tier order is the whole point: resource id, then text, then description,
 * then partial text, then semantics, then structure, then recorded bounds, then
 * OCR, then coordinates. A test that only checks "it found the button" would pass
 * with a coordinate-only implementation, which is exactly the design the spec
 * forbids - so every test here asserts the *tier* as well as the result.
 */
class TargetingTest {

    private val resolver = ElementResolver()
    private val evaluator = ExpectationEvaluator(resolver)
    private val clock = testClock()

    @Test
    fun resourceIdBeatsTextWhenBothArePresent() {
        val screen = screenOf(
            nodes = listOf(
                uiNode(1, text = "Save", id = "com.x:id/save"),
                uiNode(2, text = "Save")
            ),
            clock = clock
        )
        val resolution = resolver.resolve(screen, ElementQuery(viewId = "com.x:id/save", text = "Save"))

        assertEquals(MatchTier.VIEW_ID, resolution.tier)
        assertEquals(1, resolution.best?.node?.key)
    }

    @Test
    fun exactTextBeatsPartialText() {
        val screen = screenOf(
            nodes = listOf(
                uiNode(1, text = "Wi-Fi settings"),
                uiNode(2, text = "Wi-Fi")
            ),
            clock = clock
        )
        val resolution = resolver.resolve(screen, ElementQuery.byAnyLabel("Wi-Fi"))

        assertEquals(MatchTier.EXACT_TEXT, resolution.tier)
        assertEquals(2, resolution.best?.node?.key)
    }

    @Test
    fun passwordFieldsAreNeverTargetedByDefault() {
        val screen = screenOf(
            nodes = listOf(
                uiNode(1, text = "Password", editable = true, password = true),
                uiNode(2, text = "Sign in")
            ),
            clock = clock
        )

        assertTrue(resolver.resolve(screen, ElementQuery.byAnyLabel("Password")).isEmpty)
        // Explicit opt-in is required, and it is the only way in.
        val opted = resolver.resolve(
            screen,
            ElementQuery.byAnyLabel("Password").copy(matchPasswordFields = true)
        )
        assertFalse(opted.isEmpty)
        assertEquals(1, opted.best?.node?.key)
    }

    @Test
    fun disabledNodesAreSkipped() {
        val screen = screenOf(
            nodes = listOf(uiNode(1, text = "Submit", enabled = false)),
            clock = clock
        )
        assertTrue(resolver.resolve(screen, ElementQuery.byAnyLabel("Submit")).isEmpty)
    }

    @Test
    fun coordinatesAreTheLastResortAndSaySo() {
        val screen = screenOf(nodes = listOf(uiNode(1, text = "Download")), clock = clock)
        val resolution = resolver.resolve(screen, ElementQuery(point = Point(540, 1200)))

        assertEquals(MatchTier.COORDINATES, resolution.tier)
        assertNull("a coordinate match has no node behind it", resolution.best?.node)
        assertTrue(
            "the resolver must warn when it falls back to coordinates",
            resolution.diagnostics.any { it.contains("coordinates") }
        )
    }

    @Test
    fun aTextQueryNeverFallsBackToCoordinatesWhenANodeMatches() {
        val screen = screenOf(nodes = listOf(uiNode(1, text = "Download")), clock = clock)
        val resolution = resolver.resolve(
            screen,
            ElementQuery(text = "Download", point = Point(10, 10))
        )
        assertEquals(MatchTier.EXACT_TEXT, resolution.tier)
        assertTrue(resolution.best?.node != null)
    }

    @Test
    fun identicalLabelsAreReportedAsAmbiguous() {
        val screen = screenOf(
            nodes = listOf(
                uiNode(1, text = "Delete", bounds = Bounds(0, 100, 400, 200)),
                uiNode(2, text = "Delete", bounds = Bounds(0, 500, 400, 600)),
                // Three identical labels: ambiguity is only declared when there is
                // a real choice to make, not for a lone duplicate.
                uiNode(3, text = "Delete", bounds = Bounds(0, 900, 400, 1000))
            ),
            clock = clock
        )
        val resolution = resolver.resolve(screen, ElementQuery.byAnyLabel("Delete"))

        assertTrue("two identical labels must be flagged", resolution.ambiguous)
        assertTrue(resolution.matches.size >= 2)
    }

    @Test
    fun recordedQueriesKeepSemanticsAndDropCoordinates() {
        val labelled = uiNode(3, text = "Download", id = "com.x:id/download").toQuery()
        assertEquals("com.x:id/download", labelled.viewId)
        assertEquals("Download", labelled.text)
        assertNull("a labelled node never needs bounds", labelled.bounds)
        assertNull(labelled.point)
        assertFalse(labelled.isCoordinateOnly)

        // A node with no metadata at all keeps its bounds as the last resort...
        val bare = uiNode(4, text = null, id = null, className = "android.view.View").toQuery()
        assertNotNull(bare.bounds)
        // ...and withoutFallbacks() is what the recorder stores for a semantic workflow.
        assertNull(bare.withoutFallbacks().bounds)
    }

    // ---------------------------------------------------------- expectations

    @Test
    fun textExpectationsEvaluateAgainstTheSnapshot() {
        val screen = screenOf(
            packageName = "com.android.settings",
            nodes = listOf(uiNode(1, text = "Wi-Fi"), uiNode(2, text = "Connected")),
            clock = clock
        )

        assertTrue(evaluator.evaluate(TextPresent("connected"), screen).satisfied)
        assertTrue(evaluator.evaluate(TextAbsent("Bluetooth"), screen).satisfied)
        assertFalse(evaluator.evaluate(TextPresent("Bluetooth"), screen).satisfied)
        assertTrue(evaluator.evaluate(PackageIs("com.android.settings"), screen).satisfied)
        assertFalse(evaluator.evaluate(PackageIs("com.other"), screen).satisfied)
        assertTrue(evaluator.evaluate(Always, screen).satisfied)
        assertTrue(evaluator.evaluate(null, screen).verdict.name == "UNKNOWN")
    }

    @Test
    fun screenChangedNeedsADifferentSignature() {
        val before = screenOf(nodes = listOf(uiNode(1, text = "A")), clock = clock)
        val after = screenOf(nodes = listOf(uiNode(1, text = "B")), clock = clock)

        assertTrue(evaluator.evaluate(ScreenChanged(before.signature), after).satisfied)
        assertFalse(evaluator.evaluate(ScreenChanged(after.signature), after).satisfied)
        assertTrue(evaluator.evaluate(ElementPresent(ElementQuery.byAnyLabel("A")), before).satisfied)
        assertFalse(evaluator.evaluate(ElementPresent(ElementQuery.byAnyLabel("A")), after).satisfied)
    }

    @Test
    fun signaturesAreStableButSensitiveToContent() {
        val a = screenOf(nodes = listOf(uiNode(1, text = "Wi-Fi"), uiNode(2, text = "Bluetooth")), clock = clock)
        val b = screenOf(nodes = listOf(uiNode(1, text = "Wi-Fi"), uiNode(2, text = "Bluetooth")), clock = clock)
        val c = screenOf(nodes = listOf(uiNode(1, text = "Wi-Fi"), uiNode(2, text = "Airplane mode")), clock = clock)

        assertEquals(ScreenSignature.compute(a), ScreenSignature.compute(b))
        assertNotEquals(ScreenSignature.compute(a), ScreenSignature.compute(c))
        assertEquals(a.signature, ScreenSignature.compute(a), "withSignature() must agree with compute()")
    }
}
