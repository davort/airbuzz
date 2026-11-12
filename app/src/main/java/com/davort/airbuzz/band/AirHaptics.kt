package com.davort.airbuzz.band

/**
 * Payloads for the Air's CoAP `/settings` resource, which holds the band's haptic strength.
 * The same resource and payloads are used by the MIT-licensed fitbit-air library
 * (github.com/ThoreKoritzius/fitbit-air); see THIRD_PARTY_NOTICES.md.
 */
object AirHaptics {
    const val LOW = 2
    const val HIGH = 3

    /** `PUT /settings` with this body answers with the current strength entry. */
    val READ_REQUEST = byteArrayOf(0x0A, 0x01, 0x01)

    /** One strength entry: field 1 { field 1 { field 1 = strength } }. */
    fun entry(strength: Int): ByteArray {
        require(strength == LOW || strength == HIGH) { "The Fitbit Air has two strengths: 2 or 3." }
        return byteArrayOf(0x0A, 0x04, 0x0A, 0x02, 0x08, strength.toByte())
    }

    fun other(strength: Int) = if (strength == LOW) HIGH else LOW

    /**
     * `POST /settings` body for one tap. At the current strength it writes the other strength and then the
     * current one, so the band buzzes and keeps its setting. At a different strength it writes that strength
     * once, which also changes the band's saved strength (and therefore its alarm strength).
     */
    fun tapBody(current: Int, target: Int): ByteArray =
        if (target == current) entry(other(current)) + entry(current) else entry(target)

    /** Parses the read response; null when it is not exactly one known strength entry. */
    fun parseStrength(body: ByteArray): Int? =
        listOf(LOW, HIGH).firstOrNull { body.contentEquals(entry(it)) }
}
