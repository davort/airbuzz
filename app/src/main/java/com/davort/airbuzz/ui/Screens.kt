package com.davort.airbuzz.ui

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davort.airbuzz.Engine
import com.davort.airbuzz.band.BandResult
import com.davort.airbuzz.data.Entry
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.rules.Settings
import com.davort.airbuzz.rules.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

@Composable
fun SettingsScreen(engine: Engine, s: Settings, go: (Screen) -> Unit) {
    val a = LocalAirColors.current
    val context = LocalContext.current
    val band by engine.state.collectAsStateWithLifecycle()
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    Page {
        ScreenBar("Settings", onBack = { go(Screen.HOME) }, close = true)
        Card {
            Label("Strength")
            Seg(listOf(false to "Gentle", true to "Strong"), s.strong) { strong -> engine.store.update { it.copy(strong = strong) } }
            Text("Strong uses more battery and also sets your Air's alarm strength.", style = Type.small, color = a.muted)
        }
        Group(listOf({
            GroupRow({ go(Screen.APPEARANCE) }) {
                TwoLine("Appearance", Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(when (s.theme) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" })
                        HGap(6)
                        Dot(a.accent)
                    }
                }
                GlyphIcon(Glyph.CHEVRON, 22.dp, a.muted)
            }
        }))
        Group(listOf(
            { GroupRow({ go(Screen.ACTIVITY) }) {
                TwoLine("Activity", Modifier.weight(1f)) { Label("This week's calls and messages") }
                GlyphIcon(Glyph.CHEVRON, 22.dp, a.muted)
            } },
            { GroupRow({ go(Screen.SETUP) }) {
                TwoLine("Setup checklist", Modifier.weight(1f)) { Label("Permissions and background") }
                GlyphIcon(Glyph.CHEVRON, 22.dp, a.muted)
            } },
            { GroupRow({ shareLog(context, engine) }) {
                TwoLine("Share log", Modifier.weight(1f)) { Label("When something didn't buzz") }
                GlyphIcon(Glyph.CHEVRON, 22.dp, a.muted)
            } },
            { GroupRow({ engine.store.update { it.copy(onboarded = false) }; go(Screen.HOME) }) {
                TwoLine("Show onboarding again", Modifier.weight(1f)) { Label("For testing · keeps your settings") }
                GlyphIcon(Glyph.CHEVRON, 22.dp, a.muted)
            } },
        ))
        Spacer(Modifier.weight(1f))
        Group(listOf({
            GroupRow {
                TwoLine("Google Fitbit Air", Modifier.weight(1f)) {
                    Label(lastReached(band.lastReachedAt, entries)?.let { "Reached ${timeOf(it)}" } ?: "Not reached yet")
                }
                Pill("Test", { go(Screen.TEST) }, filled = false, small = true)
            }
        }))
        Text("Works with Google Fitbit Air. AirBuzz is not made by, endorsed by or affiliated with Google or Fitbit. It talks to the Air over an unofficial protocol, so a firmware update could stop it working.",
            style = Type.small, color = a.muted)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        Label("AirBuzz $version · GNU GPL v3")
    }
}

@Composable
fun AppearanceScreen(engine: Engine, s: Settings, onBack: () -> Unit) {
    val a = LocalAirColors.current
    Page(gap = 10.dp) {
        ScreenBar("Appearance", onBack = onBack)
        Label("Theme", Modifier.padding(start = 4.dp, top = 8.dp))
        Group(listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").map { (mode, name) ->
            @Composable {
                GroupRow({ engine.store.update { it.copy(theme = mode) } }) {
                    Mark(s.theme == mode, radio = true)
                    TwoLine(name, Modifier.weight(1f), if (mode == ThemeMode.SYSTEM) ({ Label("Follows your phone · now ${if (a.dark) "dark" else "light"}") }) else null)
                }
            }
        })
        Label("Accents", Modifier.padding(start = 4.dp, top = 8.dp))
        AccentCard(dark = false, s.lightAccent) { i -> engine.store.update { it.copy(lightAccent = i) } }
        AccentCard(dark = true, s.darkAccent) { i -> engine.store.update { it.copy(darkAccent = i) } }
        Spacer(Modifier.weight(1f))
        Text("Each mode keeps its own accent. The greys take a hint of it.", style = Type.small, color = a.muted)
    }
}

