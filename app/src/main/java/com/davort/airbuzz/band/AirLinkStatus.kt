package com.davort.airbuzz.band

import java.util.Locale
import java.util.UUID

/**
 * The Air's link-status service, read as plain Bluetooth (no secure session) when the Air stops answering, so the step
 * log shows whether the radio link itself still works and how it is set up. fitbit-air's research named the two
 * characteristics and their layouts (github.com/ThoreKoritzius/fitbit-air, MIT; see THIRD_PARTY_NOTICES.md); the
 * decoders are AirBuzz's own. Both are little-endian.
 */
object AirLinkStatus {
    val SERVICE: UUID = UUID.fromString("abbafd00-e56a-484c-b832-8b17cf6cbfe8")
    val CONFIGURATION: UUID = UUID.fromString("abbafd01-e56a-484c-b832-8b17cf6cbfe8")
    val STATUS: UUID = UUID.fromString("abbafd02-e56a-484c-b832-8b17cf6cbfe8")

    /** ABBAFD01: interval in 1.25 ms units, latency, supervision timeout in 10 ms units, MTU, then the mode byte. */
    fun configuration(b: ByteArray): String {
        if (b.size < 9) return "${b.size} bytes, too short"
        val mode = when (b[8].toInt()) { 0 -> "default"; 1 -> "fast"; 2 -> "slow"; else -> "mode ${b[8].toInt() and 0xFF}" }
        return String.format(Locale.ROOT, "interval %.2f ms, latency %d, timeout %d ms, MTU %d, %s",
            u16(b, 0) * 1.25, u16(b, 2), u16(b, 4) * 10, u16(b, 6), mode)
    }

    /** ABBAFD02: flags (bonded, encrypted, data length extension, its reboot), then max TX and RX payload and time. */
    fun status(b: ByteArray): String {
        if (b.size < 7) return "${b.size} bytes, too short"
        val flags = b[0].toInt() and 0xFF
        // Bit 3's meaning is only named ("data length reboot") in fitbit-air, so it is logged as named.
        val names = listOf(1 to "bonded", 2 to "encrypted", 4 to "DLE", 8 to "DLE reboot").filter { flags and it.first != 0 }.map { it.second }
        return String.format(Locale.ROOT, "flags 0x%02X (%s), TX %d B/%d µs, RX %d B/%d µs", flags,
            names.joinToString(", ").ifEmpty { "none" }, b[1].toInt() and 0xFF, u16(b, 2), b[4].toInt() and 0xFF, u16(b, 5))
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}
