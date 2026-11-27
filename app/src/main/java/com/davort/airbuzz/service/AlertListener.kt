package com.davort.airbuzz.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.davort.airbuzz.data.ChatOption
import com.davort.airbuzz.data.ChatSource
import com.davort.airbuzz.engine
import com.davort.airbuzz.rules.MessageFrom
import com.davort.airbuzz.rules.Caller
import com.davort.airbuzz.rules.LoneSummaries
import com.davort.airbuzz.rules.Rules

/**
 * Sees every notification (with the user's permission) and passes calls and messages to the engine.
 * No notification text is stored; only the app, the caller or chat name, and what AirBuzz did.
 */
class AlertListener : NotificationListenerService() {
    /** Text of the last notification seen per key, so silent updates don't count as new messages. */
    private val lastText = HashMap<String, Int>()
    private val ringing = HashSet<String>()
    /** The last call-diagnostic line per notification key, so a dialer's updates log only when something changes. */
    private val callShape = HashMap<String, String>()
    /** Why a chosen app's notification last didn't count as a new message, per key, so each reason logs once. */
    private val dropped = HashMap<String, String>()
    /**
     * Rings of the phone's own phone app (a system call screen or the default phone app) that ran while the phone was
     * in ringtone mode; they end when it leaves that mode. Other apps' rings don't: WhatsApp's calls leave ringtone
     * mode after about 6 s and keep ringing (Davor's phone, 26 Nov).
     */
    private val modeRinging = HashSet<String>()
    /**
     * Samsung call-screen notifications whose call was answered, placed or has ended: they stay up through the call
     * and look the same as while ringing, so they don't ring again until they go away.
     */
    private val samsungSpent = HashSet<String>()
    private val summaries = LoneSummaries()
    private val main = Handler(Looper.getMainLooper())
    private val audio: AudioManager? by lazy { getSystemService(AudioManager::class.java) }
    private var audioMode = AudioManager.MODE_NORMAL
    private var modeListener: Any? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val engine = engine
        if (sbn.packageName == SAMSUNG_CALL_SCREEN && inCall()) samsungSpent += sbn.key
        val state = callState(sbn)
        if (state != CallState.INCOMING) noteCallShape(sbn, state)
        when (state) {
            CallState.INCOMING -> { ring(sbn); return }
            CallState.OTHER -> {
                if (sbn.key in ringing) endRing(sbn.key, "")
                return
            }
            CallState.NONE -> Unit
        }
        if (n.category == Notification.CATEGORY_MISSED_CALL) {
            val dialer = defaultDialer()
            engine.onMissedCall(setOf(sbn.packageName) + if (sbn.packageName == dialer) CALL_SCREENS else emptySet(), appLabel(sbn.packageName))
        }
        val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (!isMessage(n) && !(summary && isMessage(n, summaryToo = true) && sbn.packageName in engine.settings.apps)) {
            noteDropped(sbn, notMessageReason(n)); return
        }
        val ex = n.extras
        val text = listOf(ex.getCharSequence(Notification.EXTRA_TITLE), ex.getCharSequence(Notification.EXTRA_TEXT),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)).joinToString("\u0000").hashCode()
        val seen = lastText.put(sbn.key, text)
        if (seen == text) { noteDropped(sbn, "same text as before"); return }
        if (seen != null && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) { noteDropped(sbn, "a silent update"); return }
        if (lastText.size > 500) lastText.clear()
        val now = android.os.SystemClock.elapsedRealtime()
        if (summary) {
            // A group summary stands for a message only when no message of its group comes with it (a tester's
            // Reddit, 26 Nov: the messages came 11–16 min later or never).
            if (!summaries.summary(sbn.key, sbn.groupKey, text, now)) { noteDropped(sbn, "a group summary"); return }
            val from = messageFrom(sbn)
            main.postDelayed({
                val showing = getActiveNotifications(arrayOf(sbn.key))?.isNotEmpty() ?: true
                if (!showing) summaries.removed(sbn.key)
                if (!summaries.due(sbn.key, android.os.SystemClock.elapsedRealtime())) return@postDelayed
                dropped -= sbn.key
                engine.note("${appLabel(sbn.packageName)} showed only a group summary, with no message after it; taken as a message")
                deliver(sbn, from)
            }, SUMMARY_WAIT_MS)
            return
        }
        if (!summaries.message(sbn.groupKey, text, now)) { noteDropped(sbn, "its group summary already buzzed for it"); return }
        dropped -= sbn.key
        deliver(sbn, messageFrom(sbn))
    }

    private fun deliver(sbn: StatusBarNotification, from: MessageFrom) {
        val n = sbn.notification
        if (from.chatId != null) engine.chatBook.savePhoto(sbn.packageName, from.chatId, chatIcon(n, from.group))
        engine.onMessage(sbn.key, sbn.packageName, appLabel(sbn.packageName), from.chatName ?: chatName(n), from, moment(), sbn.postTime)
    }

    private fun ring(sbn: StatusBarNotification) {
        if (audioMode == AudioManager.MODE_RINGTONE && isPhoneApp(sbn.packageName)) modeRinging += sbn.key
        if (!ringing.add(sbn.key)) return
        engine.onIncomingCall(sbn.key, sbn.packageName, appLabel(sbn.packageName), caller(sbn.notification), moment(), sbn.postTime)
    }

    private fun endRing(key: String, why: String) {
        if (key.contains("|$SAMSUNG_CALL_SCREEN|")) samsungSpent += key
        ringing -= key
        modeRinging -= key
        engine.onCallEnded(key, why)
    }

    /**
     * The phone's audio mode, which Android's call service sets to ringtone while a phone call rings and to in call
     * once it is answered. On OnePlus the ringing notification of the built-in phone app looks the same while ringing
     * and in a call (a tester's log, 26 Nov), so the mode tells them apart; and the phone app's ring stops as soon as
     * the phone leaves ringtone mode, which can come before the app updates its notification ([modeRinging]).
     */
    private fun onAudioMode(mode: Int) {
        val was = audioMode
        if (mode == was) return
        audioMode = mode
        if (was == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_RINGTONE || ringing.isNotEmpty()) {
            engine.note("Phone audio mode: ${modeName(was)} → ${modeName(mode)}")
        }
        if (mode == AudioManager.MODE_RINGTONE) {
            // A system call screen that posted before the mode changed rings now, and the phone app's ring that is
            // already under way ends with the mode.
            (runCatching { activeNotifications }.getOrNull() ?: emptyArray())
                .filter { callState(it) == CallState.INCOMING }.forEach(::ring)
        } else if (was == AudioManager.MODE_RINGTONE) {
            modeRinging.filter { it in ringing }.forEach { endRing(it, ": the phone stopped ringing (${modeName(mode)})") }
            modeRinging.clear()
        }
        if (inCall()) {
            // Samsung's call screen rings without ringtone mode when the ringer is off, so its ring ends when a call is
            // under way: answered, or placed from the phone.
            (runCatching { activeNotifications }.getOrNull() ?: emptyArray())
                .filter { it.packageName == SAMSUNG_CALL_SCREEN }.forEach { sbn ->
                    if (sbn.key in ringing) endRing(sbn.key, ": a call is under way (${modeName(mode)})") else samsungSpent += sbn.key
                }
        }
    }

    override fun onListenerConnected() {
        engine.note("Notification listener connected")
        audioMode = audio?.mode ?: AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 31 && modeListener == null) {
            val l = AudioManager.OnModeChangedListener { main.post { onAudioMode(it) } }
            runCatching { audio?.addOnModeChangedListener(mainExecutor, l) }.onSuccess { modeListener = l }
        }
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
        stopModeListener()
        requestRebind(ComponentName(this, AlertListener::class.java))
    }

    override fun onDestroy() {
        stopModeListener()
        super.onDestroy()
    }

    private fun stopModeListener() {
        if (Build.VERSION.SDK_INT >= 31) (modeListener as? AudioManager.OnModeChangedListener)?.let { audio?.removeOnModeChangedListener(it) }
        modeListener = null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        lastText -= sbn.key
        callShape -= sbn.key
        samsungSpent -= sbn.key
        dropped -= sbn.key
        summaries.removed(sbn.key)
        if (sbn.key in ringing) endRing(sbn.key, "")
        // Only the user's own dismissals count as seen: apps also remove and repost notifications on their own.
        else if (reason == REASON_CLICK || reason == REASON_CANCEL || reason == REASON_CANCEL_ALL) engine.onMessageDismissed(sbn.key)
    }

    private enum class CallState { INCOMING, OTHER, NONE }

    /**
     * Incoming calls are recognised from any app: Android's call style marks them as incoming; older apps post a
     * call-category notification with a full-screen intent while ringing and drop it once answered. A system call
     * screen's or the default phone app's call notification without either rings while the phone is in ringtone mode
     * (OnePlus, 26 Nov).
     */
    private fun callState(sbn: StatusBarNotification): CallState {
        val n = sbn.notification
        val ex = n.extras
        if (ex.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle") {
            return when {
                Build.VERSION.SDK_INT >= 31 && ex.getInt(Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_INCOMING -> CallState.INCOMING
                // Samsung's call screen posts a ringing call as ongoing (call type 2), and with the ringer off the phone
                // stays in normal mode (an S25 Ultra tester's log, 27 Nov). Before a call is under way, it is ringing.
                sbn.packageName == SAMSUNG_CALL_SCREEN && sbn.key !in samsungSpent &&
                    (audioMode == AudioManager.MODE_NORMAL || audioMode == AudioManager.MODE_RINGTONE) -> CallState.INCOMING
                else -> CallState.OTHER
            }
        }
        if (n.category == Notification.CATEGORY_CALL) return when {
            n.fullScreenIntent != null -> CallState.INCOMING
            audioMode == AudioManager.MODE_RINGTONE && isPhoneApp(sbn.packageName) -> CallState.INCOMING
            else -> CallState.OTHER
        }
        return CallState.NONE
    }

    /**
     * Logs what a call-looking notification carried when it wasn't taken as an incoming call, so a dialer AirBuzz misses
     * can be fixed from a shared log (a OnePlus tester's calls left no trace, 26 Nov). Call-looking: call style, the
     * call category, the default phone app, or a system call screen ([CALL_SCREENS]; on OnePlus and OPPO the default
     * phone app is Contacts, and the ringing notification comes from the in-call screen). Only technical fields; no
     * caller name or number.
     */
    private fun noteCallShape(sbn: StatusBarNotification, state: CallState) {
        val n = sbn.notification
        val template = n.extras.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')
        val dialer = defaultDialer()
        if (template != "CallStyle" && n.category != Notification.CATEGORY_CALL && sbn.packageName != dialer &&
            sbn.packageName !in CALL_SCREENS) return
        val callType = if (template == "CallStyle" && Build.VERSION.SDK_INT >= 31) n.extras.getInt(Notification.EXTRA_CALL_TYPE).toString() else "-"
        val shape = "package ${sbn.packageName}${if (sbn.packageName == dialer) " (default phone app)" else ""}, " +
            "template ${template ?: "none"}, call type $callType, category ${n.category ?: "none"}, " +
            "full-screen ${if (n.fullScreenIntent != null) "yes" else "no"}, " +
            "ongoing ${if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) "yes" else "no"}, " +
            "foreground service ${if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) "yes" else "no"}, " +
            "audio mode ${modeName(audioMode)}"
        if (callShape.put(sbn.key, shape) == shape) return
        if (callShape.size > 50) callShape.clear()
        engine.note("Call-like notification not taken as ringing (${state.name.lowercase()}): $shape")
    }

    /**
     * Logs once per notification and reason when a chosen app's notification doesn't count as a new message, so a
     * message that never buzzed leaves a trace (a tester's Reddit chats left none, 26 Nov). Only the app's name.
     */
    private fun noteDropped(sbn: StatusBarNotification, why: String) {
        if (sbn.packageName !in engine.settings.apps) return
        if (dropped.put(sbn.key, why) == why) return
        if (dropped.size > 200) dropped.clear()
        engine.note("Notification from ${appLabel(sbn.packageName)} not counted as a new message: $why")
    }

    private fun notMessageReason(n: Notification): String = when {
        n.flags and Notification.FLAG_GROUP_SUMMARY != 0 -> "a group summary"
        n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 -> "a foreground service"
        n.flags and Notification.FLAG_ONGOING_EVENT != 0 -> "ongoing"
        else -> "category ${n.category}"
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

    private fun inCall() = audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION

    private fun isPhoneApp(pkg: String) = pkg in CALL_SCREENS || pkg == defaultDialer()

    private fun defaultDialer(): String? = runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()

    private fun modeName(mode: Int) = when (mode) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        AudioManager.MODE_IN_CALL -> "in call"
        AudioManager.MODE_IN_COMMUNICATION -> "in communication"
        else -> "mode $mode"
    }

    private fun isMessage(n: Notification, summaryToo: Boolean = false): Boolean {
        if (!summaryToo && n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
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

    private companion object {
        /** System call screens that post the ringing notification for the built-in phone app. */
        const val SAMSUNG_CALL_SCREEN = "com.samsung.android.incallui"
        val CALL_SCREENS = setOf("com.android.incallui", "com.android.server.telecom", SAMSUNG_CALL_SCREEN)
        /** How long a lone group summary waits for a message of its group before it counts as one. */
        const val SUMMARY_WAIT_MS = 2_000L
    }
}