@Composable
fun AccentCard(dark: Boolean, selected: Int, onSelect: (Int) -> Unit) {
    val a = LocalAirColors.current
    val accents = if (dark) DarkAccents else LightAccents
    val chosen = accents.getOrElse(selected) { accents[0] }
    Card(gap = 12.dp) {
        LabelRow("Accent in ${if (dark) "dark" else "light"}") { Label(chosen.name) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            accents.forEachIndexed { i, acc ->
                val on = i == selected
                Box(
                    Modifier.size(40.dp)
                        .drawBehind {
                            if (on) {
                                drawCircle(a.ink, size.minDimension / 2 + 5.dp.toPx())
                                drawCircle(a.surface, size.minDimension / 2 + 3.dp.toPx())
                            }
                        }
                        .clip(CircleShape).background(acc.color)
                        .clickable(role = Role.RadioButton) { onSelect(i) }
                        .semantics { contentDescription = acc.name + if (on) ", selected" else "" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) GlyphIcon(Glyph.CHECK, 20.dp, if (dark) Color(0xFF0E0F10) else Color.White)
                }
            }
        }
        // The preview is drawn in that mode's own colours, so both accents can be judged from either mode.
        val preview = airColors(dark, chosen.color)
        CompositionLocalProvider(LocalAirColors provides preview) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(preview.bg)
                .border(1.dp, preview.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Sentence(listOf(Part.Words("When"), Part.Blank("Preview", "Mum and Ana") {}, Part.Words("call")))
            }
        }
    }
}

/** One line of Test buzz's details, from the band's own timings. */
data class Step(val name: String, val text: String, val endMs: Long?, val bad: Boolean = false)

fun testSteps(r: BandResult?, buzzedMs: Long?): List<Step> {
    val p = r?.phases ?: emptyMap()
    val open = r?.link == "open"
    val ends = listOf(p["subscribed"] ?: p["connected"] ?: if (open) 0L else null, p["quiet"], p["session"], buzzedMs ?: p["buzzed"])
    val names = listOf("Found your Air", "Google Health paused", "Secure connection", "Tap sent")
    var prev = 0L
    var failureShown = false
    return names.mapIndexed { i, name ->
        val end = ends[i]
        when {
            end != null -> Step(name, if (i == 0 && open) "Open link" else seconds(end - prev), end).also { prev = end }
            r != null && r.taps == 0 && !failureShown -> { failureShown = true; Step(name, "Failed", r.totalMs, bad = true) }
            else -> Step(name, "—", null)
        }
    }
}

/**
 * Test buzz: the pebble drawing (decision 38). Grey rings pulse while AirBuzz connects; when the Air confirms the tap,
 * the capsule shakes, accent rings appear and stay, and the phone gives one haptic tap.
 */
