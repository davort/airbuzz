package com.davort.airbuzz

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.ApplicationStartInfo
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import com.davort.airbuzz.band.AirBand
import com.davort.airbuzz.band.AirBattery
import com.davort.airbuzz.data.Battery
import com.davort.airbuzz.data.BatteryAge
import com.davort.airbuzz.data.BatteryStore
import com.davort.airbuzz.band.AirHaptics
import com.davort.airbuzz.band.BandCommand
import com.davort.airbuzz.band.BandResult
import com.davort.airbuzz.data.ActivityLog
import com.davort.airbuzz.data.ChatBook
import com.davort.airbuzz.data.ChatOption
import com.davort.airbuzz.data.ChatSource
import com.davort.airbuzz.data.SeenChat
import com.davort.airbuzz.rules.MessageFrom
import com.davort.airbuzz.data.DebugLog
import com.davort.airbuzz.data.Entry
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.data.Result
import com.davort.airbuzz.data.SettingsStore
import com.davort.airbuzz.rules.Caller
import com.davort.airbuzz.rules.Moment
import com.davort.airbuzz.rules.Outcome
import com.davort.airbuzz.rules.Rules
import com.davort.airbuzz.rules.Settings
import com.davort.airbuzz.service.Notifications
import com.davort.airbuzz.ui.MainActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AirBuzzApp : Application() {
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        engine = Engine(this)
        engine.note(startLine())
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) = engine.onAppOpened()
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    /**
     * Why Android started AirBuzz now and why it stopped it last time, so the step log shows whether the phone ends
     * AirBuzz in the background. Phone makers' battery savers often add their own description.
     */
    private fun startLine(): String {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val am = getSystemService(ActivityManager::class.java)
        val started = if (Build.VERSION.SDK_INT >= 35) runCatching {
            am.getHistoricalProcessStartReasons(1).firstOrNull()?.let { " by ${startReason(it.reason)}" }
        }.getOrNull() else null
        val last = if (Build.VERSION.SDK_INT >= 30) runCatching { am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull() else null
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
        // Android records no stop for a shutdown, and on 15 Nov kept none for an update 3 minutes before one either, so
        // a last stop from before the phone started says only that the phone restarted since.
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val restarted = if (last != null && last.timestamp < bootAt) " The phone restarted at ${time.format(Date(bootAt))}." else ""
        val stopped = last?.let {
            val where = if (it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) "on screen" else "in the background"
            " Last ${if (restarted.isEmpty()) "stopped" else "recorded stop before that"} ${time.format(Date(it.timestamp))} $where: " +
                exitReason(it.reason) + (it.description?.takeIf(String::isNotBlank)?.let { d -> " ($d)" } ?: "")
        } ?: ""
        return "AirBuzz $version started${started ?: ""}.$restarted$stopped"
    }

    private fun startReason(reason: Int) = when (reason) {
        ApplicationStartInfo.START_REASON_SERVICE -> "a service (the notification listener)"
        ApplicationStartInfo.START_REASON_LAUNCHER, ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS -> "opening the app"
        ApplicationStartInfo.START_REASON_BROADCAST -> "a broadcast"
        ApplicationStartInfo.START_REASON_BOOT_COMPLETE -> "the phone starting"
        ApplicationStartInfo.START_REASON_START_ACTIVITY -> "a screen opening"
        else -> "reason $reason"
    }

    private fun exitReason(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user, an update or a reinstall"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "the phone needed memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too much battery or CPU"
        ApplicationExitInfo.REASON_OTHER -> "ended by the system"
        ApplicationExitInfo.REASON_SIGNALED -> "killed"
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        else -> "reason $reason"
    }
}

val Context.engine: Engine get() = (applicationContext as AirBuzzApp).engine

/** What the band is doing, for Home, Test buzz and the status notification. */
data class BandState(
    val busy: Boolean = false,
    val ringingWho: String? = null,
    /** Wall-clock time of the last command that reached the Air. */
    val lastReachedAt: Long? = null,
    /** The last command's failure, cleared by the next success. */
    val lastError: String? = null,
    /**
     * [lastError] came from the Air answering nothing although it was in reach (a silent spell), not from it being out
     * of reach, so Home can say "not answering" (Davor, 17 Nov).
     */
    val notAnswering: Boolean = false,
    /** Alerts that didn't reach the Air since the current problem started, as the problem notification counts them. */
    val missed: Int = 0,
    /** Wall-clock time the current problem started (Bluetooth off, or the first missed alert); null when there is none. */
    val problemSince: Long? = null,
    /**
     * Google Health is syncing with the Air, judged from its traffic on the open link, so a buzz may take up to about
     * 6 s (brief decisions 16 and 33). A sync lasts about 10–20 s and comes about every 15 minutes. Always false while
     * AirBuzz is off or the link is closed.
     */
    val healthSyncing: Boolean = false,
    /**
     * The listen-only open link to the Air is connected and subscribed right now. It drops and comes back often, usually
     * within minutes, and on 19 Nov it stayed up through silent spells, so on its own it doesn't mean the Air answers.
     */
    val linkOpen: Boolean = false,
    /**
     * Wall-clock time AirBuzz last heard anything from the Air, Google Health's traffic included, updated about once a
     * minute while it sends. Null until it is first heard after AirBuzz starts. Google Health syncs about every 15 min
     * in the day, and at night the Air still sends a few packets every 15 min.
     */
    val lastHeardAt: Long? = null,
)

/** A Test buzz in progress or finished. */
data class TestRun(
    val startedAt: Long,
    val buzzedMs: Long? = null,
    val result: BandResult? = null,
    val lines: List<String> = emptyList(),
    /** The band's phases and link so far, while the test runs, so Test buzz lists each step as it happens. */
    val phases: Map<String, Long> = emptyMap(),
    val link: String? = null,
)

