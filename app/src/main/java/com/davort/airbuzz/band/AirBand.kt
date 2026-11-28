package com.davort.airbuzz.band

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.fitbit.goldengate.bindings.GoldenGate
import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.data.IncomingResponse
import com.fitbit.goldengate.bindings.coap.data.Method
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder
import com.fitbit.goldengate.bindings.dtls.DtlsProtocolStatus.TlsProtocolState
import com.fitbit.goldengate.bindings.dtls.TlsKeyResolver
import com.fitbit.goldengate.bindings.io.RxSource
import com.fitbit.goldengate.bindings.io.TxSink
import com.fitbit.goldengate.bindings.node.BluetoothAddressNodeKey
import com.fitbit.goldengate.bindings.node.NodeKey
import com.fitbit.goldengate.bindings.stack.DtlsSocketNetifGattlink
import com.fitbit.goldengate.bindings.stack.Stack
import com.fitbit.goldengate.bindings.stack.StackEvent
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What the band should do in one secure session. */
sealed interface BandCommand {
    val strength: Int

    /** [count] taps about [GAP_MS] apart, for a message. */
    data class Taps(val count: Int, override val strength: Int) : BandCommand

    /** A tap every [intervalMs] until [AirBand.stopRing] or [maxMs], for a call. */
    data class Ring(override val strength: Int, val intervalMs: Long = 1_000, val maxMs: Long = 60_000) : BandCommand

    /** Only reads the Air's battery, with no buzz: when AirBuzz is opened and the last reading is old. */
    data object Battery : BandCommand { override val strength = 0 }

    companion object {
        const val GAP_MS = 600L
    }
}

/** What one command did. Times are milliseconds after [AirBand.run] was called. */
data class BandResult(
    val ok: Boolean,
    val message: String,
    /** When the Air acknowledged the first tap; the motor fires as it applies the write. */
    val firstBuzzMs: Long?,
    val totalMs: Long,
    val taps: Int,
    val handshakes: Int,
    val wrongKeys: Int,
    val lostRaces: Int,
    /** "new" connection, the "open" link, or "open→new" when the open link was silent and the command reconnected. */
    val link: String,
    val phases: Map<String, Long>,
    /** The Air sent nothing back to AirBuzz on a connection of its own, so Google Health wasn't the visible cause. */
    val bandSilent: Boolean = false,
)

/**
 * Drives the Air's vibration motor over Bluetooth LE and Golden Gate (Fitbit's open-source CoAP-over-DTLS stack).
 *
 * The band is shared with Google Health, so each command:
 * 1. connects to the tracker channel, or reuses the listen-only open link (see [keepLinkOpen]);
 * 2. waits until Google Health's traffic on the channel goes quiet ([QuietWait]);
 * 3. opens its own DTLS session with the band's public BOOTSTRAP key, staying on the same stack when the band
 *    first offers a key we don't have (-10601) and retrying 1.5 s after a lost race with Google Health (-1);
 * 4. writes the band's haptic strength to make it buzz ([AirHaptics]);
 * 5. hands the channel back by restarting the stack with a resolver that refuses every key, so the band resets
 *    its Gattlink session and Google Health can handshake again.
 *
 * Steps 2, 3 and 5 and the open link follow the MIT-licensed fitbit-air library
 * (github.com/ThoreKoritzius/fitbit-air); the timings were measured on a Fitbit Air with AirBuzz's test app.
 * Everything runs on the main thread; GATT and Golden Gate callbacks are posted there.
 */
@SuppressLint("MissingPermission")
class AirBand(private val context: Context, private val log: (String) -> Unit) {

    private companion object {
        val TRACKER: UUID = UUID.fromString("abbaff00-e56a-484c-b832-8b17cf6cbfe8")
        val TX: UUID = UUID.fromString("abbaff01-e56a-484c-b832-8b17cf6cbfe8")
        val RX: UUID = UUID.fromString("abbaff02-e56a-484c-b832-8b17cf6cbfe8")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CONNECT_TIMEOUT_MS = 45_000L
        const val HANDSHAKE_TIMEOUT_MS = 6_000L
        const val LOST_RACE_RETRY_MS = 1_500L
        const val MAX_HANDSHAKE_ATTEMPTS = 3
        const val TLS_UNKNOWN_IDENTITY = -10601
        const val TLS_LOST_RACE = -1
        const val HANDOFF_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_S = 3L
        /** The open link is reused only after it has been subscribed this long. */
        const val SETTLE_MS = 3_000L
        /** With nothing received this long after the handshake starts, the open link is treated as dead. */
        const val OPEN_LINK_SILENCE_MS = 2_000L
        /**
         * With nothing received this long after a handshake starts on a new connection, the Air isn't answering, so
         * the attempt ends at once instead of after three 6 s handshakes. It normally answers within about 2 s; on
         * 12 Nov it answered nothing for 2 minutes, and each try took 20 s.
         */
        const val BAND_SILENCE_MS = 3_000L
        const val STRENGTH_KEY = "band_strength"
        /** After a drop or a failed attempt the open link is asked for again after this, doubling up to [WARM_RETRY_MAX_MS]. */
        const val WARM_RETRY_MS = 5_000L
        const val WARM_RETRY_MAX_MS = 30 * 60_000L
        /**
         * How often the step log records the phone's battery, to measure what the open link costs. The Air's can't be
         * read: it offers no battery level among its 8 services (12 Nov).
         */
        const val BATTERY_EVERY_MS = 60 * 60_000L
        /**
         * An open link that hears nothing this long is subscribed again. Google Health syncs about every 15 minutes, and
         * on 13 Nov the open link heard nothing for over an hour after three drops while Google Health kept syncing;
         * the next subscription, at a call, brought its packets back.
         */
        const val DEAF_MS = 20 * 60_000L
        const val DEAF_CHECK_MS = 60_000L
        /** [onHeard] is called at most this often while the Air keeps sending, so Home doesn't redraw for every packet. */
        const val HEARD_EVERY_MS = 60_000L
        /** While Google Health syncs, [SyncWatch] is checked this often, because a sync ends with no packet to say so. */
        const val SYNC_CHECK_MS = 2_000L
        /**
         * A tracker write Android declines because its Bluetooth queue is busy is tried again this often, this many
         * times; before, the packet waited for the next one Golden Gate sent (fitbit-air's idea, our own code).
         */
        const val WRITE_RETRY_MS = 25L
        const val WRITE_RETRIES = 20
        /** How long the link-status read may take before what it has is logged. */
        const val STATUS_TIMEOUT_MS = 1_500L
        /** BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, and what AirBuzz records when Android gives no status. */
        const val WRITE_BUSY = 201
        const val WRITE_UNKNOWN = -1
    }

