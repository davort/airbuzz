package com.davort.airbuzz.ui

import androidx.activity.compose.BackHandler
import android.provider.Settings as SystemSettings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.sin
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davort.airbuzz.Engine
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.rules.CallMode
import com.davort.airbuzz.rules.Rules
import com.davort.airbuzz.rules.Settings

/** The setup steps counted by the stepper. The intro before them and "You're set" after them aren't steps (issue #17). */
private const val STEPS = 6
private const val INTRO = 0
private const val DONE = STEPS + 1


/**
 * Onboarding (decision 39): an intro, six steps with one ask each, and "You're set", shown on launch until it is
 * finished. Steps 1–3 recheck themselves when AirBuzz comes back from Android's dialogs and settings, as Setup does.
 */
@Composable
fun OnboardingScreen(engine: Engine, s: Settings, openApps: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(INTRO) }
    // The intro's animation plays once; coming back from step 1 shows where it ends (issue #26).
    var introPlayed by rememberSaveable { mutableStateOf(false) }
    // Once AirBuzz is on, there is no going back into setup.
    BackHandler(page in 1..STEPS) { page-- }
    // AirBuzz is already on at "You're set", so Back finishes setup like its button.
    BackHandler(page == DONE) { engine.store.update { it.copy(onboarded = true) } }
    val next = { page = (page + 1).coerceAtMost(DONE) }
    // Set when someone continues past step 1 without a Fitbit Air (Play reviewers, or one still in its box, #16):
    // step 2 can't link it and Feel it can't buzz it, so it goes from the apps step to "You're set".
    var noAir by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp)) {
        // The bar's room is kept on every page, so the content below never jumps when it comes or goes (issue #27).
        // It drops in just after the first step starts sliding, and its first segment fills as it arrives.
        Column(Modifier.fillMaxWidth().height(48.dp)) {
            AnimatedVisibility(visible = page in 1..STEPS,
                enter = fadeIn(tween(240, 80, Motion.Out)) + slideInVertically(tween(320, 80, Motion.Out)) { -it / 3 },
                exit = fadeOut(tween(150, easing = Motion.Out))) {
                StepBar(page) { page-- }
            }
        }
        // Steps change like pages (decision 40): Next brings the next one in from the right, Back from the left.
        // "You're set" is an ending, not a next step, so it fades and settles in place instead.
        AnimatedContent(page, Modifier.weight(1f), label = "step", transitionSpec = {
            if (targetState == DONE) ContentTransform(
                fadeIn(tween(420, 120, Motion.Out)) + scaleIn(tween(520, 120, Motion.Out), initialScale = 0.94f),
                fadeOut(tween(160, easing = Motion.In)),
                sizeTransform = null,
            ) else pageChange(targetState > initialState)
        }) { shown ->
            Column(Modifier.fillMaxSize()) {
                when (shown) {
                    INTRO -> Intro(introPlayed, { introPlayed = true }) { introPlayed = true; next() }
                    1 -> FindAir({ noAir = false; next() }) { noAir = true; next() }
                    2 -> KeepRunning(noAir, next)
                    3 -> SeeAlerts(next)
                    4 -> WhoCanRing(engine, s, next)
                    5 -> WhichApps(engine, s, openApps) { if (noAir) { turnOn(engine); page = DONE } else next() }
                    6 -> FeelIt(engine, next)
                    else -> YoureSet(engine, s)
                }
            }
        }
    }
}

@Composable
private fun StepBar(step: Int, onBack: () -> Unit) {
    val a = LocalAirColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(width = 32.dp, height = 48.dp).clickable(onClickLabel = "Back", role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.CenterStart) {
            GlyphIcon(Glyph.BACK, 24.dp, a.ink)
        }
        Row(Modifier.weight(1f).semantics { contentDescription = "Step $step of $STEPS" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(STEPS) { i ->
                // Each segment fills from its left edge.
                // Starts empty, so the first segment also fills when the bar first appears.
                val fill = remember { Animatable(0f) }
                LaunchedEffect(i < step) { fill.animateTo(if (i < step) 1f else 0f, tween(240, if (step == 1) 200 else 0, Motion.Out)) }
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(a.line)) {
                    Box(Modifier.fillMaxWidth(fill.value).height(3.dp).background(a.ink))
                }
            }
        }
        Label("$step/$STEPS")
    }
}