/**
 * Decides what each alert does and drives the band. Lives as long as the process; the notification listener and
 * the foreground service both call into it. Everything runs on the main thread.
 */
class Engine(private val app: Context) {
    val store = SettingsStore(app)
    val log = ActivityLog(app)
    /** Chats seen in notifications, for the chat sheet (issue #33). */
    val chatBook = ChatBook(app)
    /** The chats of [pkg] with a notification showing right now; set by the notification listener once it is bound. */
    var chatsOnPhone: (String) -> List<ChatOption> = { emptyList() }
    /** AirBuzz's own step log, shared from Settings. */
    val debug = DebugLog(java.io.File(app.filesDir, "debug.log"))
    private val batteryStore = BatteryStore(app)
    /** The Fitbit Air's last battery reading, kept across restarts. */
    val battery: StateFlow<Battery> = batteryStore.battery
    private val batteryAgeState = MutableStateFlow(BatteryAge.NONE)
    /** How old [battery] is; changes as the reading crosses 1 h and 12 h, so Home can follow it. */
    val batteryAge: StateFlow<BatteryAge> = batteryAgeState
    private val main = Handler(Looper.getMainLooper())
    private val band = AirBand(app) { line -> note(line); testLine(line) }.also {
        it.onPhase = { testPhases() }
        it.onHealthSync = { syncing -> bandState.value = bandState.value.copy(healthSyncing = syncing) }
        it.onLink = { up ->
            bandState.value = bandState.value.copy(linkOpen = up)
            if (up) linkBack() else linkLost()
        }
        it.onHeardAgain = { onHeardAgain() }
        it.onHeard = { heard() }
        it.onDeaf = { deaf() }
        it.wantBattery = { batteryStore.value.due(System.currentTimeMillis(), BATTERY_IN_BUZZ_MS) }
        it.onBattery = ::onBatteryReading
    }

    private val bandState = MutableStateFlow(BandState())
    val state: StateFlow<BandState> = bandState
    private val testState = MutableStateFlow<TestRun?>(null)
    val test: StateFlow<TestRun?> = testState

    /**
     * [giveUpAt] (elapsed realtime) is how long a failed job may be retried; 0 means never, as for Test buzz.
     * [tries] counts the band runs so far, so a buzz that needed retries says so in the step log.
     */
    private class Job(
        val cmd: BandCommand,
        var ringKey: String? = null,
        /** For a ring, its app and caller, so a phone app re-posting the same call continues it. */
        val callFrom: String? = null,
        val giveUpAt: Long = 0L,
        /** What the alert was, such as "call", for the step log's timing line; null for Test buzz. */
        val what: String? = null,
        /** Elapsed realtime when the alert reached AirBuzz. */
        val alertAt: Long = 0L,
        /** The problem notification's title if this alert never reaches the Air, such as "Mum called · your Fitbit Air didn't ring". */
        val missTitle: String? = null,
        /** A message's notification key, so dismissing the notification drops the buzz it would still retry. */
        val alertKey: String? = null,
        val buzzed: () -> Unit = {},
        val done: (BandResult) -> Unit,
    ) {
        var tries = 0
        /** The message was seen on the phone (unlocked or notification dismissed), so a late buzz isn't wanted. */
        var seen = false
        /** A try found the Air answering nothing, so this message may wait [SILENT_MESSAGE_RETRY_FOR_MS]. */
        var sawSilent = false
        /** Elapsed realtime when this alert stops trying. */
        val until: Long get() = if (sawSilent) alertAt + SILENT_MESSAGE_RETRY_FOR_MS else giveUpAt
    }

    /** Whether notification [key] is still on the phone; set by the notification listener once it is connected. */
    var isShowing: (String) -> Boolean = { true }

    private val queue = ArrayDeque<Job>()
    private var running: Job? = null
    /** The queued or running Test buzz, so only its own steps reach the Test buzz screen. */
    private var testJob: Job? = null
    /** Jobs that failed, usually because Google Health held the Air, waiting [RETRY_MS] (or [SILENT_RING_RETRY_MS]) to try again. */
    private val waiting = mutableListOf<Job>()
    /**
     * Keeps the phone's processor awake while an alert is still on its way to the Fitbit Air. [main]'s timers stop
     * while the processor sleeps, so on 15 Nov a message's 5 s retry ran 46 s later, after its 30 s had passed.
     */
    private val awake = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirBuzz:alert").apply { setReferenceCounted(false) }
    private val lastBuzzByApp = HashMap<String, Long>()

    val settings: Settings get() = store.settings.value

    private val region: String by lazy {
        val tm = app.getSystemService(TelephonyManager::class.java)
        listOf(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country).firstOrNull { !it.isNullOrBlank() } ?: "SE"
    }

    private val strength: Int get() = if (settings.strong) AirHaptics.HIGH else AirHaptics.LOW

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                        if (st == BluetoothAdapter.STATE_ON || st == BluetoothAdapter.STATE_OFF) {
                            note("Bluetooth turned ${if (st == BluetoothAdapter.STATE_ON) "on" else "off"}")
                            // Out of reach gives way to Bluetooth being off, which checkBluetooth shows instead.
                            if (st == BluetoothAdapter.STATE_OFF) linkBack()
                            checkBluetooth()
                            if (st == BluetoothAdapter.STATE_ON) band.warmUp() else band.shutdown()
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> onUnlocked()
                    Intent.ACTION_SCREEN_OFF -> screenOffAt = SystemClock.elapsedRealtime()
                    Intent.ACTION_SCREEN_ON -> screenOffAt = 0L
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Pause and Turn off are set from the screens, so follow the settings rather than each place that changes them.
        MainScope().launch { store.settings.collect(::onSettings) }
    }