    /** Refuses every PSK, so the band's next handshake fails and it resets its Gattlink session. */
    private object RefusingKeyResolver : TlsKeyResolver() {
        override fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val disposables = CompositeDisposable()
    private val prefs = context.getSharedPreferences("band", Context.MODE_PRIVATE)

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var linkUp = false

    /**
     * Keep a listen-only Bluetooth client connected and subscribed between commands. The secure session is still
     * closed and handed back to Google Health after every command. On Davor's Air this took a message buzz from
     * a 3.1 s median to 0.5 s, and Google Health kept syncing (research notes, test app 0.4).
     */
    var keepLinkOpen = true
        set(value) {
            field = value
            if (!value) main.removeCallbacks(warmRetry)
            if (!value && !busy && gatt != null) closeTransport()
        }

    /** The current connection was opened by [warmUp] and is not subscribed yet. */
    private var warming = false
    private var warmDelay = WARM_RETRY_MS
    private val warmRetry = Runnable { warmUp() }
    private var batteryAt = 0L
    private var linkReadyAt = 0L
    private val recentRx = ArrayDeque<Pair<Long, Int>>()
    private var usedOpenLink = false
    private var fellBack = false
    private var sessionRx = 0
    /** Anything arrived during this command's secure sessions. */
    private var heardBand = false
    private var bandSilent = false
    /** When a command last ended because the Air didn't answer; 0 once it is heard again. */
    private var silentSince = 0L
    private var silenceCheck: Runnable? = null
    private val txQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    /** Tracker packets Android accepted, and writes it declined with their statuses, in this command, for its steps line. */
    private var txWritten = 0
    private val txDeclined = ArrayList<Int>()
    /** Declined writes in a row; the same packet is tried again every [WRITE_RETRY_MS], [WRITE_RETRIES] times. */
    private var writeRetries = 0
    private val writeRetry = Runnable { writeNext() }
    /** Set while the Air's services are found again after it said they changed. */
    private var rediscovering = false
    /** The open link has logged its link status once while the Air was answering; reset when the link closes. */
    private var healthyStatusLogged = false
    /** A link-status read in progress ([readLinkStatus]): why, what to do after, and the first half's result. */
    private var statusReason: String? = null
    private var statusThen: (() -> Unit)? = null
    private var statusConfig: String? = null
    private val statusTimeout = Runnable { linkStatusDone("no answer in ${STATUS_TIMEOUT_MS} ms") }
    private var txSink: TxSink? = null
    private var rxSource: RxSource? = null
    private var stack: Stack? = null
    private var endpoint: CoapEndpoint? = null

    private var quietWait: QuietWait? = null
    private var quietPackets = 0
    private val traffic = HealthTraffic()
    private var handshakeAttempts = 0
    private var sessionStarted = false
    private var handshakeDeadline: Runnable? = null
    private var linkBoosted = false

    private var command: BandCommand? = null
    private var onBuzz: (() -> Unit)? = null
    private var onDone: ((BandResult) -> Unit)? = null
    private var startedAt = 0L
    private var firstBuzzAt: Long? = null
    private var taps = 0
    private var wrongKeys = 0
    private var lostRaces = 0
    private var stopRequested = false
    private var strength = 0
    private val phases = LinkedHashMap<String, Long>()
    private val connectTimeout = Runnable { fail("Couldn’t reach your Fitbit Air within ${CONNECT_TIMEOUT_MS / 1000} s.") }

    val busy: Boolean get() = onDone != null

    /** How the running command reached the Air so far: "new", "open" or "open→new". */
    val link: String get() = if (fellBack) "open→new" else if (usedOpenLink) "open" else "new"

    /** The running command's phases so far, in ms since it started, for Test buzz to show live. */
    val phasesSoFar: Map<String, Long> get() = LinkedHashMap(phases)

    /** Called on the main thread each time the running command reaches a phase. */
    var onPhase: () -> Unit = {}

    /** Called on the main thread when Google Health starts (true) or stops (false) syncing, as [SyncWatch] judges it. */
    var onHealthSync: (Boolean) -> Unit = {}

    /** Called on the main thread when the open link comes up (true), or is lost or still being asked for (false). */
    var onLink: (Boolean) -> Unit = {}

    /** Called on the main thread when the Air sends again after answering nothing. */
    var onHeardAgain: () -> Unit = {}

    /** Called on the main thread when the Air sends anything, at most once per [HEARD_EVERY_MS], and at once after [onDeaf]. */
    var onHeard: () -> Unit = {}

    /**
     * Called on the main thread once the open link has heard nothing from the Air for [DEAF_MS] since its last packet or
     * since the link came up, whichever is later. Subscribing again doesn't restart that count. Called again only after
     * the Air has been heard or the link has closed.
     */
    var onDeaf: () -> Unit = {}

    private val syncWatch = SyncWatch()
    private val syncCheck = object : Runnable {
        override fun run() {
            if (syncWatch.update(SystemClock.elapsedRealtime())) {
                syncEnded()
                // Once per link, after a sync it heard in full: the link status of a healthy band, to compare with the
                // one logged in a silent spell (the review of 28 Nov saw MTU 23 and "slow" there, normal or not unknown).
                val g = gatt
                if (!healthyStatusLogged && !busy && linkUp && !warming && g != null) {
                    healthyStatusLogged = true
                    readLinkStatus(g, "healthy, after a Google Health sync") {}
                }
            }
            else if (syncWatch.syncing) main.postDelayed(this, SYNC_CHECK_MS)
        }
    }
    private var lastRxAt = 0L
    /** Elapsed realtime of the Air's last packet; unlike [lastRxAt], subscribing again doesn't reset it. */
    private var heardAt = 0L
    private var heardReportedAt = 0L
    private var deafReported = false
    private val deafCheck = object : Runnable {
        override fun run() {
            val g = gatt
            if (!linkUp || warming || g == null) return
            val now = SystemClock.elapsedRealtime()
            if (!deafReported && now - maxOf(heardAt, linkReadyAt) >= DEAF_MS) {
                deafReported = true
                onDeaf()
            }
            val quietFor = now - maxOf(lastRxAt, linkReadyAt)
            if (!busy && quietFor >= DEAF_MS) {
                // The signal strength says whether the radio link is still alive while nothing arrives.
                g.readRemoteRssi()
                if (resubscribedAt != 0L && lastRxAt == resubscribedAt) {
                    // On 13 Nov subscribing again didn't end two deaf spells; a connection of AirBuzz's own is the
                    // next thing to try. Only AirBuzz's client closes, so Google Health's link to the Air stays up.
                    log("Open link: still heard nothing ${(now - resubscribedAt) / 60_000} min after subscribing again; connecting afresh")
                    readLinkStatus(g, "still deaf") {
                        if (!busy) {
                            closeTransport()
                            warmDelay = WARM_RETRY_MS
                            warmUp()
                        }
                    }
                    return
                }
                log("Open link: heard nothing for ${quietFor / 60_000} min; subscribing again")
                lastRxAt = now
                resubscribedAt = now
                // Read first: Android runs one Bluetooth request at a time, and the subscription is one.
                readLinkStatus(g, "deaf") { if (!busy && !subscribe(g)) linkFailed("Could not subscribe to the tracker channel again.") }
            }
            main.postDelayed(this, DEAF_CHECK_MS)
        }
    }
    /** When a deaf open link was last subscribed again; 0 when it wasn't, or after the link closed. */
    private var resubscribedAt = 0L
    /** Google Health was syncing when the running command started, for its steps line. */
    private var duringSync = false