/** The shape every step shares: a picture on the text edge, a serif title, one muted sentence, then the step's own content. */
@Composable
private fun StepHead(title: String, body: String, art: @Composable () -> Unit) {
    val a = LocalAirColors.current
    Box(Modifier.fillMaxWidth().height(104.dp).padding(bottom = 20.dp), contentAlignment = Alignment.BottomStart) { art() }
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = Type.heading, color = a.ink, modifier = Modifier.semantics { heading() })
        // Body size, not small: at 14 sp every step looked empty beside the heading and the button (issue #19).
        Text(body, style = Type.body, color = a.muted)
    }
}

/**
 * One step: [body] scrolls, and [foot] stays at the bottom so the main button is always in view.
 * The body fades out under the progress bar and above the button, as in the sheets.
 */
@Composable
private fun ColumnScope.Step(foot: @Composable ColumnScope.() -> Unit, gap: Int = 12, body: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.weight(1f).fillMaxWidth().edgeFades(scroll, LocalAirColors.current.bg).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        body()
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(12.dp))
    foot()
}

/** The main button at the bottom, with an optional quiet text action under it. */
@Composable
private fun StepFoot(main: String, onMain: () -> Unit, enabled: Boolean = true, link: String? = null, onLink: () -> Unit = {}, linkSaid: String? = null) {
    Pill(main, onMain, Modifier.fillMaxWidth(), enabled = enabled)
    if (link != null) {
        // A 12 dp gap, so a thumb on the button's edge can't land on the text action's 48 dp target.
        Spacer(Modifier.height(12.dp))
        Text(link, style = Type.bodyMedium, color = LocalAirColors.current.ink, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onLink).padding(vertical = 12.dp)
                .then(if (linkSaid != null) Modifier.semantics { contentDescription = linkSaid } else Modifier))
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
private fun CheckRow(title: String, label: String, muted: Boolean = false, end: @Composable () -> Unit) {
    val a = LocalAirColors.current
    GroupRow(vertical = 12.dp) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.bodyMedium, color = if (muted) a.muted else a.ink)
            Text(label.uppercase(), style = Type.label, color = a.muted)
        }
        end()
    }
}

/**
 * The centred page that closes onboarding: the pebble with its rings, a large serif title, one sentence, and
 * the button at the bottom with a note above it. "You're set" mirrors the intro's last frame (issues #17, #26).
 */
@Composable
private fun ColumnScope.Bookend(title: String, body: String, note: String, button: String, onClick: () -> Unit, wave: Float = 0f) {
    val a = LocalAirColors.current
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // The rings reach 18 dp past the capsule.
        Box(Modifier.padding(18.dp)) {
            AirPebble(120.dp, Buzz.TAPPED, wave = wave)
            // A check on the capsule, so the last page reads as done, not as the intro again (Davor, 26 Nov).
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 12.dp).size(36.dp).clip(CircleShape).background(a.accent)
                .border(3.dp, a.bg, CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.CHECK, 20.dp, a.onInk, 2.6f) }
        }
        Gap(28)
        Text(title, style = Type.heading.copy(fontSize = 36.sp, lineHeight = 40.sp), color = a.ink, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Gap(12)
        Text(body, style = Type.body, color = a.muted, textAlign = TextAlign.Center, modifier = Modifier.width(300.dp))
    }
    Spacer(Modifier.height(12.dp))
    Text(note, style = Type.small, color = a.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
    Gap(12)
    Pill(button, onClick, Modifier.fillMaxWidth())
}

/** When the intro's buzz happens, in ms from the start (issue #26, Paper "Simple intro"). */
private object IntroAt {
    const val BUZZ = 1500f      // the Fitbit Air buzzes once, after the page has had a moment to land
    const val END = 2600f       // the second wave has left the capsule
}

