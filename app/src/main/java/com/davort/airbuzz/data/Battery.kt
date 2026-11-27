package com.davort.airbuzz.data

import android.content.Context
import com.davort.airbuzz.band.AirBattery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How old the last battery reading is, for Home: under 1 h, 1–12 h, over 12 h, or none yet (Davor, 27 Nov). */
enum class BatteryAge { NONE, FRESH, HOURS, OLD }

/**
 * The Fitbit Air's last battery reading. [percent] is null until the first one; [charging] is the band's on-charger
 * flag; [readAt] is epoch ms. [lowAlertSent] latches the low-battery notification until a reading says charging.
 */
data class Battery(
    val percent: Int? = null,
    val charging: Boolean = false,
    val readAt: Long = 0L,
    val lowAlertSent: Boolean = false,
) {
    fun age(now: Long): BatteryAge = when {
        percent == null -> BatteryAge.NONE
        now - readAt < HOUR_MS -> BatteryAge.FRESH
        now - readAt < 12 * HOUR_MS -> BatteryAge.HOURS
        else -> BatteryAge.OLD
    }

    /** When [age] next changes, in epoch ms; null when it won't. */
    fun nextAgeChange(now: Long): Long? = when (age(now)) {
        BatteryAge.FRESH -> readAt + HOUR_MS
        BatteryAge.HOURS -> readAt + 12 * HOUR_MS
        else -> null
    }

    /** Whether a reading is due: there is none, or it is [maxAgeMs] old. */
    fun due(now: Long, maxAgeMs: Long) = percent == null || now - readAt >= maxAgeMs

    /**
     * The state after [r] at [now], and whether to send the low-battery notification: once for a reading at [LOW_AT]%
     * or below while not charging, then not again until a reading says charging.
     */
    fun after(r: AirBattery.Reading, now: Long): Pair<Battery, Boolean> {
        val charging = r.onCharger == true
        val alert = !charging && r.percent <= LOW_AT && !lowAlertSent
        return Battery(r.percent, charging, now, lowAlertSent = !charging && (lowAlertSent || alert)) to alert
    }

    companion object {
        const val LOW_AT = 20
        const val HOUR_MS = 3_600_000L
    }
}

/** [Battery], kept across restarts in the app's private preferences. */
class BatteryStore(context: Context) {
    private val prefs = context.getSharedPreferences("battery", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val battery: StateFlow<Battery> = state
    val value: Battery get() = state.value

    fun set(b: Battery) {
        state.value = b
        prefs.edit().putInt("percent", b.percent ?: -1).putBoolean("charging", b.charging).putLong("readAt", b.readAt)
            .putBoolean("lowAlertSent", b.lowAlertSent).apply()
    }

    private fun load() = Battery(
        prefs.getInt("percent", -1).takeIf { it >= 0 }, prefs.getBoolean("charging", false),
        prefs.getLong("readAt", 0L), prefs.getBoolean("lowAlertSent", false),
    )
}
