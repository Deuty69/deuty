package com.deuty.agent

import com.deuty.agent.core.util.LoopDetector
import com.deuty.agent.core.util.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two utilities the whole agent leans on: loop detection (requirement 23) and
 * fuzzy text matching (requirement 22).
 *
 * These are pure functions with no Android dependency, which is exactly why they
 * can be tested - and why a bug in them can be found here rather than on a device.
 */
class CoreUtilTest {

    // --- loop detection ------------------------------------------------------

    @Test
    fun repeatingTheSameActionOnTheSameScreenIsALoop() {
        val detector = LoopDetector()
        var verdict = detector.record("screen-A", "tap Wi-Fi")
        assertFalse(verdict.isLoop)
        verdict = detector.record("screen-A", "tap Wi-Fi")
        assertFalse("twice is a retry, not a loop", verdict.isLoop)
        verdict = detector.record("screen-A", "tap Wi-Fi")

        assertTrue(verdict.isLoop)
        assertNotNull(verdict.message)
        assertNotNull(detector.warning)
        assertTrue(verdict.message!!.contains("stuck"))
    }

    @Test
    fun theSameActionOnProgressingScreensIsNotALoop() {
        val detector = LoopDetector()
        // A paging list: "next page" repeatedly, but the screen changes each time.
        repeat(6) { page ->
            val verdict = detector.record("screen-$page", "scroll down")
            assertFalse("page $page was wrongly called a loop", verdict.isLoop)
        }
        assertTrue(detector.warning == null)
        assertEquals(6, detector.distinctScreens())
    }

    @Test
    fun manyActionsWithNoNewScreenIsFlaggedEvenIfTheyDiffer() {
        val detector = LoopDetector(noProgressThreshold = 3)
        assertFalse(detector.record("screen-A", "tap one").isLoop)
        assertFalse(detector.record("screen-A", "tap two").isLoop)
        assertFalse(detector.record("screen-A", "tap three").isLoop)
        val verdict = detector.record("screen-A", "tap four")

        assertTrue("no new screen for 3 actions should be flagged", verdict.isLoop)
        assertTrue(verdict.message!!.contains("Nothing has changed"))
    }

    @Test
    fun progressResetsTheNoProgressCounter() {
        val detector = LoopDetector(noProgressThreshold = 3)
        detector.record("screen-A", "tap one")
        detector.record("screen-A", "tap two")
        detector.record("screen-B", "tap three") // new screen = progress
        assertFalse(detector.record("screen-B", "tap four").isLoop)
    }

    @Test
    fun resetClearsEverything() {
        val detector = LoopDetector()
        detector.record("screen-A", "tap")
        detector.record("screen-A", "tap")
        detector.reset()

        assertEquals(0, detector.actionsRecorded())
        assertEquals(0, detector.distinctScreens())
        assertFalse(detector.record("screen-A", "tap").isLoop)
    }

    @Test
    fun theWindowIsBoundedSoHistoryCannotGrow() {
        val detector = LoopDetector(window = 5)
        repeat(50) { i -> detector.record("screen-$i", "action-$i") }
        assertTrue("window not enforced: ${detector.actionsRecorded()}", detector.actionsRecorded() <= 5)
    }

    @Test
    fun snapshotIsReadableForTheDebugPanel() {
        val detector = LoopDetector()
        detector.record("abc123", "tap Wi-Fi")
        val snapshot = detector.snapshot()

        assertEquals(1, snapshot.size)
        assertTrue(snapshot[0].contains("abc123"))
        assertTrue(snapshot[0].contains("tap Wi-Fi"))
    }

    // --- text matching -------------------------------------------------------

    @Test
    fun normalizeCollapsesWhitespaceCaseAndUnicodePunctuation() {
        val normalized = TextMatch.normalize("  Wi-Fi   Settings ")
        assertEquals("wi-fi settings", normalized)
        assertEquals("", TextMatch.normalize(null))
        // An en dash in a label is the same label as an ASCII hyphen.
        assertEquals(TextMatch.normalize("Turn on \u2013 Wi\u2011Fi"), TextMatch.normalize("turn on - wi-fi"))
        // Zero-width joiners and BOMs are dropped, not counted as characters.
        assertEquals("wifi", TextMatch.normalize("\uFEFFwi\u200Bfi"))
    }

    @Test
    fun equalsLooseIgnoresCaseAndSpacing() {
        assertTrue(TextMatch.equalsLoose("Turn on Wi-Fi", "  turn ON  wi-fi "))
        assertFalse("a hyphen is significant: 'wi fi' is a different string", TextMatch.equalsLoose("Wi-Fi", "wi fi"))
        assertFalse(TextMatch.equalsLoose("Wi-Fi", "Bluetooth"))
    }

    @Test
    fun containsIsCaseInsensitiveAndNullSafe() {
        assertTrue(TextMatch.contains("Download timetable", "timetable"))
        assertFalse(TextMatch.contains("Download timetable", "invoice"))
        assertFalse(TextMatch.contains(null, "anything"))
        assertFalse(TextMatch.contains("anything", null))
        assertFalse(TextMatch.contains("anything", "   "))
    }

    @Test
    fun similarityRanksExactAboveContainsAboveFuzzy() {
        val exact = TextMatch.similarity("Wi-Fi", "wi-fi")
        val partial = TextMatch.similarity("Wi-Fi networks", "wi-fi")
        val different = TextMatch.similarity("Bluetooth", "wi-fi")

        assertTrue("exact should beat partial: $exact vs $partial", exact > partial)
        assertTrue("partial should beat unrelated: $partial vs $different", partial > different)
        assertTrue(exact > 0.95f)
    }

    @Test
    fun similarityOfEmptyStringsIsNotInfiniteOrNaN() {
        val score = TextMatch.similarity("", "")
        assertFalse(score.isNaN())
        assertTrue(score in 0f..1f)
    }

    @Test
    fun levenshteinIsBoundedByTheMaxDistance() {
        assertEquals(0, TextMatch.levenshtein("same", "same"))
        assertEquals(1, TextMatch.levenshtein("wifi", "wify"))
        assertTrue(TextMatch.levenshtein("completely", "unrelated", max = 3) >= 3)
    }

    @Test
    fun looksLikeCodeDetectsOtpsButNotWords() {
        assertTrue(TextMatch.looksLikeCode("483920"))
        assertTrue(TextMatch.looksLikeCode("938 201"))
        assertFalse(TextMatch.looksLikeCode("timetable"))
        assertFalse(TextMatch.looksLikeCode("9a"))
        assertFalse(TextMatch.looksLikeCode(null))
        // Deliberately conservative: a 4-digit year is also OTP-shaped, and
        // masking a year costs nothing while leaking an OTP costs everything.
        assertTrue(TextMatch.looksLikeCode("2024"))
    }

    @Test
    fun tokensSplitOnPunctuationAndStopShort() {
        val tokens = TextMatch.tokens("Wi-Fi & Bluetooth settings!")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.all { it.isNotBlank() })
    }
}
