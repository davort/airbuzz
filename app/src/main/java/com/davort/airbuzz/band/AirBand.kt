package com.davort.airbuzz.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
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
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What the band should do in one secure session. */
sealed interface BandCommand {
    val strength: Int

    /** [count] taps about [GAP_MS] apart, for a message. */
    data class Taps(val count: Int, override val strength: Int) : BandCommand

    /** A tap every [intervalMs] until [AirBand.stopRing] or [maxMs], for a call. */
    data class Ring(override val strength: Int, val intervalMs: Long = 1_000, val maxMs: Long = 60_000) : BandCommand

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
        const val STRENGTH_KEY = "band_strength"
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
            if (!value && !busy && gatt != null) closeTransport()
        }
    private var linkReadyAt = 0L
    private val recentRx = ArrayDeque<Pair<Long, Int>>()
    private var usedOpenLink = false
    private var fellBack = false
    private var sessionRx = 0
    private var silenceCheck: Runnable? = null
    private val txQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var txSink: TxSink? = null
    private var rxSource: RxSource? = null
    private var stack: Stack? = null
    private var endpoint: CoapEndpoint? = null

    private var quietWait: QuietWait? = null
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
    private val connectTimeout = Runnable { fail("Couldn't reach your Air within ${CONNECT_TIMEOUT_MS / 1000} s.") }

    val busy: Boolean get() = onDone != null

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
        phases.clear()
        handshakeAttempts = 0
        sessionStarted = false
        val device = findBondedAir() ?: return fail("No paired Fitbit Air. Pair it in the Google Health app first.")
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        val open = gatt
        if (keepLinkOpen && open != null && linkUp && SystemClock.elapsedRealtime() - linkReadyAt >= SETTLE_MS) {
            usedOpenLink = true
            log("Using the open link")
            // On the open link the fast-link request goes out at once (test app 0.4: no lost races from it).
            linkBoosted = open.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            if (!subscribe(open)) reconnectFresh(device, "could not refresh the subscription")
            return
        }
        if (open != null) closeTransport()
        connectFresh(device)
    }

    /** Ends a ring after its current tap, or abandons a command that has not reached the Air yet. */
    fun stopRing() {
        if (!busy) return
        stopRequested = true
        if (!sessionStarted) succeed("Stopped before the Air was reached.")
    }

    fun shutdown() {
        if (!busy) closeTransport()
    }

    private fun connectFresh(device: BluetoothDevice) {
        log("Connecting to ${device.name}")
        val g = device.connectGatt(context, false, callback, TRANSPORT_LE)
        if (g == null) fail("Android did not create a Bluetooth connection.") else gatt = g
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
                // Nothing to fail between commands: the open link dropped, so the next one connects afresh.
                if (!linkUp) { log("The open link dropped (status $status)"); closeTransport() }
                return@onMain
            }
            // A dead link cannot carry the hand-off, so skip it by clearing sessionStarted.
            when {
                status != BluetoothGatt.GATT_SUCCESS -> { sessionStarted = false; fail("Couldn't connect to your Air (status $status).") }
                newState == BluetoothProfile.STATE_CONNECTED -> { mark("connected"); g.discoverServices() }
                newState == BluetoothProfile.STATE_DISCONNECTED -> { sessionStarted = false; fail("Your Air disconnected.") }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onMain(g) {
            if (status != BluetoothGatt.GATT_SUCCESS) return@onMain fail("Service discovery failed (status $status).")
            val service = g.getService(TRACKER)
            val tx = service?.getCharacteristic(TX)
            val rx = service?.getCharacteristic(RX)
            if (tx == null || rx == null || rx.getDescriptor(CCCD) == null) return@onMain fail("Your Air's tracker channel is missing.")
            txCharacteristic = tx
            rxCharacteristic = rx
            if (!subscribe(g)) fail("Could not subscribe to the tracker channel.")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = onMain(g) {
            if (d.uuid != CCCD) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) return@onMain fail("Tracker subscription failed (status $status).")
            if (!busy) return@onMain
            mark("subscribed")
            if (!usedOpenLink) linkReadyAt = SystemClock.elapsedRealtime()
            waitForQuietChannel()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = onMain(g) {
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) fail("Tracker write failed (status $status).") else writeNext()
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
        if (rxSource != null) sessionRx++
        quietWait?.record(now, packet.size)
        rxSource?.receiveData(packet)?.subscribe({}, { fail("Receive failed: ${it.message}") })?.let(disposables::add)
    }

    private fun enqueue(packet: ByteArray) {
        txQueue.addLast(packet)
        writeNext()
    }

    private fun writeNext() {
        val g = gatt ?: return
        val c = txCharacteristic ?: return
        if (writeInFlight) return
        val packet = txQueue.pollFirst() ?: return
        writeInFlight = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            c.value = packet
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!ok) {
            writeInFlight = false
            txQueue.addFirst(packet)
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
            silenceCheck = if (!usedOpenLink || fellBack) null else Runnable {
                silenceCheck = null
                // The band answers a new Gattlink session within about a second; silence means Android stopped
                // routing notifications to this client. No command has reached the Air yet, so start over.
                if (endpoint === ep && !sessionStarted && sessionRx == 0) reconnectFresh(g.device, "nothing received")
            }.also { main.postDelayed(it, OPEN_LINK_SILENCE_MS) }
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
                fail("Google Health is using your Air. Try again in a moment.")
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
        if (handshakeAttempts >= MAX_HANDSHAKE_ATTEMPTS) return fail("Google Health is using your Air ($reason).")
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
        if (stopRequested) return succeed("Stopped before the first tap.")
        val cached = prefs.getInt(STRENGTH_KEY, 0).takeIf { it == AirHaptics.LOW || it == AirHaptics.HIGH }
        val current: Single<Int> = if (cached != null) Single.just(cached) else readStrength(ep)
        disposables.add(current.subscribe(
            { s -> main.post { strength = s; nextTap(ep, 0) } },
            { e -> main.post { fail("Couldn't read the strength: ${e.message}") } },
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
                }
                main.postDelayed({ nextTap(ep, index + 1) }, if (cmd is BandCommand.Taps && index + 1 >= cmd.count) 0 else gap)
            }
        }, { e -> main.post { fail("The tap failed: ${e.message}") } }))
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
            { s -> main.post { prefs.edit().putInt(STRENGTH_KEY, s).apply(); succeed("Buzzed $taps time${if (taps == 1) "" else "s"}.") } },
            { e -> main.post { fail(e.message ?: "Strength check failed.") } },
        ))
    }

    private fun mark(phase: String) {
        phases[phase] = SystemClock.elapsedRealtime() - startedAt
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
                throw IllegalStateException("Your Air rejected the write (${response.responseCode}).")
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
        val result = {
            BandResult(ok, message, firstBuzzAt?.let { it - startedAt }, SystemClock.elapsedRealtime() - startedAt,
                taps, handshakeAttempts, wrongKeys, lostRaces,
                if (fellBack) "open→new" else if (usedOpenLink) "open" else "new", LinkedHashMap(phases))
        }
        val end = { if (keepLinkOpen && linkUp && gatt != null) releaseSession() else closeTransport() }
        if (sessionStarted && gatt != null) {
            handOff { end(); done(result()) }
        } else {
            end()
            done(result())
        }
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

    private fun closeTransport() {
        handshakeDeadline?.let(main::removeCallbacks)
        handshakeDeadline = null
        silenceCheck?.let(main::removeCallbacks)
        silenceCheck = null
        quietWait = null
        disposeSession()
        val g = gatt
        gatt = null
        linkUp = false
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