/**
 * The first-launch intro (issue #26): the wordmark, the Fitbit Air, the title and the sentence are all there from the
 * first frame, and 1.5 s in the Fitbit Air buzzes, then again every 3 s. With Remove animations on it shows the buzzed
 * end state and stays still.
 */
@Composable
private fun ColumnScope.Intro(played: Boolean, onPlayed: () -> Unit, next: () -> Unit) {
    val a = LocalAirColors.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val reduceMotion = remember {
        SystemSettings.Global.getFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val t = remember { Animatable(if (played || reduceMotion) IntroAt.END else 0f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        if (t.value < IntroAt.END) {
            t.animateTo(IntroAt.END, tween(IntroAt.END.toInt(), easing = LinearEasing))
            onPlayed()
        }
        // Then it buzzes again every 3 s while the page is open, so it looks alive (Davor, 26 Nov).
        while (true) {
            delay(1900)
            t.snapTo(IntroAt.BUZZ)
            t.animateTo(IntroAt.END, tween((IntroAt.END - IntroAt.BUZZ).toInt(), easing = LinearEasing))
        }
    }
    val now = t.value
    // The capsule twitches twice as in Test buzz, and two rings leave it.
    val shake = ((now - IntroAt.BUZZ) / 280f).let { if (it in 0f..1f) sin(it * 4f * Math.PI.toFloat()) * with(density) { 2.5.dp.toPx() } else 0f }
    val wave = listOf(IntroAt.BUZZ, IntroAt.BUZZ + 500f).map { (now - it) / 520f }.firstOrNull { it in 0.001f..0.999f } ?: 0f

    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("AirBuzz", style = Type.heading.copy(fontSize = 26.sp, lineHeight = 30.sp), color = a.ink)
        Gap(28)
        Box(Modifier.padding(18.dp)) {
            AirPebble(120.dp, if (now >= IntroAt.BUZZ) Buzz.TAPPED else Buzz.REST, shake, wave = wave)
        }
        Gap(28)
        Text("Your Fitbit\u00A0Air, buzzing for what\u00A0matters", style = Type.heading.copy(fontSize = 36.sp, lineHeight = 40.sp),
            color = a.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Gap(12)
        Text("Calls from the people you pick. Messages from the apps you pick. Everything else stays on your phone.",
            style = Type.body, color = a.muted, textAlign = TextAlign.Center, modifier = Modifier.width(300.dp))
    }
    Spacer(Modifier.height(12.dp))
    Label("Setup takes about two minutes", Modifier.align(Alignment.CenterHorizontally))
    Gap(12)
    Pill("Set up AirBuzz", next, Modifier.fillMaxWidth())
}

@Composable
private fun ColumnScope.FindAir(next: () -> Unit, skip: () -> Unit) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    // Android stops showing its dialog after a second "Don't allow"; then the step points to App info instead (#16).
    var blocked by rememberSaveable { mutableStateOf(false) }
    val fixer = rememberFixer({}, onBlocked = { blocked = true }) {}
    val nearby = remember(tick) { Setup.hasNearby(context) }
    val on = remember(tick) { Setup.bluetoothOn(context) }
    val found = remember(tick) { Setup.bondedAirAddress(context) != null }
    val health = remember(tick) { Setup.hasGoogleHealth(context) }
    // Whenever step 1 can't finish, people can go on without a Fitbit Air and pair it later (#16).
    @Composable fun Foot(main: String, onMain: () -> Unit) =
        StepFoot(main, onMain, link = "Continue without it", onLink = skip, linkSaid = "Continue without a Fitbit\u00A0Air")
    Step({
        when {
            !nearby && blocked -> Foot("Open settings") { openSettings(context, Setup.appDetails(context)) }
            !nearby -> StepFoot("Find my Fitbit\u00A0Air", { fixer(Fix.NEARBY) })
            !on -> Foot("Turn on Bluetooth") { fixer(Fix.PAIR) }
            !found -> Foot(if (health) "Open Google Health" else "Get Google Health") { Setup.openGoogleHealth(context) }
            else -> StepFoot("Next", next)
        }
    }) {
        StepHead("Find your Fitbit\u00A0Air", "AirBuzz talks to it over Bluetooth, using the pairing Google Health already made.") {
            if (found) AirPebble(64.dp) else IconCircle(Curve.BLUETOOTH)
        }
        when {
            !nearby && blocked -> Group(listOf({ Column {
                CheckRow("Nearby devices is off", "Needed to find your Fitbit Air") {}
                PagePath(listOf("Tap " to "Permissions", "Tap " to "Nearby devices", "Choose " to "Allow"))
            } }))
            !nearby -> AndroidAsks("Allow AirBuzz to find, connect to, and determine the relative position of nearby devices?")
            !on -> Group(listOf({ CheckRow("Bluetooth is off", "Your Fitbit Air needs it") {} }))
            !found -> Group(listOf({ CheckRow("No Fitbit Air paired yet", "Pair it in Google Health first") {} }))
            else -> Group(listOf({ CheckRow("Fitbit Air", "Paired in Google Health") { Done("Found") } }))
        }
    }
}

/**
 * Both rows are real checks with the same filled button (issues #18, #20). Background activity is Android's
 * battery-optimisation exemption, which App info sets; the path to it is spelled out under the row until it's set.
 */
@Composable
private fun ColumnScope.KeepRunning(noAir: Boolean, next: () -> Unit) {
    val a = LocalAirColors.current
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var error by remember { mutableStateOf<String?>(null) }
    var opened by rememberSaveable { mutableStateOf(false) }
    val fixer = rememberFixer({}) { error = it }
    val linked = remember(tick) { Setup.isAssociated(context) }
    val allowed = remember(tick) { Setup.ignoresBatteryOptimizations(context) }
    // Linking can fail when Android doesn't find the Air, and a phone may not report the battery setting; then the
    // step lets you go on (after a visit to the page) and Setup keeps the row.
    Step({ StepFoot("Next", next, enabled = (linked || error != null || noAir) && (allowed || opened)) }) {
        StepHead("Keep AirBuzz running", "Phones stop apps running in the background to save battery. These two settings keep AirBuzz awake for your Fitbit\u00A0Air.") {
            IconCircle(Curve.LINK)
        }
        val rows = listOf<@Composable () -> Unit>(
            // Without a Fitbit Air there is nothing to link yet; the Setup checklist offers it once one is paired.
            { CheckRow("Link your Fitbit Air", if (noAir && !linked) "After you pair it" else "Android asks once", muted = noAir && !linked) {
                if (linked) Done("Linked") else if (!noAir) Pill("Link", { error = null; fixer(Fix.COMPANION) }, small = true)
            } },
            // One task, one panel (issue #28): the path to the setting sits in the same panel as its row.
            { Column {
                CheckRow("Allow background activity", if (Setup.isColorOs) "${Setup.maker} · Battery usage" else "${Setup.maker} · Battery") {
                    if (allowed) Done("Allowed") else Pill("Open", { opened = true; openSettings(context, Setup.autostartSettings(context)) }, small = true)
                }
                if (!allowed) PagePath(if (Setup.isColorOs) listOf("Tap " to "Battery usage", "Choose " to "Allow background activity", "Tap " to "Allow")
                    else listOf("Tap " to "Battery", "Choose " to "Unrestricted"))
            } },
        )
        Group(rows)
        error?.let { Text(it, style = Type.small, color = a.warn) }
    }
}

/**
 * What to tap on the page a button opens, under its row in the same panel. The background path on ColorOS was checked
 * on Davor's phone; other phones get a general hint.
 */
@Composable
private fun PagePath(lines: List<Pair<String, String>>) {
    val a = LocalAirColors.current
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("On the page that opens:", style = Type.small, color = a.muted, modifier = Modifier.padding(bottom = 2.dp))
        lines.forEachIndexed { i, (verb, place) ->
            Row(verticalAlignment = Alignment.Top) {
                Text("${i + 1}", style = Type.label.copy(lineHeight = Type.small.lineHeight), color = a.muted, modifier = Modifier.width(26.dp))
                Text(buildAnnotatedString {
                    append(verb)
                    withStyle(SpanStyle(fontWeight = FontWeight(500))) { append(place) }
                }, style = Type.small, color = a.ink)
            }
        }
    }
}