@Composable
fun TestBuzzScreen(engine: Engine, onBack: () -> Unit) {
    val a = LocalAirColors.current
    val run by engine.test.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    val reduceMotion = remember {
        SystemSettings.Global.getFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    // A finished run from a Try in a sheet isn't this screen's; start clean.
    LaunchedEffect(Unit) { engine.clearTest() }
    DisposableEffect(Unit) { onDispose { engine.clearTest() } }
    val shake = remember { Animatable(0f) }
    val wave = remember { Animatable(0f) }
    val buzzedMs = run?.buzzedMs
    // On the tap: one ring leaves the pebble (520 ms) while it twitches twice (2 × 140 ms), decision 40.
    LaunchedEffect(buzzedMs) {
        if (buzzedMs != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (!reduceMotion) {
                launch { wave.snapTo(0f); wave.animateTo(1f, tween(520, easing = Motion.Out)) }
                shake.snapTo(0f); shake.animateTo(1f, tween(280, easing = LinearEasing))
            } else { shake.snapTo(1f); wave.snapTo(1f) }
        }
    }
    val r = run?.result
    val connecting = run != null && buzzedMs == null && r == null
    val failed = r != null && r.taps == 0
    val elapsed by produceState(0L, run?.startedAt, connecting) {
        val started = run?.startedAt ?: return@produceState
        while (connecting) { value = SystemClock.elapsedRealtime() - started; delay(100) }
    }
    var didNotFeel by remember { mutableStateOf(false) }
    var showDetails by remember(failed) { mutableStateOf(failed) }
    val steps = testSteps(r, buzzedMs)

    Page {
        ScreenBar("Test buzz", onBack = onBack)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The capsule twitches on the tap: two 1.5 dp wobbles.
            val shift = if (shake.value in 0.001f..0.999f) sin(shake.value * 4f * Math.PI.toFloat()) * with(LocalDensity.current) { 1.5.dp.toPx() } else 0f
            AirPebble(96.dp, when { buzzedMs != null -> Buzz.TAPPED; connecting -> Buzz.CONNECTING; else -> Buzz.REST }, shift,
                if (buzzedMs != null) "Your Air buzzed" else if (connecting) "Connecting to your Air" else "Your Air", wave.value)
            HGap(32)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label("Time to tap")
                val (time, color) = when {
                    buzzedMs != null -> seconds(buzzedMs).removeSuffix(" s") to a.accent
                    connecting -> seconds(elapsed).removeSuffix(" s") to a.ink
                    failed -> seconds(r?.totalMs).removeSuffix(" s") to a.warn
                    else -> "0.0" to a.muted
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(time, style = Type.number(56, 64), color = color)
                    HGap(4)
                    Text("s", style = Type.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(400)), color = color, modifier = Modifier.padding(bottom = 8.dp))
                }
                when {
                    buzzedMs != null && r == null -> Label("Buzzing now", color = a.accent)
                    buzzedMs != null -> Label("Tapped ${tapsWord(r?.taps ?: 1)}", color = a.accent)
                    connecting -> Label("Connecting")
                    failed -> Label("No tap", color = a.warn)
                    else -> Label("Ready")
                }
            }
        }
        Text(when {
            buzzedMs != null -> "Your Air tapped ${seconds(buzzedMs)} after you pressed Test."
            connecting -> "Connecting to your Air…"
            failed -> "Couldn't reach your Air. ${r?.message ?: ""}".trim()
            else -> "Press Test to tap your Air once and see how long it takes. The target is 3 s."
        }, style = Type.body, color = a.ink)
        Timeline(steps, if (connecting) elapsed else null, buzzedMs ?: if (failed) r?.totalMs else null, failed)
        if (r != null) {
            val rows = mutableListOf<@Composable () -> Unit>({
                GroupRow({ showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide details" else "Show details", style = Type.bodyMedium, color = a.ink, modifier = Modifier.weight(1f))
                    GlyphIcon(if (showDetails) Glyph.UP else Glyph.DOWN, 20.dp, a.muted)
                }
            })
            if (showDetails) {
                steps.forEach { st ->
                    rows += {
                        GroupRow(vertical = 12.dp) {
                            Text(st.name, style = Type.body, color = if (st.text == "—") a.muted else a.ink, modifier = Modifier.weight(1f))
                            Label(st.text, color = when { st.bad -> a.warn; st.name == "Tap sent" && st.endMs != null -> a.accent; else -> a.muted })
                        }
                    }
                }
                if (r.wrongKeys + r.lostRaces > 0) rows += {
                    GroupRow(vertical = 12.dp) {
                        Text("Google Health was using your Air, so AirBuzz waited and tried again.", style = Type.small, color = a.muted)
                    }
                }
            }
            Group(rows)
        }
        Spacer(Modifier.weight(1f))
        when {
            connecting -> Pill("Connecting…", {}, Modifier.fillMaxWidth(), enabled = false)
            failed -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Try again", { engine.clearTest(); engine.testBuzz() }, Modifier.weight(1f))
                Pill("Share log", { shareLog(context, engine) }, Modifier.weight(1f), filled = false)
            }
            r != null && !didNotFeel -> {
                Text("Did your Air buzz?", style = Type.bodyMedium, color = a.ink)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Yes", onBack, Modifier.weight(1f))
                    Pill("No", { didNotFeel = true }, Modifier.weight(1f), filled = false)
                }
            }
            r != null -> {
                Text("The Air confirmed the tap, so its motor was asked to run. Try Strong in Settings, and check the band is snug on your wrist.",
                    style = Type.small, color = a.muted)
                Pill("Test again", { didNotFeel = false; engine.clearTest(); engine.testBuzz() }, Modifier.fillMaxWidth())
            }
            else -> Pill("Test", { engine.testBuzz() }, Modifier.fillMaxWidth())
        }
    }
}

