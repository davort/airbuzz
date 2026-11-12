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
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import com.davort.airbuzz.band.AirBand
import com.davort.airbuzz.band.AirHaptics
import com.davort.airbuzz.band.BandCommand
import com.davort.airbuzz.band.BandResult
import com.davort.airbuzz.data.ActivityLog
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
        val stopped = last?.let {
            val where = if (it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) "on screen" else "in the background"
            " Last stopped ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date(it.timestamp))} $where: " +
                exitReason(it.reason) + (it.description?.takeIf(String::isNotBlank)?.let { d -> " ($d)" } ?: "")
        } ?: ""
        return "AirBuzz $version started${started ?: ""}.$stopped"
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
    /** Alerts that didn't reach the Air since the current problem started, as the problem notification counts them. */
    val missed: Int = 0,
    /** Wall-clock time the current problem started (Bluetooth off, or the first missed alert); null when there is none. */
    val problemSince: Long? = null,
)

/** A Test buzz in progress or finished. */
data class TestRun(
    val startedAt: Long,
    val buzzedMs: Long? = null,
    val result: BandResult? = null,
    val lines: List<String> = emptyList(),
)

/**
 * Decides what each alert does and drives the band. Lives as long as the process; the notification listener and
 * the foreground service both call into it. Everything runs on the main thread.
 */
