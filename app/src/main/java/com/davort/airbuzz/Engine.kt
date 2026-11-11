package com.davort.airbuzz

import android.app.Application
import android.content.Context
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
import com.davort.airbuzz.data.Entry
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.data.Result
import com.davort.airbuzz.data.SettingsStore
import com.davort.airbuzz.rules.Caller
import com.davort.airbuzz.rules.Moment
import com.davort.airbuzz.rules.Outcome
import com.davort.airbuzz.rules.Rules
import com.davort.airbuzz.rules.Settings
import com.davort.airbuzz.service.BuzzService
import com.davort.airbuzz.service.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Locale

class AirBuzzApp : Application() {
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        engine = Engine(this)
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
    private val main = Handler(Looper.getMainLooper())
    private val band = AirBand(app) { line -> Log.i(TAG, line); testLine(line) }

    private val bandState = MutableStateFlow(BandState())
    val state: StateFlow<BandState> = bandState
    private val testState = MutableStateFlow<TestRun?>(null)
    val test: StateFlow<TestRun?> = testState

    private class Job(val cmd: BandCommand, val ringKey: String? = null, val buzzed: () -> Unit = {}, val done: (BandResult) -> Unit)

    private val queue = ArrayDeque<Job>()
    private var running: Job? = null
    private val lastBuzzByApp = HashMap<String, Long>()

    val settings: Settings get() = store.settings.value

    private val region: String by lazy {
        val tm = app.getSystemService(TelephonyManager::class.java)
        listOf(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country).firstOrNull { !it.isNullOrBlank() } ?: "SE"
    }

    private val strength: Int get() = if (settings.strong) AirHaptics.HIGH else AirHaptics.LOW

    fun setEnabled(on: Boolean) {
        store.update { it.copy(enabled = on) }
        if (on) BuzzService.start(app) else { BuzzService.stop(app); band.shutdown() }
    }

    // Calls

    /** An incoming call notification from [pkg], identified by its notification [key]. */
    fun onIncomingCall(key: String, pkg: String, appLabel: String, caller: Caller, moment: Moment) {
        if (running?.ringKey == key || queue.any { it.ringKey == key }) return
        val who = caller.name ?: caller.number ?: "Unknown caller"
        when (val o = Rules.decideCall(caller, settings, moment, region)) {
            is Outcome.Skip -> log.add(Entry(moment.nowMs, Kind.CALL, pkg, appLabel, who, caller.number, Result.SKIPPED, o.reason))
            else -> {
                val entry = Entry(moment.nowMs, Kind.CALL, pkg, appLabel, who, caller.number, Result.BUZZED)
                log.add(entry)
                bandState.value = bandState.value.copy(ringingWho = who)
                Notifications.showRinging(app, who)
                val start = SystemClock.elapsedRealtime()
                // A ring goes ahead of queued message taps.
                queue.addFirst(Job(BandCommand.Ring(strength), ringKey = key) { r ->
                    Notifications.cancelRinging(app)
                    bandState.value = bandState.value.copy(ringingWho = null)
                    log.updateLast({ it.time == entry.time && it.kind == Kind.CALL }) {
                        if (r.taps > 0) it.copy(rangMs = SystemClock.elapsedRealtime() - start, latencyMs = r.firstBuzzMs)
                        else if (r.ok) it.copy(result = Result.SKIPPED, reason = "answered before the Air was reached")
                        else it.copy(result = Result.FAILED, reason = r.message)
                    }
                })
                pump()
            }
        }
    }

    /** The call notification [key] went away or was answered. */
    fun onCallEnded(key: String) {
        if (running?.ringKey == key) band.stopRing()
        queue.removeAll { job -> (job.ringKey == key).also { if (it) job.done(cancelled()) } }
    }

    /** Silence from the "Ringing your Air" notification. */
    fun silence() {
        running?.takeIf { it.ringKey != null }?.let { band.stopRing() }
        queue.removeAll { job -> (job.ringKey != null).also { if (it) job.done(cancelled()) } }
    }

    // Messages

    fun onMessage(pkg: String, appLabel: String, who: String?, moment: Moment) {
        when (val o = Rules.decideMessage(pkg, settings, moment, lastBuzzByApp[pkg])) {
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
                queue.addLast(Job(BandCommand.Taps(settings.messageTaps, strength)) { r ->
                    log.updateLast({ it.time == entry.time && it.pkg == pkg }) {
                        if (r.taps > 0) it.copy(latencyMs = r.firstBuzzMs) else it.copy(result = Result.FAILED, reason = r.message)
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
        band.run(job.cmd, job.buzzed) { r ->
            running = null
            bandState.value = bandState.value.copy(
                busy = false,
                lastReachedAt = if (r.taps > 0) System.currentTimeMillis() else bandState.value.lastReachedAt,
                lastError = if (r.ok || r.taps > 0) null else r.message,
            )
            job.done(r)
            main.post { pump() }
        }
    }

    private fun cancelled() = BandResult(true, "Stopped before the Air was reached.", null, 0, 0, 0, 0, 0, "new", emptyMap())

    /** The phone's state now. [doNotDisturb] comes from the notification listener. */
    fun moment(doNotDisturb: Boolean, phoneInUse: Boolean): Moment {
        val c = Calendar.getInstance()
        return Moment(c.timeInMillis, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), doNotDisturb, phoneInUse)
    }

    companion object {
        const val TAG = "AirBuzz"
    }
}
