package com.davort.airbuzz.band

/**
 * The Air's device-information resource, which carries its battery. fitbit-air's research found the path and the field
 * numbers, and that the level matches the percentage Google Health shows (github.com/ThoreKoritzius/fitbit-air, MIT;
 * see THIRD_PARTY_NOTICES.md). The decoder is AirBuzz's own.
 */
object AirBattery {
    const val PATH = "/md/606"

    /** One reading: [percent] 0–100, [onCharger] and [millivolts] when the body carries them. */
    data class Reading(val percent: Int, val onCharger: Boolean?, val millivolts: Int?)

    /**
     * Reads protobuf fields 13 (percent), 10 (on charger) and 12 (millivolts) as varints and skips every other field.
     * Null when the body is malformed or has no percent from 0 to 100.
     */
    fun decode(body: ByteArray): Reading? {
        var i = 0
        fun varint(): Long? {
            var value = 0L
            var shift = 0
            while (i < body.size && shift < 64) {
                val b = body[i++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            return null
        }
        var percent: Long? = null
        var charger: Long? = null
        var millivolts: Long? = null
        while (i < body.size) {
            val key = varint() ?: return null
            val field = key ushr 3
            when ((key and 7).toInt()) {
                0 -> {
                    val v = varint() ?: return null
                    when (field) {
                        10L -> charger = v
                        12L -> millivolts = v
                        13L -> percent = v
                    }
                }
                1 -> i += 8
                2 -> {
                    val n = varint() ?: return null
                    if (n < 0 || n > body.size - i) return null
                    i += n.toInt()
                }
                5 -> i += 4
                else -> return null
            }
            if (i > body.size) return null
        }
        val p = percent?.takeIf { it in 0..100 }?.toInt() ?: return null
        return Reading(p, charger?.let { it != 0L }, millivolts?.takeIf { it in 1..10_000 }?.toInt())
    }
}