class Engine(private val app: Context) {
    val store = SettingsStore(app)
    val log = ActivityLog(app)
    /** AirBuzz's own step log, shared from Settings. */
    val debug = DebugLog(java.io.File(app.filesDir, "debug.log"))
    private val main = Handler(Looper.getMainLooper())
    private val band = AirBand(app) { line -> note(line); testLine(line) }

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
        val ringKey: String? = null,
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
    }

    private val queue = ArrayDeque<Job>()
    private var running: Job? = null
    /** Jobs that failed, usually because Google Health held the Air, waiting [RETRY_MS] to try again. */
    private val waiting = mutableListOf<Job>()
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
                            checkBluetooth()
                            if (st == BluetoothAdapter.STATE_ON) band.warmUp() else band.shutdown()
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> onUnlocked()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Pause and Turn off are set from the screens, so follow the settings rather than each place that changes them.
        MainScope().launch { store.settings.collect(::onSettings) }
    }

    fun setEnabled(on: Boolean) {
        store.update { it.copy(enabled = on) }
        if (!on) band.shutdown()
    }

    // Calls

    /** An incoming call notification from [pkg], identified by its notification [key]. */
    fun onIncomingCall(key: String, pkg: String, appLabel: String, caller: Caller, moment: Moment, postedAt: Long) {
        if (running?.ringKey == key || queue.any { it.ringKey == key } || waiting.any { it.ringKey == key }) return
        val who = caller.name ?: caller.number ?: "Unknown caller"
        val o = Rules.decideCall(caller, settings, moment, region)
        note("Call from $appLabel (${if (caller.number != null) "number" else if (caller.name != null) "name only" else "no caller ID"}" +
            "${posted(moment, postedAt)}): ${outcome(o)}")
        when (o) {
            is Outcome.Skip -> log.add(Entry(moment.nowMs, Kind.CALL, pkg, appLabel, who, caller.number, Result.SKIPPED, o.reason))
            else -> {
                val entry = Entry(moment.nowMs, Kind.CALL, pkg, appLabel, who, caller.number, Result.BUZZED)
                log.add(entry)
                bandState.value = bandState.value.copy(ringingWho = who)
                Notifications.showRinging(app, who)
                val start = SystemClock.elapsedRealtime()
                var latency: Long? = null
                // A ring goes ahead of queued message taps.
                queue.addFirst(Job(BandCommand.Ring(strength), ringKey = key, giveUpAt = start + RING_RETRY_FOR_MS, what = "call",
                    alertAt = start, missTitle = "$who called · your Fitbit Air didn't ring",
                    buzzed = { latency = SystemClock.elapsedRealtime() - start }) { r ->
                    Notifications.cancelRinging(app)
                    bandState.value = bandState.value.copy(ringingWho = null)
                    log.updateLast({ it.time == entry.time && it.kind == Kind.CALL }) {
                        if (r.taps > 0) it.copy(rangMs = SystemClock.elapsedRealtime() - start, latencyMs = latency)
                        else if (r.ok) it.copy(result = Result.SKIPPED, reason = "answered before the Fitbit Air was reached")
                        else it.copy(result = Result.FAILED, reason = r.message)
                    }
                })
                pump()
            }
        }
    }

    /** The call notification [key] went away or was answered. */
    fun onCallEnded(key: String) {
        note("Call ended")
        if (running?.ringKey == key) band.stopRing()
        queue.removeAll { job -> (job.ringKey == key).also { if (it) job.done(cancelled()) } }
        waiting.removeAll { job -> (job.ringKey == key).also { if (it) job.done(cancelled()) } }
    }

    /** Silence from the "Ringing your Fitbit Air" notification. */
    fun silence() {
        note("Silence pressed")
        running?.takeIf { it.ringKey != null }?.let { band.stopRing() }
        queue.removeAll { job -> (job.ringKey != null).also { if (it) job.done(cancelled()) } }
        waiting.removeAll { job -> (job.ringKey != null).also { if (it) job.done(cancelled()) } }
    }

    // Messages

    fun onMessage(key: String, pkg: String, appLabel: String, who: String?, moment: Moment, postedAt: Long) {
        val o = Rules.decideMessage(pkg, settings, moment, lastBuzzByApp[pkg])
        note("Message from $appLabel${if (o is Outcome.Skip) "" else " (${posted(moment, postedAt).removePrefix(", ")})"}: ${outcome(o)}")
        when (o) {
            is Outcome.Skip -> log.add(Entry(moment.nowMs, Kind.MESSAGE, pkg, appLabel, who, null, Result.SKIPPED, o.reason))
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
                    missTitle = "${if (who != null) "$who on $appLabel" else "$appLabel message"} · your Fitbit Air didn't buzz", alertKey = key,
                    buzzed = { latency = SystemClock.elapsedRealtime() - start }) { r ->
                    log.updateLast({ it.time == entry.time && it.pkg == pkg }) {
                        if (r.taps > 0) it.copy(latencyMs = latency)
                        else if (r.ok) it.copy(result = Result.SKIPPED, reason = "seen on the phone before the Fitbit Air was reached")
                        else it.copy(result = Result.FAILED, reason = r.message)
                    }
                })
                pump()
            }
        }
    }

    // Test buzz and Try

    /** One Test buzz with the message pattern, reported step by step on the Test buzz screen. */
    fun testBuzz(taps: Int = 1) {
        if (testState.value?.result == null && testState.value != null) return
        val started = SystemClock.elapsedRealtime()
        testState.value = TestRun(started)
        queue.addLast(Job(BandCommand.Taps(taps, strength), buzzed = {
            testState.value = testState.value?.copy(buzzedMs = SystemClock.elapsedRealtime() - started)
        }) { r ->
            testState.value = testState.value?.copy(result = r)
            log.add(Entry(System.currentTimeMillis(), Kind.TEST, app.packageName, "Test buzz", null, null,
                if (r.taps > 0) Result.BUZZED else Result.FAILED, if (r.taps > 0) null else r.message, latencyMs = r.firstBuzzMs))
        })
        pump()
    }

    fun clearTest() { if (testState.value?.result != null) testState.value = null }

    private fun testLine(line: String) {
        val t = testState.value ?: return
        if (t.result != null) return
        val ms = SystemClock.elapsedRealtime() - t.startedAt
        testState.value = t.copy(lines = t.lines + String.format(Locale.ROOT, "%5.1f s  %s", ms / 1000.0, line))
    }

    // The band queue

    private fun pump() {
        if (running != null || band.busy) return
        val job = queue.pollFirst() ?: return
        running = job
        bandState.value = bandState.value.copy(busy = true)
        band.keepLinkOpen = settings.enabled
        job.tries++
        band.run(job.cmd, {
            if (job.what != null) {
                val after = SystemClock.elapsedRealtime() - job.alertAt
                note("First buzz ${seconds(after)} after the ${job.what} arrived" + if (job.tries > 1) ", on try ${job.tries}" else "")
            }
            job.buzzed()
        }) { r ->
            running = null
            val failed = !r.ok && r.taps == 0
            // A buzz that lost the Air to Google Health is tried again a little later rather than dropped, for up to
            // 30 s for a message, unless the message was seen on the phone meanwhile (Davor left the choice to the app
            // thread on 11 Nov: a late buzz beats a missed one, but not for something already read).
            val retry = failed && !job.seen && SystemClock.elapsedRealtime() + RETRY_MS < job.giveUpAt
            bandState.value = bandState.value.copy(
                busy = false,
                lastReachedAt = if (r.taps > 0) System.currentTimeMillis() else bandState.value.lastReachedAt,
                lastError = if (!failed) null else if (retry) bandState.value.lastError else r.message,
            )
            if (retry) {
                note("${r.message} Trying again in ${RETRY_MS / 1000} s")
                waiting += job
                main.postDelayed({
                    if (waiting.remove(job)) {
                        if (job.ringKey != null) queue.addFirst(job) else queue.addLast(job)
                        pump()
                    }
                }, RETRY_MS)
            } else if (failed && job.seen) {
                note("Not retrying: the message was seen on the phone")
                job.done(seen())
            } else {
                if (failed) note("Gave up: ${r.message}")
                if (failed && job.missTitle != null) missed(job.missTitle, r)
                if (r.taps > 0) clearProblem()
                job.done(r)
            }
            main.post { pump() }
        }
    }

    private fun cancelled() = BandResult(true, "Stopped before the Fitbit Air was reached.", null, 0, 0, 0, 0, 0, "new", emptyMap())

    private fun seen() = BandResult(true, "Seen on the phone before the Fitbit Air was reached.", null, 0, 0, 0, 0, 0, "new", emptyMap())

    /** The user dismissed message notification [key], so its buzz isn't wanted any more if it is still waiting. */
    fun onMessageDismissed(key: String) = dropSeen("Message dismissed") { it.alertKey == key }

    /** The phone was unlocked, so every message still waiting to buzz has been seen. */
    private fun onUnlocked() = dropSeen("Phone unlocked") { it.alertKey != null }

    private fun dropSeen(why: String, match: (Job) -> Boolean) {
        val dropped = (queue.filter(match) + waiting.filter(match)).onEach { it.seen = true }
        running?.takeIf(match)?.seen = true
        if (dropped.isEmpty()) return
        note("$why; dropped ${dropped.size} waiting buzz${if (dropped.size == 1) "" else "es"}")
        queue.removeAll(dropped.toSet())
        waiting.removeAll(dropped.toSet())
        dropped.forEach { it.done(seen()) }
    }

    // Problems while AirBuzz is closed (brief decision 41: "quiet, then loud"). One notification, updated in place:
    // silent on the Problems channel while Bluetooth is off, loud on the Missed alerts channel once an alert didn't
    // reach the Air. Never while AirBuzz is off or paused; cleared when the problem is fixed.

    /** Why an alert didn't reach the Air, as the notification says it, with the one fix when there is one. */
    private enum class Cause(val text: String, val fixLabel: String?) {
        BLUETOOTH_OFF("Bluetooth is off", "Turn on"),
        NEARBY("AirBuzz isn't allowed to use Nearby devices", "Allow"),
        NO_AIR("No Fitbit Air is paired with this phone", null),
        GOOGLE_HEALTH("Google Health kept your Fitbit Air busy", null),
        SILENT("Your Fitbit Air didn't answer", null),
        OUT_OF_REACH("Your Fitbit Air was out of reach", null),
    }

    private class ProblemNote(val since: Long, var cause: Cause, var missed: Int = 0, var title: String? = null)

    private var problem: ProblemNote? = null
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
        // The open link stays up while AirBuzz is on, paused included, since chosen callers still ring.
        if (!band.busy) band.keepLinkOpen = s.enabled
        if (s.enabled) band.warmUp()
    }

    private fun missed(title: String, r: BandResult) {
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
        p.missed++
        p.title = title
        showProblem()
    }

    private fun showProblem() {
        val p = problem ?: return
        bandState.value = bandState.value.copy(missed = p.missed, problemSince = p.since)
        val bluetooth = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        val openApp = Intent(app, MainActivity::class.java)
        if (p.missed == 0) {
            note("Notification: Bluetooth is off")
            Notifications.showProblem(app, loud = false, "Bluetooth is off", "Your Fitbit Air won't buzz until it's back on", "Turn on", bluetooth)
        } else {
            val title = if (p.missed == 1) p.title ?: "An alert didn't reach your Fitbit Air" else "${p.missed} alerts didn't reach your Fitbit Air"
            note("Notification: ${p.missed} missed (${p.cause.text})")
            Notifications.showProblem(app, loud = true, title, p.cause.text, p.cause.fixLabel,
                if (p.cause == Cause.BLUETOOTH_OFF) bluetooth else openApp)
        }
    }

    private fun clearProblem() {
        if (problem == null) return
        problem = null
        note("Problem cleared")
        Notifications.cancelProblem(app)
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
    private fun posted(moment: Moment, postedAt: Long) = ", posted ${seconds(moment.nowMs - postedAt)} before"

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    private fun outcome(o: Outcome) = when (o) {
        Outcome.Buzz -> "buzz"
        Outcome.Merge -> "within 15 s of the last buzz, merged"
        is Outcome.Skip -> "ignored (${o.reason})"
    }

    companion object {
        const val TAG = "AirBuzz"
        const val RETRY_MS = 5_000L
        /** A message buzz is worth up to half a minute late; after that it is logged as failed. */
        const val MESSAGE_RETRY_FOR_MS = 30_000L
        /** A call keeps trying for as long as AirBuzz would ring it, unless it ends first. */
        const val RING_RETRY_FOR_MS = 60_000L
    }
}