/** Shares AirBuzz's own step log with the app version and phone model, for when something didn't buzz. */
fun shareLog(context: android.content.Context, engine: Engine) {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    val text = buildString {
        appendLine("AirBuzz $version · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
        appendLine(engine.debug.text())
    }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share log"))
}

/** 0–8 s at 1/8 of the width per second, with the 3 s target shaded and the steps drawn as fading ink. */
@Composable
fun Timeline(steps: List<Step>, liveMs: Long?, endMs: Long?, failed: Boolean) {
    val a = LocalAirColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
        val width = maxWidth
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val w = size.width
            fun x(ms: Long) = (ms / 8000f).coerceIn(0f, 1f) * w
            drawRoundRect(a.tint, Offset(0f, 4.dp.toPx()), Size(x(3000), 16.dp.toPx()), CornerRadius(2.dp.toPx()))
            drawRect(a.line, Offset(0f, 11.dp.toPx()), Size(w, 2.dp.toPx()))
            if (liveMs != null) {
                drawRect(a.ink, Offset(0f, 9.dp.toPx()), Size(x(liveMs), 6.dp.toPx()))
            } else {
                var from = 0L
                steps.take(3).forEachIndexed { i, st ->
                    val to = st.endMs ?: return@forEachIndexed
                    if (to > from) drawRect(a.ink.copy(alpha = listOf(1f, 0.6f, 0.35f)[i]), Offset(x(from), 9.dp.toPx()), Size(x(to) - x(from), 6.dp.toPx()))
                    from = to
                }
            }
            for (s in 0..8) drawRect(a.muted, Offset((x(s * 1000L)).coerceAtMost(w - 1.dp.toPx()), 22.dp.toPx()), Size(1.dp.toPx(), 5.dp.toPx()))
            endMs?.let { drawRect(if (failed) a.warn else a.accent, Offset(x(it).coerceAtMost(w - 2.dp.toPx()), 0f), Size(2.dp.toPx(), 24.dp.toPx())) }
        }
        Label("0", Modifier.offset(y = 30.dp))
        Label("Target 3 s", Modifier.offset(x = width * 3 / 8 - 46.dp, y = 30.dp))
        Label("8 s", Modifier.align(Alignment.TopEnd).offset(y = 30.dp))
    }
}

private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

/** One day at a time (decision 32), totals in the top bar when they fit (decision 31), ignored alerts by source. */
@Composable
fun ActivityScreen(engine: Engine, open: (SheetKind) -> Unit, onBack: () -> Unit) {
    val a = LocalAirColors.current
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    val now = rememberNow()
    var daysAgo by remember { mutableIntStateOf(0) }
    var filter by remember { mutableIntStateOf(2) } // 0 all, 1 buzzed, 2 ignored
    val start = dayStart(daysAgo, now)
    val day = entries.filter { it.time in start until start + 86_400_000L && it.kind != Kind.TEST }
    val buzzed = day.count { it.fate == Fate.BUZZED }
    val ignored = day.count { it.fate == Fate.IGNORED }
    val word = when (daysAgo) { 0 -> "today"; 1 -> "yesterday"; else -> "on " + dayFormat.format(Date(start)) }
    val dayLabel = when (daysAgo) { 0 -> "Today · "; 1 -> "Yesterday · "; else -> "" } + dayFormat.format(Date(start))
    // Large text or a long title pushes the totals below the strip.
    val compact = LocalDensity.current.fontScale <= 1.15f

    Page {
        ScreenBar("Activity", onBack = onBack) {
            if (compact) Totals(buzzed, ignored, 28, 32, 16, Alignment.End)
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(dayLabel, Modifier.weight(1f))
                DayArrow(Glyph.BACK, "Previous day", daysAgo < 6) { daysAgo++ }
                DayArrow(Glyph.CHEVRON, "Next day", daysAgo > 0) { daysAgo-- }
            }
            var drag by remember { mutableFloatStateOf(0f) }
            BuzzStrip(entries, start, Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag > 60f) daysAgo = (daysAgo + 1).coerceAtMost(6)
                        if (drag < -60f) daysAgo = (daysAgo - 1).coerceAtLeast(0)
                    },
                ) { _, amount -> drag += amount }
            }, height = 44.dp, tall = 26.dp, short = 9.dp, hours = true)
        }
        if (!compact) Totals(buzzed, ignored, 40, 44, 40, Alignment.Start)
        Text("AirBuzz saw ${day.size} calls and messages $word. Ignored ones reached your phone, but your rules kept them off your wrist. Tap an app to let it buzz.",
            style = Type.small, color = a.muted, modifier = Modifier.padding(vertical = 8.dp))
        Seg(listOf(0 to "All", 1 to "Buzzed", 2 to "Ignored"), filter) { filter = it }
        if (filter == 2) {
            val sources = day.filter { it.fate == Fate.IGNORED }.groupBy { source(it) }.entries.sortedByDescending { it.value.size }
            if (sources.isEmpty()) Text("Nothing was ignored $word.", style = Type.small, color = a.muted)
            else Group(sources.map { (src, list) ->
                @Composable {
                    GroupRow(sheetFor(list.first())?.let { kind -> { open(kind) } }, vertical = 10.dp) {
                        TwoLine(src.first, Modifier.weight(1f)) { Label(src.second) }
                        Text("${list.size}", style = Type.bodyMedium.copy(fontFeatureSettings = "tnum"), color = a.ink)
                        GlyphIcon(Glyph.CHEVRON, 18.dp, a.muted)
                    }
                }
            })
        } else {
            val rows = day.filter { filter == 0 || it.fate == Fate.BUZZED }.reversed()
            if (rows.isEmpty()) Text("Nothing ${if (filter == 1) "buzzed" else "happened"} $word.", style = Type.small, color = a.muted)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { rows.forEach { HistoryRow(it) } }
        }
    }
}