    init {
        // Start Golden Gate now, while AirBuzz has few files open. Its loop watches one socket with select(), which
        // aborts the process when that socket's number is 1024 or more. Started at the first buzz instead, the socket
        // can get a high number: on 14 Nov, Test buzz 24 s after an update crashed with fd 1495, because opening
        // AirBuzz reads every launcher app's name and Android holds their APKs open until the next garbage collection.
        // A failure here is reported by the first buzz, which calls init again.
        runCatching { GoldenGate.init() }
    }

    /** Whether a buzz should also read the battery before it hands the channel back; asked once per buzz. */
    var wantBattery: () -> Boolean = { false }
    /** Called on the main thread with each battery reading. */
    var onBattery: (AirBattery.Reading) -> Unit = {}

    fun findBondedAir(): BluetoothDevice? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return try {
            adapter.bondedDevices?.firstOrNull { it.name?.contains("Fitbit Air", ignoreCase = true) == true }
        } catch (_: SecurityException) {
            null
        }
    }

    /** Runs [cmd]. [buzzed] fires when the Air acknowledges the first tap; [done] fires once, after the hand-off. */
    fun run(cmd: BandCommand, buzzed: () -> Unit = {}, done: (BandResult) -> Unit) {
        check(!busy) { "The band is busy." }
        command = cmd
        onBuzz = buzzed
        onDone = done
        startedAt = SystemClock.elapsedRealtime()
        firstBuzzAt = null
        taps = 0
        wrongKeys = 0
        lostRaces = 0
        stopRequested = false
        usedOpenLink = false
        fellBack = false
        heardBand = false
        bandSilent = false
        phases.clear()
        handshakeAttempts = 0
        sessionStarted = false
        quietPackets = 0
        txWritten = 0
        txDeclined.clear()
        duringSync = syncWatch.syncing
        val device = findBondedAir() ?: return fail("No paired Fitbit Air. Pair it in the Google Health app first.")
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        val open = gatt
        if (keepLinkOpen && open != null && linkUp && !warming && !rediscovering && SystemClock.elapsedRealtime() - linkReadyAt >= SETTLE_MS) {
            usedOpenLink = true
            log("Using the open link")
            // On the open link the fast-link request goes out at once (test app 0.4: no lost races from it).
            linkBoosted = open.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            if (statusReason != null) {
                // A link-status read holds Android's one Bluetooth request; subscribe once it is done (at most 1.5 s).
                val token = startedAt
                val after = statusThen
                statusThen = {
                    after?.invoke()
                    if (busy && startedAt == token && gatt === open && !subscribe(open)) reconnectFresh(device, "could not refresh the subscription")
                }
            } else if (!subscribe(open)) {
                // Most likely another Bluetooth request still in flight; give it a moment before connecting afresh.
                val token = startedAt
                main.postDelayed({
                    if (busy && startedAt == token && gatt === open && !subscribe(open)) reconnectFresh(device, "could not refresh the subscription")
                }, 200)
            }
            return
        }
        if (open != null) closeTransport()
        connectFresh(device)
    }

    /** Ends a ring after its current tap, or abandons a command that has not reached the Air yet. */
    fun stopRing() {
        if (!busy) return
        stopRequested = true
        if (!sessionStarted) succeed("Stopped before the Fitbit Air was reached.")
    }

    /**
     * Opens the listen-only link ahead of the first alert, so it doesn't pay for a new connection and the wrong key
     * (8.2 s for a Telegram message on Davor's phone on 12 Nov). Android's autoConnect leaves the waiting to the
     * Bluetooth chip, which costs little while the Air is out of reach. Does nothing while a link exists or AirBuzz is off.
     */
    fun warmUp() {
        main.removeCallbacks(warmRetry)
        if (!keepLinkOpen || busy || gatt != null) return
        if (context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) return
        val device = findBondedAir() ?: return
        log("Opening the link to ${device.name}")
        onLink(false)
        val g = try { device.connectGatt(context, true, callback, TRANSPORT_LE) } catch (_: SecurityException) { null }
        if (g == null) return scheduleWarm()
        warming = true
        gatt = g
    }

    private fun scheduleWarm() {
        if (!keepLinkOpen) return
        main.removeCallbacks(warmRetry)
        main.postDelayed(warmRetry, warmDelay)
        warmDelay = minOf(warmDelay * 2, WARM_RETRY_MAX_MS)
    }

    /** The open link failed between commands; drop it and ask for it again later. */
    private fun linkFailed(message: String) {
        if (busy) return fail(message)
        log("Open link: $message")
        onLink(false)
        closeTransport()
        scheduleWarm()
    }

    fun shutdown() {
        if (!busy) closeTransport()
    }

    private fun connectFresh(device: BluetoothDevice) {
        // Whether any app's link to the Air is up, such as Google Health's, as Android sees it before ours opens.
        log("Connecting to ${device.name} (Android: ${androidLinkState(device)})")
        val g = device.connectGatt(context, false, callback, TRANSPORT_LE)
        if (g == null) fail("Android did not create a Bluetooth connection.") else gatt = g
    }

