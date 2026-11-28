package com.davort.airbuzz

import com.davort.airbuzz.band.GoldenGateLog
import com.davort.airbuzz.band.SessionTrace
import com.fitbit.goldengate.bindings.dtls.DtlsProtocolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import timber.log.Timber

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

    @Test fun forwardsOnlyGoldenGateLinesWhileOpenUpToTheCap() {
        val got = ArrayList<String>()
        Timber.plant(GoldenGateLog)
        try {
            Timber.tag("GoldenGate").i("before")
            GoldenGateLog.open { got.add(it) }
            Timber.tag("Stack").d("not Golden Gate's")
            Timber.tag("GoldenGate").v("%s: %s", "FINER", "received 31 bytes from the transport")
            repeat(GoldenGateLog.CAP + 5) { Timber.tag("GoldenGate").i("%s: line %d", "INFO", it) }
            GoldenGateLog.close()
            Timber.tag("GoldenGate").i("after")
        } finally {
            Timber.uproot(GoldenGateLog)
        }
        assertEquals(GoldenGateLog.CAP + 1, got.size)
        assertEquals("Golden Gate: INFO: line 0", got.first())
        assertEquals("Golden Gate: more than ${GoldenGateLog.CAP} lines, the rest dropped", got.last())
        GoldenGateLog.open { got.add(it) }
        Timber.plant(GoldenGateLog)
        try {
            Timber.tag("GoldenGate").v("%s: %s", "FINER", "GG_FAILURE shadowing finer error: -30976")
        } finally {
            Timber.uproot(GoldenGateLog)
            GoldenGateLog.close()
        }
        assertEquals("Golden Gate: FINER: GG_FAILURE shadowing finer error: -30976", got.last())
    }
}