    fun setEnabled(on: Boolean) {
        store.update { it.copy(enabled = on) }
        if (!on) { band.shutdown(); linkBack(); clearSilentWarning() }
    }

    // Calls

    /** A ring that just ended, kept [CALL_REPOST_MS] in case its phone app shows the same call again. */
    private class EndedCall(val callFrom: String, val entryTime: Long, val start: Long, val at: Long)
    private var endedCall: EndedCall? = null

    /** The newest call's row and when its notification went, so a missed-call notification just after can mark it. */
    private class LastCall(val pkg: String, val entryTime: Long, var endedAt: Long = 0L)
    private var lastCall: LastCall? = null

    /** An incoming call notification from [pkg], identified by its notification [key]. */
    fun onIncomingCall(key: String, pkg: String, appLabel: String, caller: Caller, moment: Moment, postedAt: Long) {
        if (running?.ringKey == key || queue.any { it.ringKey == key } || waiting.any { it.ringKey == key }) return
        val who = caller.name ?: caller.number ?: "Unknown caller"
        val callFrom = "$pkg\u0000$who"
        // Some phone apps replace a ringing call's notification with a new one (a Reddit user's phone, 26 Nov): the
        // ring already under way takes the new key, so one call stays one row and one ring.
        (listOfNotNull(running) + queue + waiting).firstOrNull { it.ringKey != null && it.callFrom == callFrom }?.let { job ->
            note("$appLabel showed the same call again; it keeps ringing")
            job.ringKey = key
            return
        }
        val now = SystemClock.elapsedRealtime()
        val again = endedCall?.takeIf { it.callFrom == callFrom && now - it.at < CALL_REPOST_MS }
        endedCall = null
        val o = Rules.decideCall(caller, settings, moment, region)
        note("Call from $appLabel (${if (caller.number != null) "number" else if (caller.name != null) "name only" else "no caller ID"}" +
            "${posted(moment, postedAt)}): ${outcome(o)}" +
            (if (o == Outcome.Buzz) Rules.quietReason(settings, moment)?.let { " (rings anyway: $it)" } ?: "" else ""))
        when (o) {
            is Outcome.Skip -> {
                log.add(Entry(moment.nowMs, Kind.CALL, pkg, appLabel, who, caller.number, Result.SKIPPED, o.reason))
                lastCall = LastCall(pkg, moment.nowMs)
            }
            else -> {
                // The same call shown again just after its notification went: continue its row rather than add one.
                val entryTime = again?.entryTime ?: moment.nowMs
                if (again != null) {
                    note("$appLabel showed the same call again; continuing it")
                    log.updateLast({ it.time == entryTime && it.kind == Kind.CALL }) { it.copy(result = Result.BUZZED, reason = null, missed = false) }
                } else log.add(Entry(entryTime, Kind.CALL, pkg, appLabel, who, caller.number, Result.BUZZED))
                lastCall = LastCall(pkg, entryTime)
                bandState.value = bandState.value.copy(ringingWho = who)
                Notifications.showRinging(app, who)
                val start = again?.start ?: now
                var latency: Long? = null
                // A ring goes ahead of queued message taps.
                queue.addFirst(Job(BandCommand.Ring(strength), ringKey = key, callFrom = callFrom, giveUpAt = start + RING_RETRY_FOR_MS, what = "call",
                    alertAt = start, missTitle = "$who called · your Fitbit Air didn’t ring",
                    buzzed = { latency = SystemClock.elapsedRealtime() - start }) { r ->
                    Notifications.cancelRinging(app)
                    bandState.value = bandState.value.copy(ringingWho = null)
                    endedCall = EndedCall(callFrom, entryTime, start, SystemClock.elapsedRealtime())
                    log.updateLast({ it.time == entryTime && it.kind == Kind.CALL }) {
                        if (r.taps > 0) it.copy(rangMs = SystemClock.elapsedRealtime() - start, latencyMs = latency ?: it.latencyMs)
                        // AirBuzz only sees the call's notification go, so it can't tell answered from missed or declined.
                        else if (r.ok) it.copy(result = Result.SKIPPED, reason = "stopped ringing before the Fitbit Air was reached")
                        else it.copy(result = Result.FAILED, reason = r.message)
                    }
                })
                pump()
            }
        }
    }

    /** The call notification [key] went away or was answered. */
    fun onCallEnded(key: String, why: String = "") {
        note("Call ended$why")
        lastCall?.let { if (it.endedAt == 0L) it.endedAt = SystemClock.elapsedRealtime() }
        if (running?.ringKey == key) band.stopRing()
        queue.removeAll { job -> (job.ringKey == key).also { if (it) job.done(cancelled()) } }
        waiting.removeAll { job -> (job.ringKey == key).also { if (it) job.done(cancelled()) } }
        keepAwake()
    }

    /**
     * A missed-call notification from one of [pkgs]: when the last call's notification went just before, its row says
     * missed. Google's Phone app posts it 0.1–1.3 s after the call stops ringing, WhatsApp 0.1–8.6 s (Davor's phone, 26 Nov).
     */
    fun onMissedCall(pkgs: Set<String>, appLabel: String) {
        val c = lastCall ?: return
        if (c.pkg !in pkgs || c.endedAt == 0L || SystemClock.elapsedRealtime() - c.endedAt > MISSED_AFTER_MS) return
        lastCall = null
        note("$appLabel reported the call as missed")
        log.updateLast({ it.time == c.entryTime && it.kind == Kind.CALL }) { it.copy(missed = true) }
    }