    private fun androidLinkState(device: BluetoothDevice): String = try {
        when (context.getSystemService(BluetoothManager::class.java)?.getConnectionState(device, BluetoothProfile.GATT)) {
            BluetoothProfile.STATE_CONNECTED -> "connected"
            BluetoothProfile.STATE_CONNECTING -> "connecting"
            BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
            BluetoothProfile.STATE_DISCONNECTED -> "not connected"
            else -> "unknown"
        }
    } catch (_: SecurityException) {
        "unknown"
    }

    /**
     * Reads the Air's link configuration and status ([AirLinkStatus]) and logs them in one line, then runs [then].
     * Tracker writes wait meanwhile, because Android runs one Bluetooth request at a time. Read only for the step log.
     */
    private fun readLinkStatus(g: BluetoothGatt, reason: String, then: () -> Unit) {
        if (statusReason != null) return then()
        val c = g.getService(AirLinkStatus.SERVICE)?.getCharacteristic(AirLinkStatus.CONFIGURATION)
        if (c == null) {
            log("Link status ($reason): the Fitbit Air offers no link-status service")
            return then()
        }
        statusReason = reason
        statusThen = then
        statusConfig = null
        main.postDelayed(statusTimeout, STATUS_TIMEOUT_MS)
        if (!g.readCharacteristic(c)) linkStatusDone("not read: Android declined the read")
    }

