package com.davort.airbuzz

import com.davort.airbuzz.band.AirBattery
import com.davort.airbuzz.data.Battery
import com.davort.airbuzz.data.BatteryAge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun decodesTheBatteryFieldsAndSkipsOthers() {
        // field 1 "abc" (length-delimited), field 2 fixed32, field 10 = 1, field 12 = 3951, field 13 = 55
        val body = bytes(0x0A, 3, 0x61, 0x62, 0x63, 0x15, 1, 2, 3, 4, 0x50, 1, 0x60, 0xEF, 0x1E, 0x68, 55)
        assertEquals(AirBattery.Reading(55, true, 3951), AirBattery.decode(body))
        assertEquals(AirBattery.Reading(7, null, null), AirBattery.decode(bytes(0x68, 7)))
    }

    @Test fun rejectsBodiesWithoutAValidPercent() {
        assertNull(AirBattery.decode(bytes(0x50, 0, 0x60, 0xEF, 0x1E))) // no percent
        assertNull(AirBattery.decode(bytes(0x68, 101)))
        assertNull(AirBattery.decode(bytes(0x68, 0xB7))) // varint cut off
        assertNull(AirBattery.decode(bytes(0x0A, 9, 1, 0x68, 55))) // length past the end
        assertNull(AirBattery.decode(bytes(0x6A, 1, 55))) // field 13 not a varint
    }

    @Test fun theLowAlertComesOnceUntilTheBandCharges() {
        var b = Battery()
        fun read(p: Int, charging: Boolean): Boolean { val (n, alert) = b.after(AirBattery.Reading(p, charging, null), 0); b = n; return alert }
        assertFalse(read(21, false))
        assertTrue(read(20, false))
        assertFalse(read(15, false))
        assertFalse(read(40, false)) // still latched: no charging reading yet
        assertFalse(read(12, true)) // charging clears the latch and never alerts
        assertFalse(b.lowAlertSent)
        assertTrue(read(19, false))
    }

    @Test fun ageCrossesAtOneAndTwelveHours() {
        val h = Battery.HOUR_MS
        assertEquals(BatteryAge.NONE, Battery().age(0))
        val b = Battery(50, false, readAt = 10 * h)
        assertEquals(BatteryAge.FRESH, b.age(11 * h - 1))
        assertEquals(BatteryAge.HOURS, b.age(11 * h))
        assertEquals(BatteryAge.OLD, b.age(22 * h))
        assertEquals(11 * h, b.nextAgeChange(10 * h))
        assertEquals(22 * h, b.nextAgeChange(11 * h))
        assertNull(b.nextAgeChange(22 * h))
        assertTrue(b.due(10 * h + 30 * 60_000, 30 * 60_000))
        assertFalse(b.due(10 * h + 29 * 60_000, 30 * 60_000))
    }
}
