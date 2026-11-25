package com.davort.airbuzz.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.davort.airbuzz.data.ChatOption
import com.davort.airbuzz.data.ChatSource
import com.davort.airbuzz.engine
import com.davort.airbuzz.rules.MessageFrom
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
        val from = messageFrom(sbn)
        if (from.chatId != null) engine.chatBook.savePhoto(sbn.packageName, from.chatId, chatIcon(n, from.group))
        engine.onMessage(sbn.key, sbn.packageName, appLabel(sbn.packageName), from.chatName ?: chatName(n), from, moment(), sbn.postTime)
    }

    override fun onListenerConnected() {
        engine.note("Notification listener connected")
        // Android answers null while the listener isn't bound; then a late buzz goes ahead as before.
        engine.isShowing = { key -> getActiveNotifications(arrayOf(key))?.isNotEmpty() ?: true }
        // The chat sheet's "On your phone now": chats of an app with a notification showing (issue #33).
        engine.chatsOnPhone = { pkg ->
            (runCatching { activeNotifications }.getOrNull() ?: emptyArray())
                .filter { it.packageName == pkg && isMessage(it.notification) }
                .map { messageFrom(it) }
                .filter { it.chatId != null && it.chatName != null }
                .distinctBy { it.chatId }
                .map { ChatOption(pkg, it.chatId, it.chatName!!, it.group, ChatSource.ON_PHONE_NOW) }
        }
    }

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

    /**
     * Which chat a message came from (issue #33): the conversation shortcut ID, which survives renames, the chat's name
     * without a waiting-message count, whether it is a group, the newest message's sender, and whether the phone shows
     * it silently. Apps that don't post conversations give no chat ID.
     */
    private fun messageFrom(sbn: StatusBarNotification): MessageFrom {
        val n = sbn.notification
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val chatId = if (Build.VERSION.SDK_INT >= 30) n.shortcutId?.takeIf { style != null } else null
        val group = style?.isGroupConversation == true
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val name = Rules.cleanChatName(style?.conversationTitle?.toString() ?: if (!group) title else null)
        val person = style?.messages?.lastOrNull()?.person
        val senderName = person?.name?.toString()?.takeIf { it.isNotBlank() }
        val number = person?.uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:")
        val sender = if (senderName != null || number != null) Caller(senderName?.takeUnless(Rules::looksLikeNumber), number ?: senderName?.takeIf(Rules::looksLikeNumber)) else null
        val ranking = Ranking()
        val importance = if (currentRanking?.getRanking(sbn.key, ranking) == true) ranking.importance else null
        val silent = importance != null && importance < android.app.NotificationManager.IMPORTANCE_DEFAULT
        return MessageFrom(chatId, name, group, sender, silent)
    }

    /**
     * The chat's picture: WhatsApp puts it on the large icon (the group's own picture in a group). Telegram leaves
     * that empty (Davor's phone, 25 Nov), so fall back to the conversation icon, then, in a chat with one person, the
     * sender's icon. A sender's icon never stands for a group.
     */
    private fun chatIcon(n: Notification, group: Boolean): Icon? {
        n.getLargeIcon()?.let { return it }
        if (Build.VERSION.SDK_INT >= 30) {
            val conv = if (Build.VERSION.SDK_INT >= 33) n.extras.getParcelable("android.conversationIcon", Icon::class.java)
            else @Suppress("DEPRECATION") n.extras.getParcelable("android.conversationIcon")
            conv?.let { return it }
        }
        if (group) return null
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        return runCatching { style?.messages?.lastOrNull()?.person?.icon?.toIcon(this) }.getOrNull()
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
