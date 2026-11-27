package com.davort.airbuzz

import com.davort.airbuzz.band.AirLinkStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkStatusTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun decodesTheConnectionConfigurationLittleEndian() {
        // interval 0x0018 = 24 × 1.25 ms, latency 0x0102 = 258, timeout 0x01F4 = 500 × 10 ms, MTU 0x00F7 = 247, slow
        assertEquals("interval 30.00 ms, latency 258, timeout 5000 ms, MTU 247, slow",
            AirLinkStatus.configuration(bytes(0x18, 0x00, 0x02, 0x01, 0xF4, 0x01, 0xF7, 0x00, 2)))
        assertEquals("8 bytes, too short", AirLinkStatus.configuration(ByteArray(8)))
    }

    @Test fun decodesTheConnectionStatus() {
        // bonded and DLE but not encrypted; TX 251 B / 0x0848 = 2120 µs; RX 27 B / 0x0148 = 328 µs
        assertEquals("flags 0x05 (bonded, DLE), TX 251 B/2120 µs, RX 27 B/328 µs",
            AirLinkStatus.status(bytes(0x05, 0xFB, 0x48, 0x08, 0x1B, 0x48, 0x01)))
        assertEquals("flags 0x00 (none), TX 0 B/0 µs, RX 0 B/0 µs", AirLinkStatus.status(ByteArray(7)))
        assertEquals("6 bytes, too short", AirLinkStatus.status(ByteArray(6)))
    }
}
