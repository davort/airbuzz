package com.davort.airbuzz

import com.davort.airbuzz.band.SyncWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Air shelf's "Google Health is syncing" state, against traffic seen on Davor's Air on 12–13 Nov. */
class SyncWatchTest {
    @Test fun idleTrafficNeverCountsAsASync() {
        val w = SyncWatch()
        // The busiest idle 10 s seen: 1.3 kB, here as 13 packets of 100 bytes, repeated for a minute.
        var t = 0L
        repeat(6) {
            repeat(13) { assertFalse(w.record(t, 100)); t += 700 }
            t += 1_000
            assertFalse(w.update(t))
        }
        assertFalse(w.syncing)
    }

    @Test fun aSyncBurstStartsItAndItEndsAboutTenSecondsAfterTheBurst() {
        val w = SyncWatch()
        // A typical sync: about 24 kB in 120 packets of 200 bytes over 6 s.
        var started = -1
        for (i in 0 until 120) if (w.record(i * 50L, 200)) started = i
        assertEquals(14, started) // 15 packets = 3 kB
        assertTrue(w.syncing)
        assertEquals(0L, w.startedAt) // the burst's first packet, not the one that tipped it over

        val lastPacket = 119 * 50L
        // Still within 10 s of the burst's last kilobytes: still syncing.
        assertFalse(w.update(lastPacket + 9_000))
        assertTrue(w.syncing)
        assertTrue(w.update(lastPacket + 10_000))
        assertFalse(w.syncing)
        assertEquals(24_000L, w.lastBytes)
    }
}