    private fun onLinkStatusRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (statusReason == null) return
        val text = when {
            status != BluetoothGatt.GATT_SUCCESS -> "unreadable (status $status)"
            c.uuid == AirLinkStatus.CONFIGURATION -> AirLinkStatus.configuration(value)
            else -> AirLinkStatus.status(value)
        }
        if (c.uuid != AirLinkStatus.CONFIGURATION) return linkStatusDone(text)
        statusConfig = text
        val next = g.getService(AirLinkStatus.SERVICE)?.getCharacteristic(AirLinkStatus.STATUS)
        if (next == null || !g.readCharacteristic(next)) linkStatusDone("not read")
    }

    private fun linkStatusDone(statusText: String) {
        val reason = statusReason ?: return
        val then = statusThen
        main.removeCallbacks(statusTimeout)
        log("Link status ($reason): Android ${gatt?.device?.let(::androidLinkState) ?: "unknown"}; " +
            "configuration ${statusConfig ?: "not read"}; status $statusText")
        statusReason = null
        statusThen = null
        statusConfig = null
        writeNext()
        then?.invoke()
    }

    /** The open link stopped working before any command reached the Air, so the command continues on a new connection. */
    private fun reconnectFresh(device: BluetoothDevice, reason: String) {
        log("Open link unusable ($reason); connecting afresh")
        mark("fell back")
        fellBack = true
        usedOpenLink = false
        closeTransport()
        handshakeAttempts = 0
        sessionStarted = false
        connectFresh(device)
    }

    private fun subscribe(g: BluetoothGatt): Boolean {
        val rx = rxCharacteristic ?: return false
        val cccd = rx.getDescriptor(CCCD) ?: return false
        return g.setCharacteristicNotification(rx, true) && if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    // GATT

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onMain(g) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) linkUp = true
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) linkUp = false
            if (!busy) {
                // Between commands: open a warm link's services, or ask again for a link that dropped.
                if (!linkUp) linkFailed(if (warming) "couldn’t connect (status $status)" else "dropped (status $status)")
                else if (warming) g.discoverServices()
                return@onMain
            }
            // A dead link cannot carry the hand-off, so skip it by clearing sessionStarted.
            when {
                status != BluetoothGatt.GATT_SUCCESS -> { sessionStarted = false; fail("Couldn’t connect to your Fitbit Air (status $status).") }
                newState == BluetoothProfile.STATE_CONNECTED -> { mark("connected"); g.discoverServices() }
                newState == BluetoothProfile.STATE_DISCONNECTED -> { sessionStarted = false; fail("Your Fitbit Air disconnected.") }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onMain(g) {
            if (status != BluetoothGatt.GATT_SUCCESS) return@onMain linkFailed("Service discovery failed (status $status).")
            val service = g.getService(TRACKER)
            val tx = service?.getCharacteristic(TX)
            val rx = service?.getCharacteristic(RX)
            if (tx == null || rx == null || rx.getDescriptor(CCCD) == null) return@onMain linkFailed("Your Fitbit Air’s tracker channel is missing.")
            txCharacteristic = tx
            rxCharacteristic = rx
            if (!subscribe(g)) linkFailed("Could not subscribe to the tracker channel.")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = onMain(g) {
            if (d.uuid != CCCD) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) return@onMain linkFailed("Tracker subscription failed (status $status).")
            warmDelay = WARM_RETRY_MS
            main.removeCallbacks(deafCheck)
            main.postDelayed(deafCheck, DEAF_CHECK_MS)
            if (!busy) {
                if (warming) {
                    warming = false
                    linkReadyAt = SystemClock.elapsedRealtime()
                    log("The link is open")
                    onLink(true)
                    logBattery(linkReadyAt)
                } else if (rediscovering) {
                    rediscovering = false
                    log("Open link: subscribed again after the services changed")
                } else if (resubscribedAt != 0L) {
                    log("Open link: subscribed again")
                }
                return@onMain
            }
            mark("subscribed")
            if (!usedOpenLink) linkReadyAt = SystemClock.elapsedRealtime()
            waitForQuietChannel()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = onMain(g) {
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) fail("Tracker write failed (status $status).") else writeNext()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            onMain(g) { onLinkStatusRead(g, c, value, status) }

        @Deprecated("Used below API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = c.value?.copyOf() ?: ByteArray(0)
            onMain(g) { onLinkStatusRead(g, c, value, status) }
        }

        /** The Air says its services changed (Android 12 and later), so the handles AirBuzz holds may be stale. */
        override fun onServiceChanged(g: BluetoothGatt) = onMain(g) {
            log("The Fitbit Air says its services changed; finding them again")
            if (busy) {
                // The session's handles may be gone, so skip the hand-off as on a dead link.
                sessionStarted = false
                fail("Your Fitbit Air changed its Bluetooth services. Try again in a moment.")
            }
            if (gatt !== g) return@onMain
            rediscovering = true
            if (!g.discoverServices()) linkFailed("Could not find the Fitbit Air’s services again.")
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) = onMain(g) {
            log(if (status == BluetoothGatt.GATT_SUCCESS) "Open link: signal $rssi dBm" else "Open link: signal unreadable (status $status)")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onMain(g) { if (c.uuid == RX) received(value) }

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = c.value?.copyOf() ?: ByteArray(0)
            onMain(g) { if (c.uuid == RX) received(value) }
        }
    }

    /** Runs [block] on the main thread, only while [g] is still the current connection. */
    private fun onMain(g: BluetoothGatt, block: () -> Unit) {
        main.post { if (g == gatt) block() }
    }

    private fun received(packet: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        recentRx.addLast(now to packet.size)
        while (recentRx.size > 64 || (recentRx.isNotEmpty() && now - recentRx.first().first > 2_000)) recentRx.removeFirst()
        lastRxAt = now
        heardAt = now
        if (deafReported || now - heardReportedAt >= HEARD_EVERY_MS) {
            deafReported = false
            heardReportedAt = now
            onHeard()
        }
        if (rxSource != null) { sessionRx++; heardBand = true } else {
            traffic.record(now, packet.size)?.let(log)
            if (syncWatch.record(now, packet.size)) {
                // Logged with when its traffic began, so a silent spell's end can be lined up with it (15 Nov).
                log(String.format(Locale.ROOT, "Google Health sync started, its traffic %.1f s before", (now - syncWatch.startedAt) / 1000.0))
                onHealthSync(true)
                main.removeCallbacks(syncCheck)
                main.postDelayed(syncCheck, SYNC_CHECK_MS)
            }
        }
        if (silentSince != 0L) {
            log("The Fitbit Air is sending again, ${(now - silentSince) / 1000} s after it stopped answering")
            silentSince = 0L
            onHeardAgain()
        }
        // Google Health's packets wake the phone now and then even in deep sleep, which keeps the battery line hourly.
        logBattery(now)
        quietWait?.record(now, packet.size)
        rxSource?.receiveData(packet)?.subscribe({}, { fail("Receive failed: ${it.message}") })?.let(disposables::add)
    }

    /** Logs the phone's battery once per [BATTERY_EVERY_MS] while the link is open. */
    private fun logBattery(now: Long) {
        if (now - batteryAt < BATTERY_EVERY_MS && batteryAt != 0L) return
        batteryAt = now
        val bm = context.getSystemService(BatteryManager::class.java) ?: return
        // Android's own background setting, to learn whether it follows the phone maker's battery screen (on ColorOS,
        // Battery usage: Allow background activity, Smart mode, or Restrict) so setup could check it instead of asking.
        val restricted = Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        val unrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        val background = if (restricted) "restricted" else if (unrestricted) "unrestricted" else "optimised"
        log("Battery: phone ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%${if (bm.isCharging) " charging" else ""}; " +
            "background $background; ${openFiles()} files open")
    }

    /** How many files and sockets AirBuzz has open, for the step log (see the init block above). */
    private fun openFiles(): Int = File("/proc/self/fd").list()?.size ?: -1

    private fun enqueue(packet: ByteArray) {
        txQueue.addLast(packet)
        writeNext()
    }

    private fun writeNext() {
        val g = gatt ?: return
        val c = txCharacteristic ?: return
        if (writeInFlight || statusReason != null) return
        main.removeCallbacks(writeRetry)
        val packet = txQueue.pollFirst() ?: return
        writeInFlight = true
        val status = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            c.value = packet
            @Suppress("DEPRECATION")
            if (g.writeCharacteristic(c)) BluetoothGatt.GATT_SUCCESS else WRITE_UNKNOWN
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            txWritten++
            writeRetries = 0
            return
        }
        writeInFlight = false
        txQueue.addFirst(packet)
        txDeclined.add(status)
        // Busy (201) or unknown: Android's Bluetooth queue is full for a moment, so the same packet goes again soon.
        if ((status == WRITE_BUSY || status == WRITE_UNKNOWN) && writeRetries < WRITE_RETRIES) {
            writeRetries++
            main.postDelayed(writeRetry, WRITE_RETRY_MS)
            return
        }
        val retries = writeRetries
        writeRetries = 0
        log("Tracker write declined (status $status${if (retries > 0) " after $retries retries" else ""})")
        // Still busy after the retries: the packet waits for the next write, as before. Any other status won't change.
        if (status != WRITE_BUSY && status != WRITE_UNKNOWN) {
            txQueue.clear()
            if (busy) fail("Android declined a Bluetooth write (status $status).")
        }
    }

    // Quiet wait

    private fun waitForQuietChannel() {
        val now0 = SystemClock.elapsedRealtime()
        // On the open link the traffic history is already known, so skip the 700 ms observation.
        val wait = if (usedOpenLink) QuietWait(now0 - 700).also { w -> recentRx.forEach { w.record(it.first, it.second) } }
            else QuietWait(now0)
        quietWait = wait
        val tick = object : Runnable {
            override fun run() {
                if (quietWait !== wait) return
                val now = SystemClock.elapsedRealtime()
                if (wait.shouldWait(now)) {
                    main.postDelayed(this, 100)
                } else {
                    quietWait = null
                    quietPackets = wait.packets
                    mark("quiet")
                    startSession()
                }
            }
        }
        main.post(tick)
    }

    // Golden Gate session

    private fun startSession() {
        val g = gatt ?: return
        try {
            GoldenGate.init()
            val tx = TxSink().also { txSink = it }
            val rx = RxSource().also { rxSource = it }
            disposables.add(tx.dataObservable.subscribe({ p -> main.post { if (txSink === tx) enqueue(p) } }, {}))
            val s = Stack(BluetoothAddressNodeKey(g.device.address), tx.thisPointer, rx.thisPointer,
                DtlsSocketNetifGattlink(), false)
            stack = s
            val ep = CoapEndpoint().also { endpoint = it }
            ep.attach(s)
            disposables.add(s.dtlsEventObservable.subscribe({ status ->
                main.post {
                    if (endpoint !== ep) return@post
                    when {
                        status.state == TlsProtocolState.TLS_STATE_SESSION && !sessionStarted -> onSession(ep)
                        status.state == TlsProtocolState.TLS_STATE_ERROR && status.lastError == TLS_UNKNOWN_IDENTITY -> {
                            // Every fresh stack is offered an unknown key first. Keep this stack up: the band then
                            // resets its Gattlink session and offers BOOTSTRAP (test app 0.3 failed by restarting).
                            wrongKeys++
                            mark("wrong key $wrongKeys")
                        }
                        status.state == TlsProtocolState.TLS_STATE_ERROR && status.lastError == TLS_LOST_RACE -> {
                            lostRaces++
                            retryHandshake("lost a handshake race with Google Health", LOST_RACE_RETRY_MS)
                        }
                    }
                }
            }, { main.post { fail("Secure transport failed: ${it.message}") } }))
            handshakeAttempts++
            armHandshakeDeadline(HANDSHAKE_TIMEOUT_MS)
            sessionRx = 0
            silenceCheck?.let(main::removeCallbacks)
            val onOpenLink = usedOpenLink && !fellBack
            silenceCheck = Runnable {
                silenceCheck = null
                if (endpoint !== ep || sessionStarted || sessionRx != 0) return@Runnable
                // The band answers a new Gattlink session within about a second. Silence on the open link may mean
                // Android stopped routing notifications to this client, so try a connection of its own; silence there,
                // before the Air has said anything in this command, means it isn't answering at all.
                if (onOpenLink) reconnectFresh(g.device, "nothing received")
                else if (!heardBand) {
                    bandSilent = true
                    val message = "Your Fitbit Air didn’t answer. Try again in a moment."
                    // Once per silent spell, read whether the radio link itself still works before the link closes.
                    if (silentSince == 0L) readLinkStatus(g, "not answering") { fail(message) } else fail(message)
                }
            }.also { main.postDelayed(it, if (onOpenLink) OPEN_LINK_SILENCE_MS else BAND_SILENCE_MS) }
            log("Starting the secure session (attempt $handshakeAttempts)")
            s.start()
        } catch (e: UnsatisfiedLinkError) {
            fail("Golden Gate native libraries did not load: ${e.message}")
        } catch (t: Throwable) {
            fail("Golden Gate failed to start: ${t.message ?: t}")
        }
    }

    private fun armHandshakeDeadline(delayMs: Long) {
        handshakeDeadline?.let(main::removeCallbacks)
        val r = Runnable {
            handshakeDeadline = null
            if (sessionStarted) return@Runnable
            if (handshakeAttempts >= MAX_HANDSHAKE_ATTEMPTS) {
                fail("Google Health is using your Fitbit Air. Try again in a moment.")
            } else {
                disposeSession()
                startSession()
            }
        }
        handshakeDeadline = r
        main.postDelayed(r, delayMs)
    }

    /** Drops the failed handshake and starts a fresh stack after [delayMs]. */
    private fun retryHandshake(reason: String, delayMs: Long) {
        mark("handshake $handshakeAttempts failed")
        if (handshakeAttempts >= MAX_HANDSHAKE_ATTEMPTS) return fail("Google Health is using your Fitbit Air ($reason).")
        log("Handshake $handshakeAttempts failed: $reason. Retrying in $delayMs ms")
        disposeSession()
        handshakeDeadline?.let(main::removeCallbacks)
        val r = Runnable { handshakeDeadline = null; if (!sessionStarted && busy) startSession() }
        handshakeDeadline = r
        main.postDelayed(r, delayMs)
    }

    private fun onSession(ep: CoapEndpoint) {
        sessionStarted = true
        main.removeCallbacks(connectTimeout)
        mark("session")
        handshakeDeadline?.let(main::removeCallbacks)
        handshakeDeadline = null
        // On a new connection the fast-link request waits until our own session exists, so it cannot help
        // Google Health win the handshake race (fitbit-air's finding).
        if (!linkBoosted) linkBoosted = gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) == true
        if (stopRequested) return succeed("Stopped before the first buzz.")
        if (command is BandCommand.Battery) return readBattery(ep) { ok -> if (ok) succeed("Read the battery.") else fail("Couldn’t read the battery.") }
        val cached = prefs.getInt(STRENGTH_KEY, 0).takeIf { it == AirHaptics.LOW || it == AirHaptics.HIGH }
        val current: Single<Int> = if (cached != null) Single.just(cached) else readStrength(ep)
        disposables.add(current.subscribe(
            { s -> main.post { strength = s; nextTap(ep, 0) } },
            { e -> main.post { fail("Couldn’t read the strength: ${e.message}") } },
        ))
    }

    /** Sends tap [index] of the command, then schedules the next one or finishes. */
    private fun nextTap(ep: CoapEndpoint, index: Int) {
        if (endpoint !== ep || !busy) return
        val cmd = command ?: return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val more = when (cmd) {
            is BandCommand.Taps -> index < cmd.count
            is BandCommand.Ring -> !stopRequested && elapsed < cmd.maxMs
            BandCommand.Battery -> false
        }
        if (!more) return verifyAndFinish(ep)
        val tapStart = SystemClock.elapsedRealtime()
        val body = AirHaptics.tapBody(strength, cmd.strength)
        disposables.add(post(ep, body).subscribe({
            main.post {
                strength = cmd.strength
                taps++
                if (firstBuzzAt == null) {
                    firstBuzzAt = SystemClock.elapsedRealtime()
                    mark("buzzed")
                    onBuzz?.invoke()
                }
                val gap = when (cmd) {
                    is BandCommand.Taps -> BandCommand.GAP_MS
                    is BandCommand.Ring -> (cmd.intervalMs - (SystemClock.elapsedRealtime() - tapStart)).coerceAtLeast(0)
                    BandCommand.Battery -> 0L
                }
                main.postDelayed({ nextTap(ep, index + 1) }, if (cmd is BandCommand.Taps && index + 1 >= cmd.count) 0 else gap)
            }
        }, { e -> main.post { fail("The buzz failed: ${e.message}") } }))
    }

    /** Reads the strength back so a mistake can't leave the band (and its alarm) at the wrong strength. */
    private fun verifyAndFinish(ep: CoapEndpoint) {
        val expected = strength
        disposables.add(readStrength(ep).flatMap { after ->
            if (after == expected) Single.just(expected)
            else post(ep, AirHaptics.entry(expected)).flatMap {
                Single.error<Int>(IllegalStateException("Strength read back as $after; restored $expected."))
            }
        }.subscribe(
            { s ->
                main.post {
                    prefs.edit().putInt(STRENGTH_KEY, s).apply()
                    val buzzed = "Buzzed $taps time${if (taps == 1) "" else "s"}."
                    if (wantBattery()) readBattery(ep) { succeed(buzzed) } else succeed(buzzed)
                }
            },
            { e -> main.post { fail(e.message ?: "Strength check failed.") } },
        ))
    }

    /**
     * Reads the battery in the session already open, before the hand-off (Davor, 27 Nov), so it costs no connection of
     * its own. A failed read is logged and never fails the buzz; [then] gets whether it worked.
     */
    private fun readBattery(ep: CoapEndpoint, then: (Boolean) -> Unit) {
        mark("battery")
        val request = OutgoingRequestBuilder(AirBattery.PATH, Method.GET).expectSuccess(false).forceNonBlockwise(true)
            .maxResendCount(0).build()
        disposables.add(ep.responseFor(request).timeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS).flatMap { response ->
            response.body.asData().map { body ->
                if (response.responseCode.responseClass.toInt() != 2) throw IllegalStateException("rejected (${response.responseCode})")
                AirBattery.decode(body) ?: throw IllegalStateException("unreadable reply of ${body.size} bytes")
            }
        }.subscribe(
            { r -> main.post { onBattery(r); then(true) } },
            { e -> main.post { log("Battery read failed: ${e.message}"); then(false) } },
        ))
    }

    private fun mark(phase: String) {
        phases[phase] = SystemClock.elapsedRealtime() - startedAt
        onPhase()
    }

    private fun readStrength(ep: CoapEndpoint): Single<Int> =
        send(ep, Method.PUT, AirHaptics.READ_REQUEST, maxResend = null).flatMap { response ->
            response.body.asData().map { body ->
                if (response.responseCode.responseClass.toInt() != 2) {
                    throw IllegalStateException("Strength read rejected (${response.responseCode}).")
                }
                AirHaptics.parseStrength(body)
                    ?: throw IllegalStateException("Unexpected settings body ${body.joinToString(" ") { "%02X".format(it) }}.")
            }
        }

    private fun post(ep: CoapEndpoint, body: ByteArray): Single<Unit> =
        send(ep, Method.POST, body, maxResend = 0).map { response ->
            if (response.responseCode.responseClass.toInt() != 2) {
                throw IllegalStateException("Your Fitbit Air rejected the write (${response.responseCode}).")
            }
        }

    private fun send(ep: CoapEndpoint, method: Method, body: ByteArray, maxResend: Int?): Single<IncomingResponse> {
        val builder = OutgoingRequestBuilder("/settings", method).expectSuccess(false).forceNonBlockwise(true)
        if (maxResend != null) builder.maxResendCount(maxResend)
        return ep.responseFor(builder.body(body).build()).timeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
    }

    // Finishing

    private fun succeed(message: String) = finish(true, message)

    private fun fail(message: String) = finish(false, message)

    private fun finish(ok: Boolean, message: String) {
        val done = onDone ?: return
        onDone = null
        onBuzz = null
        command = null
        main.removeCallbacks(connectTimeout)
        log(if (ok) message else "Failed: $message")
        log(stepsLine())
        val result = {
            BandResult(ok, message, firstBuzzAt?.let { it - startedAt }, SystemClock.elapsedRealtime() - startedAt,
                taps, handshakeAttempts, wrongKeys, lostRaces,
                link, LinkedHashMap(phases), bandSilent)
        }
        if (bandSilent && silentSince == 0L) silentSince = SystemClock.elapsedRealtime()
        val end = {
            // A link the Air didn't answer on isn't kept: the next try, and the warm link, start a new connection.
            // A command on a new connection keeps it as the open link, so say the link is up: on 23 Nov the warm link
            // had reported it down just before, and Home showed "Connecting…" while the link was open.
            if (keepLinkOpen && linkUp && gatt != null && !bandSilent) { releaseSession(); onLink(true) } else { closeTransport(); scheduleWarm() }
        }
        if (sessionStarted && gatt != null) {
            handOff { end(); done(result()) }
        } else {
            end()
            done(result())
        }
    }

    /** One line with the command's timings, for the step log: Test buzz shows them, calls and messages didn't. */
    private fun stepsLine(): String = buildString {
        append("Steps on the ").append(if (fellBack) "open link, then a new connection" else if (usedOpenLink) "open link" else "new connection").append(": ")
        append(phases.entries.joinToString(", ") { "${it.key} ${it.value} ms" }.ifEmpty { "none" })
        append("; $handshakeAttempts handshake${if (handshakeAttempts == 1) "" else "s"}, $wrongKeys wrong key${if (wrongKeys == 1) "" else "s"}, ")
        append("$lostRaces lost race${if (lostRaces == 1) "" else "s"}; $quietPackets packets heard while waiting for quiet")
        append("; $txWritten packets written")
        if (txDeclined.isNotEmpty()) append(", ${txDeclined.size} declined (status ${txDeclined.distinct().joinToString("/")})")
        if (duringSync) append("; started during a Google Health sync")
        append("; ${openFiles()} files open")
    }

    /** Rebuilds the stack with a resolver that refuses every key and waits for the band to resend its Gattlink session. */
    private fun handOff(then: () -> Unit) {
        val g = gatt ?: return then()
        disposeSession()
        var finished = false
        val complete = { _: String ->
            if (!finished) {
                finished = true
                then()
            }
        }
        main.postDelayed({ complete("timed out") }, HANDOFF_TIMEOUT_MS)
        try {
            val tx = TxSink().also { txSink = it }
            val rx = RxSource().also { rxSource = it }
            disposables.add(tx.dataObservable.subscribe({ p -> main.post { if (txSink === tx) enqueue(p) } }, {}))
            val s = Stack(BluetoothAddressNodeKey(g.device.address), tx.thisPointer, rx.thisPointer,
                DtlsSocketNetifGattlink(), false, RefusingKeyResolver)
            stack = s
            disposables.add(s.stackEventObservable.subscribe({ event ->
                if (event is StackEvent.GattlinkSessionResent) main.post { complete("done") }
            }, {}))
            s.start()
        } catch (t: Throwable) {
            complete("failed: ${t.message}")
        }
    }

    private fun disposeSession() {
        disposables.clear()
        endpoint?.close(); endpoint = null
        stack?.close(); stack = null
        rxSource?.close(); rxSource = null
        txSink?.close(); txSink = null
        txQueue.clear()
        writeInFlight = false
        main.removeCallbacks(writeRetry)
        writeRetries = 0
    }

    /** Ends the command but keeps the listen-only client connected and subscribed for the next one. */
    private fun releaseSession() {
        handshakeDeadline?.let(main::removeCallbacks)
        handshakeDeadline = null
        silenceCheck?.let(main::removeCallbacks)
        silenceCheck = null
        quietWait = null
        disposeSession()
        if (linkBoosted) gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        linkBoosted = false
    }

    private fun syncEnded() {
        main.removeCallbacks(syncCheck)
        log(String.format(Locale.ROOT, "Google Health sync ended: %.1f kB in %d s", syncWatch.lastBytes / 1000.0, syncWatch.lastMs / 1000))
        onHealthSync(false)
    }

    private fun closeTransport() {
        traffic.close(SystemClock.elapsedRealtime())?.let(log)
        main.removeCallbacks(deafCheck)
        resubscribedAt = 0L
        if (syncWatch.stop(SystemClock.elapsedRealtime())) syncEnded()
        handshakeDeadline?.let(main::removeCallbacks)
        handshakeDeadline = null
        silenceCheck?.let(main::removeCallbacks)
        silenceCheck = null
        quietWait = null
        // A link-status read in progress ends with the link, without its follow-up.
        main.removeCallbacks(statusTimeout)
        statusReason = null
        statusThen = null
        statusConfig = null
        rediscovering = false
        healthyStatusLogged = false
        disposeSession()
        val g = gatt
        gatt = null
        if (linkUp) onLink(false)
        deafReported = false
        linkUp = false
        warming = false
        txCharacteristic = null
        rxCharacteristic = null
        if (g != null) {
            if (linkBoosted) g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
            g.disconnect()
            g.close()
        }
        linkBoosted = false
    }
}