/**
 * Notification access, which AirBuzz needs, and AirBuzz's own notifications, asked here with a reason before Android's
 * dialog instead of without one after the test (issue #25). Only access is required to go on.
 */
@Composable
private fun ColumnScope.SeeAlerts(next: () -> Unit) {
    val a = LocalAirColors.current
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val fixer = rememberFixer({}) {}
    val on = remember(tick) { Setup.hasListener(context) }
    val notices = remember(tick) { Setup.hasPostNotifications(context) }
    Step({ StepFoot("Next", next, enabled = on) }) {
        StepHead("See calls and messages", "AirBuzz reads which app a notification came from and who sent it. It never keeps message text.") {
            IconCircle(Curve.BELL)
        }
        Group(listOf(
            { CheckRow("Notification access", if (on) "So AirBuzz sees alerts" else "Turn on AirBuzz there") {
                if (on) Done("On") else Pill("Open", { fixer(Fix.LISTENER) }, small = true)
            } },
            { CheckRow("AirBuzz notifications", "When a buzz didn't arrive") {
                if (notices) Done("On") else Pill("Allow", { fixer(Fix.NOTIFICATIONS) }, small = true)
            } },
        ))
        Text("They tell you when an alert didn't reach your Fitbit Air or Bluetooth is off, and let you silence a call that's ringing it.",
            style = Type.small, color = a.muted)
    }
}

