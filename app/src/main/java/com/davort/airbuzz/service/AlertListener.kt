package com.davort.airbuzz.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.davort.airbuzz.engine
import com.davort.airbuzz.rules.Caller
import com.davort.airbuzz.rules.Rules

/**
 * Sees every notification (with the user's permission) and passes calls and messages to the engine.
 * No notification text is stored; only the app, the caller or chat name, and what AirBuzz did.
 */
class AlertListener : NotificationListenerService() {
    /** Text of the last notification seen per key, so silent updates don't count as new messages. */
    private val lastText = HashMap<String, Int>()
    private val ringing = HashSet<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val engine = engine
        when (callState(n)) {
            CallState.INCOMING -> {
                if (!ringing.add(sbn.key)) return
                engine.onIncomingCall(sbn.key, sbn.packageName, appLabel(sbn.packageName), caller(n), moment(), sbn.postTime)
                return
            }
            CallState.OTHER -> {
                if (sbn.key in ringing) { ringing -= sbn.key; engine.onCallEnded(sbn.key) }
                return
            }
            CallState.NONE -> Unit
        }
        if (!isMessage(n)) return
        val ex = n.extras
        val text = listOf(ex.getCharSequence(Notification.EXTRA_TITLE), ex.getCharSequence(Notification.EXTRA_TEXT),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)).joinToString("\u0000").hashCode()
        val seen = lastText.put(sbn.key, text)
        if (seen == text || (seen != null && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)) return
        if (lastText.size > 500) lastText.clear()
        engine.onMessage(sbn.key, sbn.packageName, appLabel(sbn.packageName), chatName(n), moment(), sbn.postTime)
    }

    override fun onListenerConnected() { engine.note("Notification listener connected") }

    /**
     * Android unbinds a listener when the user turns its access off, and some phone makers' battery savers do it on
     * their own. Ask to be bound again; Android ignores this while access is off.
     */
    override fun onListenerDisconnected() {
        engine.note("Notification listener disconnected; asking Android to reconnect it")
        requestRebind(ComponentName(this, AlertListener::class.java))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        lastText -= sbn.key
        if (ringing.remove(sbn.key)) engine.onCallEnded(sbn.key)
        // Only the user's own dismissals count as seen: apps also remove and repost notifications on their own.
        else if (reason == REASON_CLICK || reason == REASON_CANCEL || reason == REASON_CANCEL_ALL) engine.onMessageDismissed(sbn.key)
    }

    private enum class CallState { INCOMING, OTHER, NONE }

    /**
     * Incoming calls are recognised from any app: Android's call style marks them as incoming; older apps post a
     * call-category notification with a full-screen intent while ringing and drop it once answered.
     */
    private fun callState(n: Notification): CallState {
        val ex = n.extras
        if (ex.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle") {
            return if (Build.VERSION.SDK_INT >= 31 && ex.getInt(Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_INCOMING) {
                CallState.INCOMING
            } else CallState.OTHER
        }
        if (n.category == Notification.CATEGORY_CALL) return if (n.fullScreenIntent != null) CallState.INCOMING else CallState.OTHER
        return CallState.NONE
    }

    private fun caller(n: Notification): Caller {
        val ex = n.extras
        val person: Person? = if (Build.VERSION.SDK_INT >= 31) {
            if (Build.VERSION.SDK_INT >= 33) ex.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
            else @Suppress("DEPRECATION") ex.getParcelable(Notification.EXTRA_CALL_PERSON)
        } else null
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val name = person?.name?.toString()?.takeIf { it.isNotBlank() } ?: title
        val uriNumber = person?.uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:")
        val number = uriNumber ?: name?.takeIf(Rules::looksLikeNumber)
        return Caller(name?.takeUnless(Rules::looksLikeNumber), number)
    }

    private fun isMessage(n: Notification): Boolean {
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        return n.category !in setOf(Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM, Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_STOPWATCH,
            Notification.CATEGORY_LOCATION_SHARING, Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL)
    }

    /** The chat or sender, for the activity log: the conversation title in a group, otherwise the title. */
    private fun chatName(n: Notification): String? {
        val ex = n.extras
        return (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun moment() = engine.moment(
        doNotDisturb = currentInterruptionFilter.let { it != INTERRUPTION_FILTER_ALL && it != INTERRUPTION_FILTER_UNKNOWN },
        phoneInUse = getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked,
    )
}