/**
 * Wait until Google Health's traffic on the shared channel goes quiet: start at once if nothing arrives in 300 ms,
 * otherwise once the last 1.2 s carried under 400 bytes and at least 700 ms have passed, and never wait past 10 s.
 * Values from the fitbit-air library.
 */
class QuietWait(private val startedAt: Long) {
    private val recent = ArrayDeque<Pair<Long, Int>>()
    var packets = 0
        private set

    fun record(now: Long, bytes: Int) {
        packets++
        recent.addLast(now to bytes)
    }

    fun shouldWait(now: Long): Boolean {
        val elapsed = now - startedAt
        if (elapsed >= 10_000) return false
        if (packets == 0 && elapsed >= 300) return false
        while (recent.isNotEmpty() && now - recent.first().first > 1_200) recent.removeFirst()
        val busy = recent.sumOf { it.second } >= 400
        return busy || elapsed < 700
    }
}

/**
 * Tells when Google Health is syncing with the Air, from its traffic on the open link, for the Air shelf's "Google
 * Health is syncing" state (brief decision 33). On Davor's Air (12–13 Nov) a sync was one burst of 18–29 kB, mostly
 * within 10 s, about every 15 minutes, while between syncs the busiest 10 s carried at most 1.3 kB. A sync starts when
 * the last [WINDOW_MS] carry [START_BYTES] and ends once they carry under [END_BYTES], so it ends about 10 s after its
 * burst. It can't see a sync the link doesn't hear, such as on a link that has gone deaf (see AirBand.DEAF_MS).
 */
