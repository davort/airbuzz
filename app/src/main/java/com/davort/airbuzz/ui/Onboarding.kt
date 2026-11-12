package com.davort.airbuzz.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davort.airbuzz.Engine
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.rules.CallMode
import com.davort.airbuzz.rules.Rules
import com.davort.airbuzz.rules.Settings

private const val STEPS = 7

/** Messaging apps offered first on the apps step, when installed. */
private val MESSENGERS = listOf(
    "com.whatsapp", "org.thoughtcrime.securesms", "org.telegram.messenger", "com.google.android.apps.messaging",
    "com.facebook.orca", "com.Slack", "com.google.android.gm", "com.viber.voip", "com.discord",
)

/**
 * Onboarding (decision 39): seven steps, one ask each, shown on launch until it is finished. Steps 2–4 recheck
 * themselves when AirBuzz comes back from Android's dialogs and settings, as Setup does.
 */
@Composable
fun OnboardingScreen(engine: Engine, s: Settings, openApps: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(step > 0) { step-- }
    val next = { step = (step + 1).coerceAtMost(STEPS - 1) }
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp)) {
        StepBar(step) { step-- }
        // Steps change like pages (decision 40): Next brings the next one in from the right, Back from the left.
        AnimatedContent(step, Modifier.weight(1f), label = "step", transitionSpec = { pageChange(targetState > initialState) }) { shown ->
            Column(Modifier.fillMaxSize()) {
                when (shown) {
                    0 -> Welcome(next)
                    1 -> FindAir(next)
                    2 -> KeepRunning(next)
                    3 -> SeeAlerts(next)
                    4 -> WhoCanRing(engine, s, next)
                    5 -> WhichApps(engine, s, openApps, next)
                    else -> FeelIt(engine)
                }
            }
        }
    }
}

@Composable
private fun StepBar(step: Int, onBack: () -> Unit) {
    val a = LocalAirColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(width = 32.dp, height = 48.dp).clickable(step > 0, onClickLabel = "Back", role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.CenterStart) {
            GlyphIcon(Glyph.BACK, 24.dp, if (step > 0) a.ink else a.context)
        }
        Row(Modifier.weight(1f).semantics { contentDescription = "Step ${step + 1} of $STEPS" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(STEPS) { i ->
                // Each segment fills from its left edge.
                val fill by animateFloatAsState(if (i <= step) 1f else 0f, tween(240, easing = Motion.Out), label = "segment")
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(a.line)) {
                    Box(Modifier.fillMaxWidth(fill).height(3.dp).background(a.ink))
                }
            }
        }
        Label("${step + 1}/$STEPS")
    }
}

/** The shape every step shares: a picture on the text edge, a serif title, one muted sentence, then the step's own content. */
@Composable
private fun StepHead(title: String, body: String, art: @Composable () -> Unit) {
    val a = LocalAirColors.current
    Box(Modifier.fillMaxWidth().height(104.dp).padding(bottom = 20.dp), contentAlignment = Alignment.BottomStart) { art() }
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = Type.heading, color = a.heading, modifier = Modifier.semantics { heading() })
        Text(body, style = Type.small, color = a.muted)
    }
}

/**
 * One step: [body] scrolls, and [foot] stays at the bottom so the main button is always in view.
 * A hairline above the button shows while more of the step is below it.
 */
@Composable
private fun ColumnScope.Step(foot: @Composable ColumnScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        body()
        Spacer(Modifier.height(8.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(if (scroll.canScrollForward) LocalAirColors.current.line else Color.Transparent))
    Spacer(Modifier.height(12.dp))
    foot()
}

/** The main button at the bottom, with an optional quiet text action under it. */
@Composable
private fun StepFoot(main: String, onMain: () -> Unit, enabled: Boolean = true, link: String? = null, onLink: () -> Unit = {}) {
    Pill(main, onMain, Modifier.fillMaxWidth(), enabled = enabled)
    if (link != null) {
        Text(link, style = Type.bodyMedium, color = LocalAirColors.current.ink, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onLink).padding(vertical = 12.dp))
    }
}

@Composable
private fun IconCircle(curve: Curve) {
    val a = LocalAirColors.current
    Box(Modifier.size(64.dp).clip(CircleShape).background(a.surface).border(1.dp, a.line, CircleShape), contentAlignment = Alignment.Center) {
        CurveIcon(curve, 28.dp, a.ink, 1.8f)
    }
}

/** What Android is about to ask, quoted before it asks. */
@Composable
private fun AndroidAsks(question: String) {
    Card(gap = 6.dp) {
        Label("Android will ask")
        Text(question, style = Type.small, color = LocalAirColors.current.ink)
    }
}

@Composable
private fun Done(text: String) {
    val a = LocalAirColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.CHECK, 18.dp, a.ok, 2.2f)
        HGap(4)
        Text(text, style = Type.smallMedium, color = a.ok)
    }
}