    /** Silence from the "Ringing your Fitbit Air" notification. */
    fun silence() {
        note("Silence pressed")
        running?.takeIf { it.ringKey != null }?.let { band.stopRing() }
        queue.removeAll { job -> (job.ringKey != null).also { if (it) job.done(cancelled()) } }
        waiting.removeAll { job -> (job.ringKey != null).also { if (it) job.done(cancelled()) } }
        keepAwake()
    }

    // Messages

    fun onMessage(key: String, pkg: String, appLabel: String, who: String?, from: MessageFrom, moment: Moment, postedAt: Long) {
        if (from.chatId != null && from.chatName != null) {
            chatBook.record(SeenChat(pkg, from.chatId, from.chatName, from.group, moment.nowMs))
            pinTypedChat(pkg, from)
        }
        val o = Rules.decideMessage(pkg, from, settings, moment, lastBuzzByApp[pkg], region)
        note("Message from $appLabel${if (o is Outcome.Skip) "" else " (${posted(moment, postedAt).removePrefix(", ")})"}: ${outcome(o)}")
        when (o) {
            is Outcome.Skip -> log.add(Entry(moment.nowMs, Kind.MESSAGE, pkg, appLabel, who, null, Result.SKIPPED, o.reason, chatId = from.chatId))
            Outcome.Merge -> {
                val merged = log.updateLast({ it.pkg == pkg && it.kind == Kind.MESSAGE && it.result == Result.BUZZED }) {
                    it.copy(count = it.count + 1)
                }
                if (!merged) log.add(Entry(moment.nowMs, Kind.MESSAGE, pkg, appLabel, who, null, Result.MERGED))
            }
            Outcome.Buzz -> {
                lastBuzzByApp[pkg] = moment.nowMs
                val entry = Entry(moment.nowMs, Kind.MESSAGE, pkg, appLabel, who, null, Result.BUZZED)
                log.add(entry)
                val start = SystemClock.elapsedRealtime()
                var latency: Long? = null
                queue.addLast(Job(BandCommand.Taps(settings.messageTaps, strength), giveUpAt = start + MESSAGE_RETRY_FOR_MS,
                    what = "$appLabel message", alertAt = start,
                    missTitle = "${if (who != null) "$who on $appLabel" else "$appLabel message"} · your Fitbit Air didn’t buzz", alertKey = key,
                    buzzed = { latency = SystemClock.elapsedRealtime() - start }) { r ->
                    log.updateLast({ it.time == entry.time && it.pkg == pkg }) {
                        if (r.taps > 0) it.copy(latencyMs = latency)
                        else if (r.ok) it.copy(result = Result.SKIPPED, reason = r.message.removeSuffix(".").replaceFirstChar(Char::lowercase))
                        else it.copy(result = Result.FAILED, reason = r.message)
                    }
                })
                pump()
            }
        }
    }

    /**
     * A chosen chat that was typed or picked from contacts has no ID until it messages; on its first message it takes
     * the chat's ID, so a later rename keeps it chosen (issue #33).
     */
    private fun pinTypedChat(pkg: String, from: MessageFrom) {
        val list = settings.chats[pkg] ?: return
        if (list.any { it.id == from.chatId }) return
        val i = list.indexOfFirst { it.id == null && Rules.chatMatches(it, from) }
        if (i < 0) return
        note("A chosen $pkg chat now matches by its ID")
        store.update { s ->
            val chats = s.chats[pkg] ?: return@update s
            s.copy(chats = s.chats + (pkg to chats.map { if (it.id == null && Rules.chatMatches(it, from)) it.copy(id = from.chatId, group = from.group) else it }))
        }
    }

    /**
     * What the chat sheet offers for [pkg], without repeats: chats showing now, chats seen earlier, contacts (people
     * only), then chosen chats still waiting for their first message. Chats with an ID come before contacts, so a
     * contact who has messaged shows once, with the chat's ID (on Davor's phone 0.8.4 listed Ozren twice).
     */
    fun chatOptions(pkg: String): List<ChatOption> {
        val now = chatsOnPhone(pkg)
        val seen = chatBook.seen.value.filter { it.pkg == pkg }.sortedByDescending { it.lastSeen }
            .map { ChatOption(pkg, it.id, it.name, it.group, ChatSource.SEEN) }
        val typed = settings.chats[pkg].orEmpty().filter { it.id == null }.map { ChatOption(pkg, null, it.name, it.group, ChatSource.TYPED) }
        val out = mutableListOf<ChatOption>()
        fun known(o: ChatOption) = out.any { (o.id != null && it.id == o.id) || it.name.equals(o.name, ignoreCase = true) && it.group == o.group }
        (now + seen + chatBook.contacts(pkg) + typed).forEach { if (!known(it)) out += it }
        return out
    }

    // Test buzz and Try

    /** One Test buzz with the message pattern, reported step by step on the Test buzz screen. */
    fun testBuzz(taps: Int = 1) {
        if (testState.value?.result == null && testState.value != null) return
        val started = SystemClock.elapsedRealtime()
        testState.value = TestRun(started)
        val job = Job(BandCommand.Taps(taps, strength), buzzed = {
            testState.value = testState.value?.copy(buzzedMs = SystemClock.elapsedRealtime() - started)
        }) { r ->
            testState.value = testState.value?.copy(result = r)
            log.add(Entry(System.currentTimeMillis(), Kind.TEST, app.packageName, "Test buzz", null, null,
                if (r.taps > 0) Result.BUZZED else Result.FAILED, if (r.taps > 0) null else r.message, latencyMs = r.firstBuzzMs))
        }
        testJob = job
        queue.addLast(job)
        pump()
    }

    fun clearTest() { if (testState.value?.result != null) testState.value = null }

    private fun testLine(line: String) {
        val t = testState.value ?: return
        if (t.result != null) return
        val ms = SystemClock.elapsedRealtime() - t.startedAt
        testState.value = t.copy(lines = t.lines + String.format(Locale.ROOT, "%5.1f s  %s", ms / 1000.0, line))
        testPhases()
    }