class SyncWatch {
    private val recent = ArrayDeque<Pair<Long, Int>>()
    /** When the running or last sync's traffic began: the first packet in the window that started it. */
    var startedAt = 0L
        private set
    private var bytes = 0L

    var syncing = false
        private set
    /** Bytes in the last [WINDOW_MS] as of the last call. */
    var windowBytes = 0
        private set
    /** The last sync's bytes and length, once it ended. */
    var lastBytes = 0L
        private set
    var lastMs = 0L
        private set

    /** Counts a packet; true when a sync starts with it. */
    fun record(now: Long, size: Int): Boolean {
        recent.addLast(now to size)
        if (syncing) bytes += size
        trim(now)
        if (syncing || windowBytes < START_BYTES) return false
        syncing = true
        startedAt = recent.first().first
        bytes = windowBytes.toLong()
        return true
    }

    /** True when the sync ends at [now] because the window has gone quiet. */
    fun update(now: Long): Boolean {
        trim(now)
        return syncing && windowBytes < END_BYTES && stop(now)
    }

    /** Ends a sync without waiting, as when the link closes; true if one was running. */
    fun stop(now: Long): Boolean {
        if (!syncing) { recent.clear(); windowBytes = 0; return false }
        syncing = false
        lastBytes = bytes
        lastMs = now - startedAt
        recent.clear()
        windowBytes = 0
        return true
    }