/** Worded as a choice being made, not the current state (issue #21); only Chosen has a list (issue #11). */
@Composable
private fun ColumnScope.WhoCanRing(engine: Engine, s: Settings, next: () -> Unit) {
    val a = LocalAirColors.current
    val pick = rememberContactPicker(engine)
    // Anyone is already chosen, so there is nothing to put off: Next keeps it (Davor, 26 Nov).
    Step({ StepFoot("Next", next) }) {
        StepHead("Who should ring your Fitbit\u00A0Air?", "Their calls buzz it every second until you answer. You can narrow it down any\u00A0time.") {
            IconCircle(Curve.PHONE)
        }
        // The choice and what it will do share one panel; Chosen's people and Add people continue it (Davor, 15 Nov).
        val rows = mutableListOf<@Composable () -> Unit>({
            Box(Modifier.padding(16.dp)) { CallChoice(s.callMode, s.people) { m -> engine.store.update { it.copy(callMode = m) } } }
        })
        if (s.callMode == CallMode.CHOSEN) {
            s.people.forEach { p ->
                rows += { GroupRow(vertical = 10.dp) {
                    Avatar(p.name)
                    TwoLine(p.name, Modifier.weight(1f)) { Label(p.number?.let(Rules::display) ?: "Matched by name") }
                } }
            }
            rows += { Box(Modifier.padding(horizontal = 20.dp)) { TextAction("Add people", pick) } }
        }
        Group(rows)
    }
}