    /** Copies the running Test buzz's phases so far into [test], so its steps appear as they happen. */
    private fun testPhases() {
        val t = testState.value ?: return
        if (t.result != null || running !== testJob) return
        testState.value = t.copy(phases = band.phasesSoFar, link = band.link)
    }

    // The band queue

    private fun pump() {
        startNext()
        keepAwake()
    }

    /** Holds [awake] while a job runs, is queued or waits to retry; each call extends it, up to [AWAKE_MAX_MS]. */
    private fun keepAwake() {
        if (running != null || queue.isNotEmpty() || waiting.isNotEmpty()) awake.acquire(AWAKE_MAX_MS)
        else if (awake.isHeld) awake.release()
    }

    private fun startNext() {
        if (running != null || band.busy) return
        val job = queue.pollFirst() ?: return
        running = job
        bandState.value = bandState.value.copy(busy = true)
        band.keepLinkOpen = settings.enabled && OPEN_LINK
        job.tries++
        band.run(job.cmd, {
            if (job.what != null) {
                val after = SystemClock.elapsedRealtime() - job.alertAt
                note("First buzz ${seconds(after)} after the ${job.what} arrived" + if (job.tries > 1) ", on try ${job.tries}" else "")
            }
            job.buzzed()
        }) { r ->
            running = null
            // A battery-only read never retries and never shows a problem: the next buzz or opening tries again.
            if (job.cmd == BandCommand.Battery) {
                bandState.value = bandState.value.copy(busy = false)
                job.done(r)
                main.post { pump() }
                return@run
            }
            val failed = !r.ok && r.taps == 0
            // A buzz that lost the Air to Google Health is tried again a little later rather than dropped, for up to
            // 30 s for a message, unless the message was seen on the phone meanwhile (Davor left the choice to the app
            // thread on 11 Nov: a late buzz beats a missed one, but not for something already read).
            // A call the Air didn't answer at all is tried again almost at once: nothing was taken from Google Health,
            // and on 12 Nov the 5 s wait put a ringing call's first buzz at 14.3 s.
            // A message the Air answered nothing for keeps trying for up to 3 min, every 15 s after its first 30 s:
            // on 16 Nov silent spells ended 61–141 s after four messages that were missed (Davor, 16 Nov). Late
            // tries are dropped once the notification is gone from the phone, as when it was read elsewhere.
            // Messages waiting together try as one, so a silent spell costs one try per round rather than one per
            // message (17 Nov: five messages made 37 connects in 3.4 min). Each keeps its own window (Davor, 17 Nov).
            val now = SystemClock.elapsedRealtime()
            val message = job.alertKey != null
            val group = if (message) waiting.filter { it.alertKey != null } + job else listOf(job)
            if (r.bandSilent && message) group.forEach { it.sawSilent = true }
            val wait = when {
                r.bandSilent && job.ringKey != null -> SILENT_RING_RETRY_MS
                // The round comes at the pace of the message that still wants it soonest.
                r.bandSilent && message && group.all { now + RETRY_MS >= it.giveUpAt } -> SILENT_LATE_RETRY_MS
                else -> RETRY_MS
            }
            // Test buzz gets the one retry a message would get when the Air answered nothing, as right after Bluetooth
            // came back on (15 Nov), so it doesn't fail where a message would buzz (Davor, 15 Nov). Its time still
            // counts from the press.
            val retry = failed && !job.seen && if (job === testJob) r.bandSilent && job.tries < 2 else now + wait < job.until
            // Waiting messages whose window ends before the next round give up with this try.
            val ending = if (failed && message) waiting.filter { it.alertKey != null && now + wait >= it.until } else emptyList()
            val missTitles = mutableListOf<String>()
            bandState.value = bandState.value.copy(
                busy = false,
                lastReachedAt = if (r.taps > 0) System.currentTimeMillis() else bandState.value.lastReachedAt,
                lastError = if (!failed) null else if (retry) bandState.value.lastError else r.message,
                notAnswering = if (!failed) false else if (retry) bandState.value.notAnswering else r.bandSilent,
            )
            // A failed try that will be retried keeps the warning it found; any other result replaced it.
            silentWarned = silentWarned && failed && retry
            if (retry) {
                val others = group.size - 1 - ending.size
                note("${r.message} Trying again in ${seconds(wait)}" +
                    if (others > 0) " for this and $others other waiting message${if (others == 1) "" else "s"}" else "")
                waiting += job
                if (!message) main.postDelayed({ retryNow(job) }, wait)
            } else if (failed && job.seen) {
                note("Not retrying: the message was seen on the phone")
                job.done(seen())
            } else {
                if (failed) note("Gave up: ${r.message}")
                if (failed) job.missTitle?.let(missTitles::add)
                if (r.taps > 0) { clearProblem(); linkBack() }
                job.done(r)
                if (r.taps > 0 && message) coverWaiting(r)
            }
            if (ending.isNotEmpty()) {
                note("Gave up too: ${ending.size} other waiting message${if (ending.size == 1) "" else "s"}, out of time")
                waiting.removeAll(ending.toSet())
                ending.forEach { it.missTitle?.let(missTitles::add); it.done(r) }
            }
            if (missTitles.isNotEmpty()) missed(missTitles, r, call = job.ringKey != null)
            if (message && waiting.any { it.alertKey != null }) retryMessagesIn(wait)
            main.post { pump() }
        }
    }

    // The Fitbit Air's battery (Davor, 27 Nov): read inside a buzz's session when the last reading is over
    // [BATTERY_IN_BUZZ_MS] old, and on its own when AirBuzz is opened and the reading is over an hour old.

