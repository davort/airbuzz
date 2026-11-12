package com.davort.airbuzz

import com.davort.airbuzz.data.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Calendar

/** The privacy policy promises the step log keeps one week. */
class DebugLogTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12) =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, 0) }.timeInMillis

    private fun steps(log: DebugLog) = log.text().lines().filter { it.isNotBlank() }.map { it.substringAfter("  ") }

    @Test fun linesOlderThanAWeekAreDeletedAcrossNewYearAndRestarts() {
        val file = File.createTempFile("debug", ".log").apply { deleteOnExit() }
        var now = at(2026, 12, 28)
        val log = DebugLog(file) { now }
        log.add("old")
        now = at(2026, 12, 31, 23)
        log.add("new year's eve")
        now = at(2027, 1, 4, 13)
        log.add("january")
        // 7 days and 1 hour after "old"; New Year's Eve is 4 days old, though its stamp's month is later than January.
        assertEquals(listOf("new year's eve", "january"), steps(log))
        assertEquals(listOf("new year's eve", "january"), steps(DebugLog(file) { now }))

        now = at(2027, 1, 8, 0)
        assertEquals(listOf("january"), steps(DebugLog(file) { now }))
        assertEquals(1, file.readLines().count { it.isNotBlank() })
    }
}