    private fun trim(now: Long) {
        while (recent.isNotEmpty() && now - recent.first().first >= WINDOW_MS) recent.removeFirst()
        windowBytes = recent.sumOf { it.second }
    }

    companion object {
        const val WINDOW_MS = 10_000L
        const val START_BYTES = 3_000
        const val END_BYTES = 1_000
    }
}

/**
 * Sums Google Health's traffic on the channel while AirBuzz has no session of its own, one step-log line per
 * [REPORT_MS], so a "Google Health is syncing" threshold can be set from real days on the phone. Idle traffic is not
 * zero: test app 0.4 heard 43 kB in 3 idle minutes. [record] and [close] return the line to log, if one is due.
 */
class HealthTraffic {
    private var since = 0L
    private var packets = 0
    private var bytes = 0L
    private var bucketStart = 0L
    private var bucketBytes = 0
    private var busiest = 0

    fun record(now: Long, size: Int): String? {
        if (packets == 0) since = now
        if (packets == 0 || now - bucketStart >= BUCKET_MS) {
            busiest = maxOf(busiest, bucketBytes)
            bucketStart = now
            bucketBytes = 0
        }
        bucketBytes += size
        packets++
        bytes += size
        return if (now - since >= REPORT_MS) report(now) else null
    }

    /** The link closed. A link that lived for one command carried only its quiet wait, which the steps line counts. */
    fun close(now: Long): String? {
        if (packets > 0 && now - since >= MIN_REPORT_MS) return report(now)
        reset()
        return null
    }

    private fun report(now: Long): String {
        val line = String.format(Locale.ROOT, "Google Health traffic: %d packets, %.1f kB in %.1f min; busiest 10 s %.1f kB",
            packets, bytes / 1000.0, (now - since) / 60_000.0, maxOf(busiest, bucketBytes) / 1000.0)
        reset()
        return line
    }

    private fun reset() {
        packets = 0
        bytes = 0
        bucketBytes = 0
        busiest = 0
    }

    companion object {
        const val REPORT_MS = 5 * 60_000L
        const val BUCKET_MS = 10_000L
        const val MIN_REPORT_MS = 60_000L
    }
}
