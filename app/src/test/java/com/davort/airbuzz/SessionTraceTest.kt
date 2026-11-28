package com.davort.airbuzz

import com.davort.airbuzz.band.SessionTrace
import com.fitbit.goldengate.bindings.dtls.DtlsProtocolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTraceTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun namesTextKeysAndShowsOnlyTheStartOfOthers() {
        assertEquals("\"BOOTSTRAP\"", SessionTrace.keyId("BOOTSTRAP".toByteArray()))
        // One unprintable byte makes the whole ID opaque, so no part of it is shown as text.
        assertEquals("a 16-byte key ID starting 4241…", SessionTrace.keyId(bytes(0x42, 0x41, *IntArray(13) { 0x43 }, 0x9f)))
        assertEquals("a 2-byte key ID starting ff01…", SessionTrace.keyId(bytes(0xff, 0x01)))
        assertEquals("an empty key ID", SessionTrace.keyId(ByteArray(0)))
    }

    @Test fun tellsGattlinkControlPacketsFromData() {
        assertEquals("Gattlink reset request", SessionTrace.control(bytes(0x80)))
        assertEquals("Gattlink reset complete", SessionTrace.control(bytes(0x81, 1, 1, 8, 8)))
        // 0x40 is a data packet's ack flag, not a control packet.
        assertNull(SessionTrace.control(bytes(0x41, 0x02, 0x17)))
        assertEquals("Gattlink data with an ack, 3 bytes", SessionTrace.packet(bytes(0x41, 0x02, 0x17)))
        assertEquals("Gattlink ack", SessionTrace.packet(bytes(0x43)))
        assertEquals("Gattlink data, 4 bytes", SessionTrace.packet(bytes(0x05, 1, 2, 3)))
    }

    @Test fun describesDtlsStatesWithTheirIdentity() {
        assertEquals("DTLS error -10601 with \"BOOTSTRAP\"", SessionTrace.dtls(DtlsProtocolStatus(3, -10601, "BOOTSTRAP")))
        assertEquals("DTLS session", SessionTrace.dtls(DtlsProtocolStatus(2, 0, "")))
    }
}
