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
 * It shows "Ringing your Air" with Silence while a call rings the band, and one problem notification while something
 * stops alerts reaching the Air (brief decision 41): silent while Bluetooth is off, loud once an alert was missed.
 */
object Notifications {
    private const val RINGING_ID = 2
    private const val RINGING_CHANNEL = "ringing"
    private const val PROBLEM_ID = 4
    private const val PROBLEMS_CHANNEL = "problems"
    private const val MISSED_CHANNEL = "missed"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("status") // 0.1's permanent status notification
        nm.createNotificationChannel(NotificationChannel(RINGING_CHANNEL, "Ringing your Air", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a call rings your Air, with Silence."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(PROBLEMS_CHANNEL, "Problems", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Something, such as Bluetooth being off, will stop your Air buzzing. Silent."
        })
        nm.createNotificationChannel(NotificationChannel(MISSED_CHANNEL, "Missed alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A call or message didn't reach your Air, with why and the fix."
        })
    }

    /**
     * The one problem notification, updated in place: [loud] moves it from the silent Problems channel to Missed
     * alerts. [fixLabel] adds the one action, opening [fix]; tapping the notification opens AirBuzz.
     */
    fun showProblem(context: Context, loud: Boolean, title: String, text: String, fixLabel: String?, fix: Intent) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // Caller and chat names stay off the lock screen.
        val public = NotificationCompat.Builder(context, if (loud) MISSED_CHANNEL else PROBLEMS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buzz)
            .setContentTitle(if (loud) "An alert didn't reach your Air" else title)
            .build()
        val n = NotificationCompat.Builder(context, if (loud) MISSED_CHANNEL else PROBLEMS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buzz)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(if (loud) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (loud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(false)
            .setContentIntent(open)
            .apply {
                if (fixLabel != null) addAction(0, fixLabel,
                    PendingIntent.getActivity(context, 5, fix, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(PROBLEM_ID, n)
        } catch (_: SecurityException) {
            // Notifications not allowed; the Air shelf still shows the problem.
        }
    }

    fun cancelProblem(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PROBLEM_ID)
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
