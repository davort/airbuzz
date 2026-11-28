package com.davort.airbuzz.band

import com.fitbit.goldengate.bindings.dtls.DtlsProtocolStatus
import com.fitbit.goldengate.bindings.stack.StackEvent
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Words for what Golden Gate and the band do during a secure session and the hand-off after it, for the step log.
 * Added in 0.9.12 to learn why Google Health's Alarms stop loading after a buzz until its next sync (Davor, 28 Nov).
 * A key ID is shown as text when it is a name such as BOOTSTRAP, and otherwise only by its length and first two bytes,
 * so the log never holds a whole key ID.
 */
object SessionTrace {
    fun keyId(id: ByteArray): String {
        if (id.isEmpty()) return "an empty key ID"
        if (id.all { it in 0x20..0x7e }) return "\"${String(id, Charsets.US_ASCII)}\""
        val start = id.take(2).joinToString("") { "%02x".format(it) }
        return "a ${id.size}-byte key ID starting $start…"
    }

    /** A Gattlink control packet's name, or null for a data packet (Golden Gate's gg_gattlink.c, packet layout). */
    fun control(packet: ByteArray): String? {
        if (packet.isEmpty() || packet[0].toInt() and 0x80 == 0) return null
        return when (packet[0].toInt() and 0x7f) {
            0 -> "Gattlink reset request"
            1 -> "Gattlink reset complete"
            else -> "Gattlink control packet 0x%02x".format(packet[0])
        }
    }

    /** A packet's Gattlink kind, for the first band packet after a hand-off. */
    fun packet(packet: ByteArray): String {
        control(packet)?.let { return it }
        val hasAck = packet.isNotEmpty() && packet[0].toInt() and 0x40 != 0
        val payload = packet.size - if (hasAck) 1 else 0
        return when {
            payload > 0 && hasAck -> "Gattlink data with an ack, ${packet.size} bytes"
            payload > 0 -> "Gattlink data, ${packet.size} bytes"
            hasAck -> "Gattlink ack"
            else -> "an empty packet"
        }
    }

    fun stackEvent(event: StackEvent): String = when (event) {
        StackEvent.GattlinkSessionReady -> "Gattlink session ready"
        StackEvent.GattlinkSessionResent -> "Gattlink session reset by the band"
        is StackEvent.GattlinkSessionStall -> "Gattlink stalled for ${event.stalledTime} ms"
        StackEvent.GattlinkBufferOverThreshold -> "Gattlink buffer over its threshold"
        StackEvent.GattlinkBufferUnderThreshold -> "Gattlink buffer under its threshold"
        StackEvent.Unknown -> "an unknown stack event"
    }

    fun dtls(status: DtlsProtocolStatus): String {
        val state = when (status.state) {
            DtlsProtocolStatus.TlsProtocolState.TLS_STATE_INIT -> "starting"
            DtlsProtocolStatus.TlsProtocolState.TLS_STATE_HANDSHAKE -> "handshake"
            DtlsProtocolStatus.TlsProtocolState.TLS_STATE_SESSION -> "session"
            DtlsProtocolStatus.TlsProtocolState.TLS_STATE_ERROR -> "error ${status.lastError}"
            DtlsProtocolStatus.TlsProtocolState.TLS_STATE_UNKNOWN -> "unknown state"
        }
        val identity = status.pskIdentity.takeIf { it.isNotEmpty() }?.let { " with ${keyId(it.toByteArray(Charsets.ISO_8859_1))}" } ?: ""
        return "DTLS $state$identity"
    }
}

/**
 * Forwards Golden Gate's own log lines, which go to Timber and were dropped until 0.9.12, to the step log while
 * [open], at most [CAP] lines each time. Which lines exist is set on the phone with
 * `adb shell setprop debug.gg.log.config ...`, read when Golden Gate starts.
 */
object GoldenGateLog : Timber.Tree() {
    const val CAP = 300
    @Volatile private var sink: ((String) -> Unit)? = null
    private val count = AtomicInteger()

    fun open(to: (String) -> Unit) {
        count.set(0)
        sink = to
    }

    fun close() {
        sink = null
    }

    override fun isLoggable(tag: String?, priority: Int) = tag == "GoldenGate" && sink != null

    /** Mbed TLS's per-packet lines at FINER, which would fill the cap before the hand-off; its error line stays. */
    private val noise = listOf("bytes from the transport", "mbedtls wants to", ": returning ", "pending data fully consumed",
        "transport data", "user data", "transport socket metadata")

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val to = sink ?: return
        if (noise.any { it in message }) return
        val n = count.incrementAndGet()
        if (n <= CAP) to("Golden Gate: $message") else if (n == CAP + 1) to("Golden Gate: more than $CAP lines, the rest dropped")
    }
}