    private fun onBatteryReading(r: AirBattery.Reading) {
        val (b, alert) = batteryStore.value.after(r, System.currentTimeMillis())
        batteryStore.set(b)
        note("Fitbit Air battery: ${r.percent}%" + (if (r.onCharger == true) ", on its charger" else "") +
            (r.millivolts?.let { ", $it mV" } ?: ""))
        if (alert) Notifications.showLowBattery(app, r.percent)
        if (b.charging) Notifications.cancelLowBattery(app)
        followBatteryAge()
    }

    private val batteryAgeTick = Runnable { followBatteryAge() }

    private fun followBatteryAge() {
        main.removeCallbacks(batteryAgeTick)
        val now = System.currentTimeMillis()
        val b = batteryStore.value
        batteryAgeState.value = b.age(now)
        b.nextAgeChange(now)?.let { main.postDelayed(batteryAgeTick, it - now + 1_000) }
    }

    /** AirBuzz came to the front: an hour-old reading is read again unless the band is busy or Google Health syncs. */
    fun onAppOpened() {
        followBatteryAge()
        if (!settings.enabled || !batteryStore.value.due(System.currentTimeMillis(), Battery.HOUR_MS)) return
        if (bandState.value.healthSyncing || running != null || queue.isNotEmpty() || waiting.isNotEmpty()) return
        note("Reading the Fitbit Air's battery: AirBuzz was opened")
        queue.addLast(Job(BandCommand.Battery) {})
        pump()
    }

    /** Puts a waiting call or Test buzz [job] back in the queue. */
    private fun retryNow(job: Job) {
        if (!waiting.remove(job)) return
        if (job.ringKey != null) queue.addFirst(job) else queue.addLast(job)
        pump()
    }

    /** Elapsed realtime of the waiting messages' next round; 0 when none is set. */
    private var messageRoundAt = 0L
    private val messageRound = Runnable { retryMessages() }

    /** Sets the waiting messages' next round [wait] from now, unless one is already set sooner. */
    private fun retryMessagesIn(wait: Long) {
        val at = SystemClock.elapsedRealtime() + wait
        if (messageRoundAt in 1..at) return
        messageRoundAt = at
        main.removeCallbacks(messageRound)
        main.postDelayed(messageRound, wait)
    }

    /**
     * One round for the waiting messages: late ones no longer on the phone are dropped, and the oldest of the rest
     * tries for all of them; when it buzzes, [coverWaiting] counts the others as buzzed with it.
     */
    private fun retryMessages() {
        messageRoundAt = 0L
        main.removeCallbacks(messageRound)
        val now = SystemClock.elapsedRealtime()
        val gone = waiting.filter { job -> job.alertKey?.let { now >= job.giveUpAt && !isShowing(it) } == true }
        gone.forEach { job ->
            note("Not retrying: the ${job.what} is no longer on the phone, maybe read elsewhere")
            waiting.remove(job)
            job.done(seen("Gone from the phone before the Fitbit Air was reached."))
        }
        waiting.filter { it.alertKey != null }.minByOrNull { it.alertAt }?.let { lead ->
            waiting.remove(lead)
            queue.addLast(lead)
        }
        pump()
    }

    /** The Air answers again after a silent spell, so messages waiting out that spell try now rather than at their timer. */
    private fun onHeardAgain() {
        // Home stops saying "not answering" now, not at the next buzz: on 19 Nov the Air answered at 12:34 with no
        // message left to send, and Home kept the warning. The missed-alert notification stays.
        if (bandState.value.notAnswering) {
            bandState.value = bandState.value.copy(lastError = null, notAnswering = false)
            if (problem?.cause == Cause.SILENT) clearProblem()
        }
        if (waiting.any { it.alertKey != null }) retryMessages()
    }

    // The open link hearing nothing for 20 min (AirBand.DEAF_MS) means the Air isn't answering, as in the silent spells
    // of 19 Nov, which showed nothing on Home until a message failed. Home says so without a notification, which waits
    // for a missed alert, and stops as soon as the Air is heard (issue #32).

    /** [BandState.lastError] was set by [deaf], not by a failed alert. */
    private var silentWarned = false

    private fun heard() {
        bandState.value = bandState.value.copy(lastHeardAt = System.currentTimeMillis())
        if (silentWarned) {
            note("Home no longer shows the Fitbit Air not answering")
            clearSilentWarning()
        }
    }

    private fun deaf() {
        if (!settings.enabled || bandState.value.lastError != null) return
        note("Open link: heard nothing from the Fitbit Air for 20 min; Home shows it not answering")
        silentWarned = true
        bandState.value = bandState.value.copy(lastError = "Your Fitbit Air isn’t answering.", notAnswering = true)
    }

    private fun clearSilentWarning() {
        if (!silentWarned) return
        silentWarned = false
        bandState.value = bandState.value.copy(lastError = null, notAnswering = false)
    }

    /** A message reached the Air, so other messages that already tried and are waiting count as buzzed with it. */
    private fun coverWaiting(r: BandResult) {
        val covered = (waiting + queue).filter { it.alertKey != null && it.tries > 0 }
        if (covered.isEmpty()) return
        note("That buzz also covers ${covered.size} waiting message${if (covered.size == 1) "" else "s"}")
        waiting.removeAll(covered.toSet())
        queue.removeAll(covered.toSet())
        covered.forEach { it.done(r) }
        keepAwake()
    }

    private fun cancelled() = BandResult(true, "Stopped before the Fitbit Air was reached.", null, 0, 0, 0, 0, 0, "new", emptyMap())

    private fun seen(why: String = "Seen on the phone before the Fitbit Air was reached.") =
        BandResult(true, why, null, 0, 0, 0, 0, 0, "new", emptyMap())