/** Who or what an ignored alert came from, and why, as (name, reason). */
private fun source(e: Entry): Pair<String, String> =
    if (e.kind == Kind.CALL) (e.who ?: "Unknown caller") to "Call · ${reasonLabel(e)}" else e.appLabel to reasonLabel(e)

/** The sheet whose choice decided an ignored alert, so one tap can change it. */
private fun sheetFor(e: Entry): SheetKind? = when (e.reason) {
    "not chosen" -> if (e.kind == Kind.CALL) SheetKind.PEOPLE else SheetKind.APPS
    "quiet hours", "phone in use", "Do Not Disturb" -> SheetKind.QUIET
    "paused" -> SheetKind.PAUSE
    else -> null
}

@Composable
private fun Totals(buzzed: Int, ignored: Int, size: Int, line: Int, gap: Int, align: Alignment.Horizontal) {
    val a = LocalAirColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
        listOf(Triple(buzzed, a.accent, "Buzzed"), Triple(ignored, a.ink, "Ignored")).forEach { (n, color, label) ->
            Column(horizontalAlignment = align) {
                Text("$n", style = Type.number(size, line), color = color)
                Label(label)
            }
        }
    }
}

@Composable
private fun DayArrow(glyph: Glyph, label: String, enabled: Boolean, onClick: () -> Unit) {
    val a = LocalAirColors.current
    Box(
        Modifier.size(32.dp).alpha(if (enabled) 1f else 0.38f).clip(CircleShape).border(1.dp, a.line, CircleShape)
            .clickable(enabled, onClickLabel = label, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, 16.dp, a.ink) }
}

@Composable
fun SetupScreen(engine: Engine, go: (Screen) -> Unit, onBack: () -> Unit) {
    val a = LocalAirColors.current
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    val fixer = rememberFixer(go) { error = it }
    val testedOk = entries.any { it.kind == Kind.TEST && it.fate == Fate.BUZZED }
    val checks = remember(tick, testedOk) { Setup.checks(context, testedOk) }
    Page {
        ScreenBar("Setup checklist", onBack = onBack)
        Group(checks.map { c ->
            @Composable {
                GroupRow {
                    Dot(when (c.done) { true -> a.ok; false -> a.warn; null -> a.muted }, 10.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(c.title, style = Type.bodyMedium, color = a.ink)
                        Text(c.detail, style = Type.small, color = a.muted)
                    }
                    if (c.done != true && c.fix != null) Pill(c.fixLabel ?: "Fix", { error = null; fixer(c.fix) }, filled = false, small = true)
                }
            }
        })
        error?.let { Text(it, style = Type.small, color = a.warn) }
    }
}