@Composable
private fun ColumnScope.WhichApps(engine: Engine, s: Settings, more: () -> Unit, next: () -> Unit) {
    val apps = AppList.apps.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val busy = remember { engine.log.entries.value.filter { it.kind == Kind.MESSAGE }.groupingBy { it.pkg }.eachCount() }
    // Chosen apps, then messaging and email apps, then the apps that notified most this week; six, in an order fixed
    // while open so a switch never jumps. An app chosen in All apps joins at the top, so the choice shows (Davor, 26 Nov).
    val first = remember(apps == null) {
        val talk = talkApps(context)
        apps.orEmpty().sortedWith(appOrder(s, talk, busy))
            .filter { it.pkg in s.apps || it.pkg in talk || (busy[it.pkg] ?: 0) > 0 }.take(6)
    }
    // Once added, a row stays while the step is open, even if switched off again, so the list doesn't jump.
    val added = remember { mutableStateListOf<AppInfo>() }
    LaunchedEffect(s.apps, first) {
        added.addAll(0, apps.orEmpty().filter { it.pkg in s.apps && first.none { f -> f.pkg == it.pkg } && added.none { a -> a.pkg == it.pkg } })
    }
    val shown = added + first
    Step({ StepFoot("Next", next, link = "Later", onLink = next) }) {
        StepHead("Which apps should buzz your Fitbit\u00A0Air?", "A message from one of these buzzes it once. Everything else stays on your phone.") {
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

/**
 * The last step reuses Test buzz's readout (issue #22). The question and its answers stay pinned below it as one
 * centred group, and the main button says what it lets happen (issue #24).
 */
@Composable
private fun ColumnScope.FeelIt(engine: Engine, done: () -> Unit) {
    val a = LocalAirColors.current
    val run by engine.test.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { engine.clearTest() }
    DisposableEffect(Unit) { onDispose { engine.clearTest() } }
    val turnOn = { turnOn(engine); done() }
    val again = { engine.clearTest(); engine.testBuzz() }
    val r = run?.result
    val buzzed = run?.buzzedMs
    val failed = r != null && r.taps == 0
    val connecting = run != null && buzzed == null && r == null
    Step({
        when {
            connecting -> StepFoot("Connecting…", {}, enabled = false)
            failed -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Try again", again, Modifier.fillMaxWidth())
                Pill("Skip the test", turnOn, Modifier.fillMaxWidth(), filled = false)
            }
            buzzed != null || r != null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Did your Fitbit Air buzz?", style = Type.bodyMedium, color = a.ink, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Pill("Yes, turn AirBuzz on", turnOn, Modifier.fillMaxWidth())
                // A new test can start only once the hand-off has finished and the result is in.
                Pill("No, buzz again", again, Modifier.fillMaxWidth(), filled = false, enabled = r != null)
            }
            else -> StepFoot("Test buzz", { engine.testBuzz() })
        }
    }, gap = 24) {
        Text("Feel it", style = Type.heading, color = a.ink, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
        TestBuzzReadout(engine, button = "Test buzz")
    }
}

/** Setup ends with AirBuzz on and not paused, after Feel it or straight from the apps step without a Fitbit Air. */
private fun turnOn(engine: Engine) {
    engine.store.update { it.copy(pausedUntil = 0L) }
    engine.setEnabled(true)
}

@Composable
private fun ColumnScope.YoureSet(engine: Engine, s: Settings) {
    val context = LocalContext.current
    // One ring leaves the pebble as the page settles.
    val wave = remember { Animatable(0f) }
    LaunchedEffect(Unit) { wave.animateTo(1f, tween(700, 260, Motion.Out)) }
    Bookend(
        title = "You're set",
        body = setUpSummary(s) { appLabel(context, it) },
        note = "To change anything later, tap Your Fitbit\u00A0Air at the bottom of Home.",
        button = "Start using AirBuzz",
        onClick = { engine.store.update { it.copy(onboarded = true) } },
        wave = wave.value,
    )
}

/** What setup turned on, in one or two sentences, such as "Calls from Mum now ring your Fitbit Air. Messages from WhatsApp buzz it once." */
fun setUpSummary(s: Settings, label: (String) -> String): String {
    fun list(names: List<String>, more: String) = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} $more"
    }
    val calls = when (s.callMode) {
        CallMode.ANYONE -> "Every call now rings your Fitbit\u00A0Air."
        CallMode.CONTACTS -> "Calls from your contacts now ring your Fitbit\u00A0Air."
        CallMode.CHOSEN -> if (s.people.isEmpty()) null
            else "Calls from ${list(s.people.map { it.name }, if (s.people.size == 3) "other" else "others")} now ring your Fitbit\u00A0Air."
    }
    val messages = if (s.apps.isEmpty()) null else {
        val apps = list(s.apps.map(label).sortedBy { it.lowercase() }, if (s.apps.size == 3) "more app" else "more apps")
        "Messages from $apps buzz ${if (calls != null) "it" else "your Fitbit\u00A0Air"} ${tapsWord(s.messageTaps)}."
    }
    return listOfNotNull(calls, messages).joinToString(" ").ifEmpty { "AirBuzz is on. Choose who rings and which apps buzz from\u00A0Home." }
}