    /** The user dismissed message notification [key], so its buzz isn't wanted any more if it is still waiting. */
    fun onMessageDismissed(key: String) = dropSeen("Message dismissed") { it.alertKey == key }

    /** The phone was unlocked, so every message still waiting to buzz has been seen. */
    private fun onUnlocked() {
        dropSeen("Phone unlocked") { it.alertKey != null }
        // The timer below stops while the phone sleeps, so unlocking checks again.
        checkLinkDown()
    }

    // The open link being gone a while means the Air is out of reach (16 Nov: left at home, nothing showed for 26 min
    // until a message missed). Home then shows it, without a notification, which waits for a missed alert; the link
    // coming back clears both (Davor, 16 Nov). Drops that reopen within minutes are common, hence the wait.

    /** Elapsed realtime when the open link was lost or first asked for; 0 while it is open. */
    private var linkDownAt = 0L
    /** [BandState.lastError] was set by [checkLinkDown], not by a failed alert. */
    private var linkWarned = false
    private val linkDownCheck = Runnable { checkLinkDown() }

    private fun linkLost() {
        if (linkDownAt != 0L) return
        linkDownAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(linkDownCheck)
        main.postDelayed(linkDownCheck, LINK_DOWN_WARN_MS)
    }

    private fun checkLinkDown() {
        val down = SystemClock.elapsedRealtime() - linkDownAt
        if (linkDownAt == 0L || down < LINK_DOWN_WARN_MS || bandState.value.lastError != null) return
        if (!settings.enabled || !bluetoothOn() || band.findBondedAir() == null) return
        note("Open link: down for ${down / 60_000} min; Home shows the Fitbit Air out of reach")
        linkWarned = true
        bandState.value = bandState.value.copy(lastError = "Your Fitbit Air is out of reach.", notAnswering = false)
    }

    /** The link is back, a command reached the Air, or the link isn't wanted (AirBuzz off, Bluetooth off). */
    private fun linkBack() {
        main.removeCallbacks(linkDownCheck)
        linkDownAt = 0L
        if (linkWarned) note("Home no longer shows the Fitbit Air out of reach")
        val outOfReach = problem?.cause == Cause.OUT_OF_REACH
        if (linkWarned || outOfReach) bandState.value = bandState.value.copy(lastError = null, notAnswering = false)
        linkWarned = false
        if (outOfReach) clearProblem()
    }

    private fun dropSeen(why: String, match: (Job) -> Boolean) {
        val dropped = (queue.filter(match) + waiting.filter(match)).onEach { it.seen = true }
        running?.takeIf(match)?.seen = true
        if (dropped.isEmpty()) return
        note("$why; dropped ${dropped.size} waiting buzz${if (dropped.size == 1) "" else "es"}")
        queue.removeAll(dropped.toSet())
        waiting.removeAll(dropped.toSet())
        dropped.forEach { it.done(seen()) }
        keepAwake()
    }

    // Problems while AirBuzz is closed (brief decision 41: "quiet, then loud"): silent on the Problems channel while
    // Bluetooth is off, cleared when it is back on; loud on the Missed alerts channel once an alert didn't reach the Air,
    // kept until tapped or swiped (Davor, 17 Nov). Never while AirBuzz is off or paused.

    /** Why an alert didn't reach the Air, as the notification says it, with the one fix when there is one. */
    private enum class Cause(val text: String, val fixLabel: String?) {
        BLUETOOTH_OFF("Bluetooth is off", "Turn on"),
        NEARBY("AirBuzz isn’t allowed to use Nearby devices", "Allow"),
        NO_AIR("No Fitbit Air is paired with this phone", null),
        GOOGLE_HEALTH("Google Health kept your Fitbit Air busy", null),
        SILENT("Your Fitbit Air didn’t answer", null),
        OUT_OF_REACH("Your Fitbit Air was out of reach", null),
    }

    private class ProblemNote(val since: Long, var cause: Cause, var missed: Int = 0)

    private var problem: ProblemNote? = null
    /** Alerts the missed-alert notification counts; it keeps counting until it is tapped or swiped. */
    private var missedShown = 0
    private val pauseEnded = Runnable { onSettings(settings) }

    private val active: Boolean get() = settings.enabled && settings.pausedUntil <= System.currentTimeMillis()

    private fun bluetoothOn() = app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != false

    /** Shows Bluetooth being off, and clears the notification when it is back on or AirBuzz is off or paused. */
    private fun checkBluetooth() {
        if (!active) return clearProblem()
        val on = bluetoothOn()
        val p = problem
        if (!on && p == null) {
            problem = ProblemNote(System.currentTimeMillis(), Cause.BLUETOOTH_OFF)
            showProblem()
        } else if (on && p?.cause == Cause.BLUETOOTH_OFF) {
            clearProblem()
        }
    }

    private fun onSettings(s: Settings) {
        main.removeCallbacks(pauseEnded)
        val left = s.pausedUntil - System.currentTimeMillis()
        if (s.enabled && left > 0) main.postDelayed(pauseEnded, left)
        checkBluetooth()
        // With [OPEN_LINK] the link stays up while AirBuzz is on, paused included, since chosen callers still ring.
        if (!band.busy) band.keepLinkOpen = s.enabled && OPEN_LINK
        if (s.enabled) band.warmUp()
    }