/** One row of a step's checklist: a title over a small label, and its state or button on the right. */
@Composable
private fun CheckRow(title: String, label: String, end: @Composable () -> Unit) {
    GroupRow(vertical = 12.dp) {
        // The label may wrap: "Motorola · AirBuzz can't check" is wider than a 360 dp phone leaves beside the button.
        TwoLine(title, Modifier.weight(1f)) { Text(label.uppercase(), style = Type.label, color = LocalAirColors.current.muted) }
        end()
    }
}

@Composable
private fun ColumnScope.Welcome(next: () -> Unit) {
    Step({ StepFoot("Start", next) }) {
        StepHead("Your Fitbit\u00A0Air taps\nfor what matters",
            "AirBuzz makes your Google Fitbit Air tap for messages from apps you choose, and ring for calls from people you choose. Setup takes about two minutes.") {
            AirPebble(80.dp, Buzz.TAPPED)
        }
    }
}

@Composable
private fun ColumnScope.FindAir(next: () -> Unit) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val fixer = rememberFixer({}) {}
    val nearby = remember(tick) { Setup.hasNearby(context) }
    val on = remember(tick) { Setup.bluetoothOn(context) }
    val found = remember(tick) { Setup.bondedAirAddress(context) != null }
    Step({
        when {
            !nearby -> StepFoot("Allow", { fixer(Fix.NEARBY) })
            !on -> StepFoot("Turn on Bluetooth", { fixer(Fix.PAIR) })
            !found -> StepFoot("Open Bluetooth", { fixer(Fix.PAIR) })
            else -> StepFoot("Next", next)
        }
    }) {
        StepHead("Find your Fitbit\u00A0Air", "AirBuzz talks to it over Bluetooth, using the pairing Google Health already made.") {
            if (found) AirPebble(64.dp) else IconCircle(Curve.BLUETOOTH)
        }
        when {
            !nearby -> AndroidAsks("Allow AirBuzz to find, connect to, and determine the relative position of nearby devices?")
            !on -> Group(listOf({ CheckRow("Bluetooth is off", "Your Fitbit Air needs it") {} }))
            !found -> Group(listOf({ CheckRow("No Fitbit Air paired", "Pair it in Google Health first") {} }))
            else -> Group(listOf({ CheckRow("Fitbit Air", "Paired in Google Health") { Done("Found") } }))
        }
    }
}

@Composable
private fun ColumnScope.KeepRunning(next: () -> Unit) {
    val a = LocalAirColors.current
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var error by remember { mutableStateOf<String?>(null) }
    var opened by rememberSaveable { mutableStateOf(false) }
    val fixer = rememberFixer({}) { error = it }
    val linked = remember(tick) { Setup.isAssociated(context) }
    // Linking can fail when Android doesn't find the Air; then the step lets you go on and Setup keeps the row.
    Step({ StepFoot("Next", next, enabled = linked || error != null) }) {
        StepHead("Keep AirBuzz running", "Phones stop apps running in the background to save battery. These two settings keep AirBuzz awake for your Fitbit Air.") {
            IconCircle(Curve.LINK)
        }
        Group(listOf(
            { CheckRow("Link your Fitbit Air", "Android asks once") {
                if (linked) Done("Linked") else Pill("Link", { error = null; fixer(Fix.COMPANION) }, small = true)
            } },
            { CheckRow("Allow background activity", "${Setup.maker} · AirBuzz\u00A0can't\u00A0check") {
                if (opened) Done("Opened") else Pill("Open", { opened = true; openSettings(context, Setup.autostartSettings(context)) }, filled = false, small = true)
            } },
        ))
        error?.let { Text(it, style = Type.small, color = a.warn) }
    }
}

@Composable
private fun ColumnScope.SeeAlerts(next: () -> Unit) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val fixer = rememberFixer({}) {}
    val on = remember(tick) { Setup.hasListener(context) }
    Step({ if (on) StepFoot("Next", next) else StepFoot("Open settings", { fixer(Fix.LISTENER) }) }) {
        StepHead("See calls and messages", "AirBuzz reads which app a notification came from and who sent it. It never keeps message text.") {
            IconCircle(Curve.BELL)
        }
        if (on) Group(listOf({ CheckRow("Notification access", "AirBuzz can see alerts") { Done("On") } }))
        else AndroidAsks("Android opens Notification access. Turn on AirBuzz, then come back here.")
    }
}

