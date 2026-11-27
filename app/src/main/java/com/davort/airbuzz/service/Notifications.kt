package com.davort.airbuzz.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.davort.airbuzz.R
import com.davort.airbuzz.engine
import com.davort.airbuzz.ui.MainActivity

/** Silence from the "Ringing your Fitbit Air" notification. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SILENCE) context.engine.silence()
    }

    companion object {
        const val ACTION_SILENCE = "com.davort.airbuzz.SILENCE"
    }
}

/**
 * AirBuzz has no permanent notification: Android keeps a notification listener running on its own.
 * It shows "Ringing your Fitbit Air" with Silence while a call rings the band, a silent notification while Bluetooth is
 * off (brief decision 41), and a loud one once an alert was missed. The missed one stays until it is tapped or swiped,
 * even after the Air buzzes again (Davor, 17 Nov), so it has its own ID.
 */
object Notifications {
    private const val RINGING_ID = 2
    private const val RINGING_CHANNEL = "ringing"
    private const val PROBLEM_ID = 4
    private const val MISSED_ID = 5
    private const val PROBLEMS_CHANNEL = "problems"
    private const val MISSED_CHANNEL = "missed"
    private const val BATTERY_ID = 6
    private const val BATTERY_CHANNEL = "battery"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("status") // 0.1's permanent status notification
        nm.createNotificationChannel(NotificationChannel(RINGING_CHANNEL, "Ringing your Fitbit Air", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a call rings your Fitbit Air, with Silence."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(PROBLEMS_CHANNEL, "Problems", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Something, such as Bluetooth being off, will stop your Fitbit Air buzzing. Silent."
        })
        nm.createNotificationChannel(NotificationChannel(MISSED_CHANNEL, "Missed alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A call or message didn’t reach your Fitbit Air, with why and the fix."
        })
        // Silent: the band warns about its own battery too (Davor, 27 Nov).
        nm.createNotificationChannel(NotificationChannel(BATTERY_CHANNEL, "Fitbit Air battery", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Your Fitbit Air’s battery is low. Silent."
        })
    }

    /** The silent notification while Bluetooth is off; tapping it opens AirBuzz, Turn on opens Bluetooth settings. */
    fun showBluetoothOff(context: Context) {
        val n = builder(context, PROBLEMS_CHANNEL, "Bluetooth is off", "Your Fitbit Air won’t buzz until it’s back on",
            "Turn on", Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notify(context, PROBLEM_ID, n)
    }

    fun cancelBluetoothOff(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PROBLEM_ID)
    }

    /**
     * The loud notification for missed alerts, updated in place and replacing the Bluetooth-off one. [fixLabel] adds
     * the one action, opening [fix]; tapping the notification opens AirBuzz and removes it.
     */
    fun showMissed(context: Context, title: String, text: String, fixLabel: String?, fix: Intent) {
        // Caller and chat names stay off the lock screen.
        val public = NotificationCompat.Builder(context, MISSED_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buzz)
            .setContentTitle("An alert didn’t reach your Fitbit Air")
            .build()
        val n = builder(context, MISSED_CHANNEL, title, text, fixLabel, fix)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .build()
        cancelBluetoothOff(context)
        notify(context, MISSED_ID, n)
    }

    /** Whether the missed-alert notification is still on the phone, not yet tapped or swiped. */
    fun missedShowing(context: Context) =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == MISSED_ID }

    private fun builder(context: Context, channel: String, title: String, text: String, fixLabel: String?, fix: Intent) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_buzz)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .apply {
                if (fixLabel != null) addAction(0, fixLabel,
                    PendingIntent.getActivity(context, 5, fix, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // Notifications not allowed; the Air shelf still shows the problem.
        }
    }

    /** Sent once when the band reads 20% or below; again only after it has been seen charging. */
    fun showLowBattery(context: Context, percent: Int) {
        val n = builder(context, BATTERY_CHANNEL, "Your Fitbit Air is at $percent%", "Charge it soon so calls keep ringing it.",
            null, Intent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        notify(context, BATTERY_ID, n)
    }

    fun cancelLowBattery(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(BATTERY_ID)
    }

    fun showRinging(context: Context, who: String) {
        val silence = PendingIntent.getBroadcast(context, 3,
            Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_SILENCE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, RINGING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buzz)
            .setContentTitle("Ringing your Fitbit Air")
            .setContentText(who)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Silence", silence)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(RINGING_ID, n)
        } catch (_: SecurityException) {
            // Notifications not allowed; the ring still runs.
        }
    }

    fun cancelRinging(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(RINGING_ID)
    }
}