    /** Alerts titled [titles] didn't reach the Air; [call] says the last of them was a call. */
    private fun missed(titles: List<String>, r: BandResult, call: Boolean) {
        if (!active) return
        val cause = when {
            !bluetoothOn() -> Cause.BLUETOOTH_OFF
            Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED -> Cause.NEARBY
            band.findBondedAir() == null -> Cause.NO_AIR
            // The Bluetooth link worked but the Air sent nothing back; seen once, cause unknown (12 Nov).
            r.bandSilent -> Cause.SILENT
            // A handshake the Air answered means it was in reach and the session was the problem.
            r.handshakes > 0 -> Cause.GOOGLE_HEALTH
            else -> Cause.OUT_OF_REACH
        }
        val p = problem ?: ProblemNote(System.currentTimeMillis(), cause).also { problem = it }
        p.cause = cause
        p.missed += titles.size
        showProblem()
        // The notification stays until tapped or swiped, even after a later buzz gets through, and counts every alert
        // missed while it shows (Davor, 17 Nov).
        missedShown = titles.size + if (Notifications.missedShowing(app)) missedShown else 0
        val title = if (missedShown == 1) titles.single() else "$missedShown alerts didn’t reach your Fitbit Air"
        // A message gives up after 3 min of silence (Davor's wording, 17 Nov); a call after its ring, so it says less.
        val text = if (cause == Cause.SILENT && !call) "Your Fitbit Air didn’t answer for 3 minutes. It usually comes back by itself."
            else cause.text
        note("Notification: $missedShown missed (${cause.text})")
        Notifications.showMissed(app, title, text, cause.fixLabel,
            if (cause == Cause.BLUETOOTH_OFF) Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) else Intent(app, MainActivity::class.java))
    }

    /** Updates Home's shelf, and shows the silent notification while Bluetooth is off and nothing was missed yet. */
    private fun showProblem() {
        val p = problem ?: return
        bandState.value = bandState.value.copy(missed = p.missed, problemSince = p.since)
        if (p.missed == 0) {
            note("Notification: Bluetooth is off")
            Notifications.showBluetoothOff(app)
        }
    }

    /** Clears the problem from Home and the Bluetooth-off notification; a missed-alert notification stays. */
    private fun clearProblem() {
        if (problem == null) return
        problem = null
        note("Problem cleared")
        Notifications.cancelBluetoothOff(app)
        bandState.value = bandState.value.copy(missed = 0, problemSince = null)
    }

    /** The phone's state now. [doNotDisturb] comes from the notification listener. */
    fun moment(doNotDisturb: Boolean, phoneInUse: Boolean): Moment {
        val c = Calendar.getInstance()
        return Moment(c.timeInMillis, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), doNotDisturb, phoneInUse)
    }

    fun note(line: String) {
        Log.i(TAG, line)
        debug.add(line)
    }

    /** How long Android took to hand the notification to AirBuzz, which a buzz's timing doesn't include. */
    private fun posted(moment: Moment, postedAt: Long) = ", posted ${seconds(moment.nowMs - postedAt)} before; ${phoneState()}"

    /** Elapsed realtime when the screen last went off; 0 while it is on or since AirBuzz started. */
    private var screenOffAt = 0L

    /** Whether the phone was asleep when an alert came, so the step log tells a deep-sleep buzz from others (16 Nov). */
    private fun phoneState(): String {
        val pm = app.getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return "screen on"
        val off = if (screenOffAt == 0L) "screen off" else "screen off ${(SystemClock.elapsedRealtime() - screenOffAt) / 60_000} min"
        return off + when {
            pm.isDeviceIdleMode -> ", deep sleep"
            Build.VERSION.SDK_INT >= 33 && pm.isDeviceLightIdleMode -> ", light sleep"
            else -> ""
        }
    }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    private fun outcome(o: Outcome) = when (o) {
        Outcome.Buzz -> "buzz"
        Outcome.Merge -> "within 15 s of the last buzz, merged"
        is Outcome.Skip -> "ignored (${o.reason})"
    }

    companion object {
        const val TAG = "AirBuzz"
        /**
         * Keep a listen-only link to the Air open between alerts. It was off in 0.6.50 to test whether it causes the
         * silent spells; on 18 Nov the worn band went silent for 23 min without it, so it is back from 0.6.51 (Davor,
         * 18 Nov). It brings the "Google Health is syncing" hint, the out-of-reach warning, the early retry when the Air
         * is heard again, and faster buzzes.
         */
        const val OPEN_LINK = true
        const val RETRY_MS = 5_000L
        /** The wait before a call's next try when the Air answered nothing on the last one. */
        const val SILENT_RING_RETRY_MS = 1_000L
        /** A message buzz is worth up to half a minute late; after that it is logged as failed. */
        const val MESSAGE_RETRY_FOR_MS = 30_000L
        /** While the Air answers nothing, a message keeps trying this long from its arrival... */
        const val SILENT_MESSAGE_RETRY_FOR_MS = 180_000L
        /** ...every this often once its first [MESSAGE_RETRY_FOR_MS] have passed. */
        const val SILENT_LATE_RETRY_MS = 15_000L
        /** A call keeps trying for as long as AirBuzz would ring it, unless it ends first. */
        const val RING_RETRY_FOR_MS = 60_000L
        /** How soon after a ring ends a new notification for the same call counts as that call shown again. */
        const val CALL_REPOST_MS = 3_000L
        /** A buzz also reads the battery when the last reading is this old. */
        const val BATTERY_IN_BUZZ_MS = 45 * 60_000L
        /** How soon after a call's notification goes a missed-call notification still belongs to it. */
        const val MISSED_AFTER_MS = 15_000L
        /**
         * The longest one stretch of [keepAwake] can last: a 60 s ring plus its retries, with room to spare. Each try
         * renews it, so a message waiting out a silent spell for [SILENT_MESSAGE_RETRY_FOR_MS] stays awake throughout.
         */
        const val AWAKE_MAX_MS = 150_000L
        /** How long the open link must be gone before Home says the Air is out of reach. */
        const val LINK_DOWN_WARN_MS = 10 * 60_000L
    }
}