@Composable
private fun ColumnScope.WhoCanRing(engine: Engine, s: Settings, next: () -> Unit) {
    val a = LocalAirColors.current
    val pick = rememberContactPicker(engine)
    Step({ StepFoot("Next", next, link = "Later", onLink = next) }) {
        StepHead("Who can ring your Fitbit\u00A0Air?", "Calls from these people ring until you answer.") {
            IconCircle(Curve.PHONE)
        }
        Seg(listOf(CallMode.CHOSEN to "Chosen", CallMode.CONTACTS to "Contacts", CallMode.ANYONE to "Anyone"), s.callMode) { m ->
            engine.store.update { it.copy(callMode = m) }
        }
        val rows = mutableListOf<@Composable () -> Unit>()
        if (s.people.isEmpty()) rows += { GroupRow(vertical = 12.dp) { Text("Nobody yet", style = Type.body, color = a.muted) } }
        s.people.forEach { p ->
            rows += { GroupRow(vertical = 10.dp) {
                Avatar(p.name)
                TwoLine(p.name, Modifier.weight(1f)) { Label(p.number?.let(Rules::display) ?: "Matched by name") }
            } }
        }
        rows += { Box(Modifier.padding(horizontal = 20.dp)) { TextAction("Add people", pick) } }
        Group(rows)
    }
}

@Composable
private fun ColumnScope.WhichApps(engine: Engine, s: Settings, more: () -> Unit, next: () -> Unit) {
    val context = LocalContext.current
    val apps = AppList.apps.collectAsStateWithLifecycle().value
    val busy = remember { engine.log.entries.value.filter { it.kind == Kind.MESSAGE }.groupingBy { it.pkg }.eachCount() }
    // Chosen apps, then known messengers, then the apps that notified most this week; six at most, fixed while open.
    val shown = remember(apps == null) {
        apps.orEmpty().sortedWith(compareBy<AppInfo>({ it.pkg !in s.apps }, { it.pkg !in MESSENGERS }, { -(busy[it.pkg] ?: 0) }, { it.label.lowercase() }))
            .filter { it.pkg in s.apps || it.pkg in MESSENGERS || (busy[it.pkg] ?: 0) > 0 }.take(6)
    }
    Step({ StepFoot("Next", next, link = "Later", onLink = next) }) {
        StepHead("Which apps tap your Fitbit\u00A0Air?", "Messages from these apps tap your band. Everything else stays on your phone.") {
            IconCircle(Curve.APPS)
        }
        Group(shown.map { app ->
            @Composable {
                GroupRow(vertical = 10.dp) {
                    AppIcon(app.pkg, 32.dp)
                    Text(app.label, style = Type.bodyMedium, color = LocalAirColors.current.ink, modifier = Modifier.weight(1f))
                    AirSwitch(app.pkg in s.apps, { on -> engine.store.update { st -> st.copy(apps = if (on) st.apps + app.pkg else st.apps - app.pkg) } },
                        Modifier.semantics { contentDescription = app.label })
                }
            }
        } + listOf<@Composable () -> Unit>({ Box(Modifier.padding(horizontal = 20.dp)) { TextAction("All apps", more) } }))
    }
}

@Composable
private fun ColumnScope.FeelIt(engine: Engine) {
    val context = LocalContext.current
    val run by engine.test.collectAsStateWithLifecycle()
    var set by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { engine.clearTest() }
    DisposableEffect(Unit) { onDispose { engine.clearTest() } }
    val turnOn = {
        if (Build.VERSION.SDK_INT >= 33 && !Setup.hasPostNotifications(context)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        engine.store.update { it.copy(pausedUntil = 0L) }
        engine.setEnabled(true)
        set = true
    }
    val again = { engine.clearTest(); engine.testBuzz() }
    val r = run?.result
    val buzzed = run?.buzzedMs
    val failed = r != null && r.taps == 0
    val connecting = run != null && buzzed == null && r == null
    val buzz = when { set -> Buzz.REST; buzzed != null -> Buzz.TAPPED; connecting -> Buzz.CONNECTING; else -> Buzz.REST }
    val (title, body) = when {
        set -> "You're set" to "AirBuzz is on. To change anything later, tap Your Fitbit Air at the bottom of Home."
        buzzed != null -> "Feel it" to "Your Fitbit Air tapped ${seconds(buzzed)} after you pressed. Did you feel it?"
        connecting -> "Feel it" to "Connecting to your Fitbit Air…"
        failed -> "No tap yet" to "Couldn't reach your Fitbit Air. ${r?.message ?: ""}".trim()
        else -> "Feel it" to "Press Test buzz. Your Fitbit Air should tap within about 3 seconds."
    }
    Step({
        when {
            set -> StepFoot("Go to Home", { engine.store.update { it.copy(onboarded = true) } })
            buzzed != null -> StepFoot("Yes, I felt it", turnOn, link = "No, try again", onLink = again)
            connecting -> StepFoot("Connecting…", {}, enabled = false)
            failed -> StepFoot("Try again", again, link = "Skip the test", onLink = turnOn)
            else -> StepFoot("Test buzz", { engine.testBuzz() })
        }
    }) {
        StepHead(title, body) { AirPebble(80.dp, buzz) }
    }
}
