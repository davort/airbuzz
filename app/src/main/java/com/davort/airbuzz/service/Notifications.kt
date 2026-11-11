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

/** Silence from the "Ringing your Air" notification. */
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
 * The only notification is "Ringing your Air", shown while a call rings the band, with Silence.
 */
object Notifications {
    private const val RINGING_ID = 2
    private const val RINGING_CHANNEL = "ringing"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("status") // 0.1's permanent status notification
        nm.createNotificationChannel(NotificationChannel(RINGING_CHANNEL, "Ringing your Air", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a call rings your Air, with Silence."
            setShowBadge(false)
        })
    }

    fun showRinging(context: Context, who: String) {
        val silence = PendingIntent.getBroadcast(context, 3,
            Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_SILENCE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, RINGING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buzz)
            .setContentTitle("Ringing your Air")
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
