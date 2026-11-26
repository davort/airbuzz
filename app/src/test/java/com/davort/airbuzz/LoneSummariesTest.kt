package com.davort.airbuzz

import com.davort.airbuzz.rules.LoneSummaries
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoneSummariesTest {
    private val g = "reddit|group"

    @Test fun aSummaryWithNoMessageBuzzesOnce() {
        val s = LoneSummaries()
        assertTrue(s.summary("k", g, 1, 10_000))
        assertTrue(s.due("k", 12_000))
        assertFalse(s.due("k", 12_100))
    }

    @Test fun aMessageJustBeforeOrAfterTheSummaryKeepsItQuiet() {
        val before = LoneSummaries()
        before.message(g, 7, 9_000)
        assertFalse(before.summary("k", g, 1, 10_000))

        val after = LoneSummaries()
        assertTrue(after.summary("k", g, 1, 10_000))
        after.message(g, 7, 11_500)
        assertFalse(after.due("k", 12_000))
    }

    @Test fun aMessageOfAnotherGroupOrLongBeforeDoesNotCount() {
        val s = LoneSummaries()
        s.message("other", 7, 9_900)
        s.message(g, 7, 7_000)
        assertTrue(s.summary("k", g, 1, 10_000))
        assertTrue(s.due("k", 12_000))
    }

    @Test fun theLateMessageWithTheSummarysTextDoesNotBuzzAgain() {
        val s = LoneSummaries()
        assertTrue(s.summary("k", g, 1, 10_000))
        assertTrue(s.due("k", 12_000))
        // Eleven minutes later the message itself arrives with the same text.
        assertFalse(s.message(g, 1, 12_000 + 11 * 60_000))
        // A new message in that chat buzzes as usual.
        assertTrue(s.message(g, 2, 12_000 + 12 * 60_000))
    }

    @Test fun aSummaryRepeatingTheLastMessagesTextDoesNotBuzz() {
        val s = LoneSummaries()
        s.message(g, 1, 0)
        assertFalse(s.summary("k", g, 1, 60_000))
        assertTrue(s.summary("k", g, 2, 60_000))
    }

    @Test fun aSummaryRemovedBeforeItsCheckDoesNotBuzz() {
        val s = LoneSummaries()
        assertTrue(s.summary("k", g, 1, 10_000))
        s.removed("k")
        assertFalse(s.due("k", 12_000))
    }
}
