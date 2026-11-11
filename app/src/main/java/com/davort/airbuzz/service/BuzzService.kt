package com.davort.airbuzz.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.davort.airbuzz.Engine
import com.davort.airbuzz.R
import com.davort.airbuzz.engine
import com.davort.airbuzz.ui.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Keeps AirBuzz running while it is on, with the status notification Android requires for a foreground service.
 * The notification listener does the work; this service keeps the process and the open Bluetooth link alive.
 */
class BuzzService : Service() {
    private val scope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        val engine = engine
        try {
            startInForeground(Notifications.status(this, engine))
        } catch (e: Exception) {
            Log.w(Engine.TAG, "Couldn't show the status notification: $e")
            stopSelf()
            return
        }
        scope.launch {
            combine(engine.store.settings, engine.state) { _, _ -> }.collect {
                getSystemService(NotificationManager::class.java).notify(Notifications.STATUS_ID, Notifications.status(this@BuzzService, engine))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!engine.settings.enabled) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, Notifications.STATUS_ID, n, type)
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BuzzService::class.java))
            } catch (e: Exception) {
                // Android refuses to start a foreground service from the background without an exemption, such as
                // the companion device association. The notification listener keeps working without it.
                Log.w(Engine.TAG, "Couldn't start the status service: $e")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BuzzService::class.java))
        }
    }
}

/** Actions from AirBuzz's notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = context.engine
        when (intent.action) {
            ACTION_PAUSE -> engine.store.update { it.copy(pausedUntil = System.currentTimeMillis() + 3600_000) }
            ACTION_RESUME -> engine.store.update { it.copy(pausedUntil = 0) }
            ACTION_OFF -> engine.setEnabled(false)
            ACTION_SILENCE -> engine.silence()
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.davort.airbuzz.PAUSE"
        const val ACTION_RESUME = "com.davort.airbuzz.RESUME"
        const val ACTION_OFF = "com.davort.airbuzz.OFF"
        const val ACTION_SILENCE = "com.davort.airbuzz.SILENCE"
    }
}

/** Starts AirBuzz again after a restart when it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && context.engine.settings.enabled) BuzzService.start(context)
    }
}

object Notifications {
    const val STATUS_ID = 1
    private const val RINGING_ID = 2
    private const val STATUS_CHANNEL = "status"
    private const val RINGING_CHANNEL = "ringing"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "Status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows that AirBuzz is on, with Pause and Turn off."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(RINGING_CHANNEL, "Ringing your Air", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a call rings your Air, with Silence."
            setShowBadge(false)
        })
    }

    private fun action(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(context, code, Intent(context, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun status(context: Context, engine: Engine): Notification {
        val s = engine.settings
        val st = engine.state.value
        val now = System.currentTimeMillis()
        val paused = s.pausedUntil > now
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        val text = when {
            st.lastError != null -> "Couldn't reach your Air. Open AirBuzz to fix it."
            paused && s.pausedUntil == Long.MAX_VALUE -> "Paused. Chosen people still ring."
            paused -> "Paused until ${time.format(Date(s.pausedUntil))}. Chosen people still ring."
            st.lastReachedAt != null -> "On · Air reached ${time.format(Date(st.lastReachedAt))}"
            else -> "On"
        }
        return NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buzz)
            .setContentTitle("AirBuzz")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
            .addAction(0, if (paused) "Resume" else "Pause 1 h",
                action(context, if (paused) ActionReceiver.ACTION_RESUME else ActionReceiver.ACTION_PAUSE, 1))
            .addAction(0, "Turn off", action(context, ActionReceiver.ACTION_OFF, 2))
            .build()
    }

    fun showRinging(context: Context, who: String) {
        val n = NotificationCompat.Builder(context, RINGING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buzz)
            .setContentTitle("Ringing your Air")
            .setContentText(who)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Silence", action(context, ActionReceiver.ACTION_SILENCE, 3))
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
