package com.davort.airbuzz.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.davort.airbuzz.Engine
import com.davort.airbuzz.data.Entry
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.engine
import com.davort.airbuzz.rules.CallMode
import com.davort.airbuzz.rules.Person
import com.davort.airbuzz.rules.Rules
import com.davort.airbuzz.rules.Settings as AirSettings
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

enum class Screen { HOME, SETTINGS, APPEARANCE, TEST, ACTIVITY, SETUP }

enum class SheetKind { PEOPLE, APPS, PATTERN, QUIET, PAUSE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = engine
        setContent {
            val settings by engine.store.settings.collectAsStateWithLifecycle()
            AirBuzzTheme(settings) {
                val a = LocalAirColors.current
                LaunchedEffect(a.dark) {
                    val style = if (a.dark) SystemBarStyle.dark(Color.Transparent.toArgb())
                        else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
                    enableEdgeToEdge(style, style)
                }
                Box(Modifier.fillMaxSize().background(a.bg)) { App(engine, settings) }
            }
        }
    }
}

/** Rechecks permissions each time AirBuzz comes back to the front, since they are changed in system screens. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    return tick
}

/** The wall clock, refreshed every [everyMs] so countdowns and "today" stay current. */
@Composable
fun rememberNow(everyMs: Long = 30_000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(everyMs); value = System.currentTimeMillis() }
    }
    return now
}

@Composable
fun App(engine: Engine, settings: AirSettings) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var from by rememberSaveable { mutableStateOf(Screen.HOME) }
    var sheet by rememberSaveable { mutableStateOf<SheetKind?>(null) }
    val go: (Screen) -> Unit = { from = screen; screen = it }
    BackHandler(screen != Screen.HOME) {
        go(if (screen == Screen.APPEARANCE || screen == Screen.SETUP) Screen.SETTINGS else Screen.HOME)
    }
    val open: (SheetKind) -> Unit = { sheet = it }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (!settings.onboarded) {
            OnboardingScreen(engine, settings)
            return@Box
        }
        // Anchor (decision 40): Home's shelf stays put and becomes Settings' last row while Settings fades in above it;
        // Back fades Settings out over Home, faster. Every other move is a cut.
        AnimatedContent(screen, label = "screen", transitionSpec = {
            when {
                initialState == Screen.HOME && targetState == Screen.SETTINGS ->
                    fadeIn(tween(Motion.ANCHOR * 6 / 10, easing = Motion.Out)) togetherWith ExitTransition.KeepUntilTransitionsFinished
                initialState == Screen.SETTINGS && targetState == Screen.HOME ->
                    (EnterTransition.None togetherWith fadeOut(tween(Motion.ANCHOR_BACK, easing = Motion.In))).apply { targetContentZIndex = -1f }
                else -> EnterTransition.None togetherWith ExitTransition.None
            }
        }) { shown ->
            when (shown) {
                Screen.HOME -> Home(engine, settings, sheet, go, open)
                Screen.SETTINGS -> SettingsScreen(engine, settings, go, play = from == Screen.HOME)
                Screen.APPEARANCE -> AppearanceScreen(engine, settings) { go(Screen.SETTINGS) }
                Screen.TEST -> TestBuzzScreen(engine) { go(Screen.HOME) }
                Screen.ACTIVITY -> ActivityScreen(engine, open) { go(Screen.HOME) }
                Screen.SETUP -> SetupScreen(engine, go) { go(Screen.SETTINGS) }
            }
        }
    }
    sheet?.let { kind ->
        val close = { sheet = null }
        when (kind) {
            SheetKind.PEOPLE -> PeopleSheet(engine, settings, close)
            SheetKind.APPS -> AppsSheet(engine, settings, close)
            SheetKind.PATTERN -> PatternSheet(engine, settings, close)
            SheetKind.QUIET -> QuietSheet(engine, settings, close)
            SheetKind.PAUSE -> PauseSheet(engine, settings, close)
        }
    }
}

/** "Mum", "Mum and Ana", "Mum, Ana and 2 more". */
fun summarize(names: List<String>, none: String): String = when (names.size) {
    0 -> none
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
}

fun quietRules(s: AirSettings): List<String> = buildList {
    if (s.quietHours) add("at night")
    if (s.quietDnd) add("in Do Not Disturb")
    if (s.quietInUse) add("while I use my phone")
}

fun tapsWord(n: Int) = when (n) { 1 -> "once"; 2 -> "twice"; else -> "three times" }

fun timeOf(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

fun dayStart(daysAgo: Int = 0, now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
    timeInMillis = now
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, -daysAgo)
}.timeInMillis

/** When AirBuzz last made the Air buzz: from the band, or from the log after a restart. */
fun lastReached(bandReachedAt: Long?, entries: List<Entry>): Long? =
    listOfNotNull(bandReachedAt, entries.lastOrNull { it.fate == Fate.BUZZED }?.time).maxOrNull()

@Composable
fun Home(engine: Engine, s: AirSettings, sheet: SheetKind?, go: (Screen) -> Unit, open: (SheetKind) -> Unit) {
    val context = LocalContext.current
    val a = LocalAirColors.current
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val now = rememberNow()
    val apps = remember(tick) { installedApps(context) }
    val paused = s.enabled && s.pausedUntil > now
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Box(Modifier.fillMaxSize()) {
        Page {
            // Top bar: title, state and switch (decision 20). Settings is the Air shelf (decision 33).
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AirBuzz", style = Type.heading, color = a.heading, modifier = Modifier.weight(1f).semantics { heading() })
                // The status rolls: the old word leaves upward, the new one rises in (decision 40).
                val roll = with(LocalDensity.current) { 10.dp.roundToPx() }
                AnimatedContent(when { paused -> 1; s.enabled -> 0; else -> 2 }, label = "status", contentAlignment = Alignment.CenterEnd, transitionSpec = {
                    (fadeIn(tween(200, easing = Motion.Out)) + slideInVertically(tween(200, easing = Motion.Out)) { roll }) togetherWith
                        (fadeOut(tween(150, easing = Motion.In)) + slideOutVertically(tween(150, easing = Motion.In)) { -roll })
                }) { state ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (state) {
                            1 -> { GlyphIcon(Glyph.PAUSE, 16.dp, a.muted, 2.4f); HGap(4); Text("Paused", style = Type.smallMedium, color = a.muted) }
                            0 -> { Dot(a.ok); HGap(6); Text("On", style = Type.smallMedium, color = a.ink) }
                            else -> { Dot(a.muted); HGap(6); Text("Off", style = Type.smallMedium, color = a.muted) }
                        }
                    }
                }
                AirSwitch(s.enabled && !paused, { on ->
                    if (on) {
                        if (Build.VERSION.SDK_INT >= 33 && !Setup.hasPostNotifications(context)) {
                            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        engine.store.update { it.copy(pausedUntil = 0L) }
                        if (!s.enabled) engine.setEnabled(true)
                    } else {
                        open(SheetKind.PAUSE)
                    }
                }, Modifier.semantics { contentDescription = "AirBuzz" })
            }

            // The paused card and the day's strip swap in place; the space between them eases to the new height.
            AnimatedContent(paused, label = "paused", transitionSpec = {
                fadeIn(tween(240, easing = Motion.Out)) togetherWith fadeOut(tween(150, easing = Motion.In)) using
                    SizeTransform(clip = false) { _, _ -> tween(240, easing = Motion.InOut) }
            }) { pausedNow -> if (pausedNow) {
                PausedCard(s, now, onResume = { engine.store.update { it.copy(pausedUntil = 0L) } }) { open(SheetKind.PAUSE) }
            } else {
                val start = dayStart(now = now)
                val today = entries.filter { it.time >= start && it.kind != Kind.TEST }
                Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open Activity") { go(Screen.ACTIVITY) },
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BuzzStrip(entries, start, height = 44.dp, tall = 26.dp, short = 9.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Today", Modifier.weight(1f))
                        Label("${today.count { it.fate == Fate.BUZZED }} buzzed", color = a.accent)
                        HGap(6)
                        Label("· ${today.count { it.fate == Fate.IGNORED }} ignored")
                        HGap(6)
                        GlyphIcon(Glyph.CHEVRON, 14.dp, a.muted, 2.4f)
                    }
                }
            } }

            val who = when (s.callMode) {
                CallMode.CHOSEN -> summarize(s.people.map { it.name }, "nobody yet")
                CallMode.CONTACTS -> "my contacts"
                CallMode.ANYONE -> "anyone"
            }
            val one = s.callMode == CallMode.ANYONE || (s.callMode == CallMode.CHOSEN && s.people.size <= 1)
            val names = s.apps.map { pkg -> apps.firstOrNull { it.pkg == pkg }?.label ?: pkg }.sorted()
            Group(listOf(
                { RuleItem("Calls", "Rings until answered") {
                    Sentence(listOf(
                        Part.Words("When"),
                        Part.Blank("Who can ring", who, open = sheet == SheetKind.PEOPLE) { open(SheetKind.PEOPLE) },
                        Part.Words((if (one) "calls" else "call") + " from any app, ring my Air."),
                    ))
                } },
                { RuleItem("Messages", "Max 1 buzz per 15 s") {
                    Sentence(listOf(
                        Part.Words("When"),
                        Part.Blank("Apps that tap", summarizeApps(names), open = sheet == SheetKind.APPS) { open(SheetKind.APPS) },
                        Part.Words(if (names.size == 1) "messages, tap" else "message, tap"),
                        Part.Blank("Taps per message", tapsWord(s.messageTaps), after = ".", open = sheet == SheetKind.PATTERN) { open(SheetKind.PATTERN) },
                    ))
                } },
                { RuleItem("Quiet", "Chosen callers still ring") {
                    Sentence(listOf(
                        Part.Words("Stay quiet"),
                        Part.Blank("When to stay quiet", summarizeQuiet(quietRules(s)), after = ".", open = sheet == SheetKind.QUIET) { open(SheetKind.QUIET) },
                    ))
                } },
            ))

            Spacer(Modifier.weight(1f))
            val recent = entries.filter { it.kind != Kind.TEST }.takeLast(3).reversed()
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open Activity") { go(Screen.ACTIVITY) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (recent.isEmpty()) Text("Nothing yet. Calls and messages appear here with what AirBuzz did.", style = Type.small, color = a.muted)
                // Rows there when Home opens stand still; a new one opens its height and fades in at the top.
                val seen = remember { recent.map { it.time }.toSet() }
                recent.forEach { e ->
                    key(e.time) {
                        val shown = remember { MutableTransitionState(e.time in seen).apply { targetState = true } }
                        AnimatedVisibility(shown, enter = expandVertically(tween(240, easing = Motion.InOut), Alignment.Top) + fadeIn(tween(240, easing = Motion.Out)),
                            exit = ExitTransition.None) { HistoryRow(e) }
                    }
                }
            }
            // Room for the shelf: its 68 dp and 16 dp margin, plus the usual 12 dp gap.
            Spacer(Modifier.height(64.dp))
        }
        ShelfFor(engine, s, go)
    }
}

@Composable
fun RuleItem(label: String, caption: String, sentence: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabelRow(label) { Label(caption) }
        sentence()
    }
}

/** While paused, Home leads with when the pause ends (decision 20). */
@Composable
fun PausedCard(s: AirSettings, now: Long, onResume: () -> Unit, onClick: () -> Unit) {
    val a = LocalAirColors.current
    Card(gap = 6.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.PAUSE, 14.dp, a.muted, 2.4f)
            HGap(6)
            val left = (s.pausedUntil - now).coerceAtLeast(0) / 60_000
            Label(if (left >= 60) "Paused · ${left / 60} h ${left % 60} min left" else "Paused · $left min left")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Until ${timeOf(s.pausedUntil)}", style = Type.title, color = a.ink, modifier = Modifier.weight(1f))
            HGap(12)
            Pill("Resume", onResume, small = true)
        }
        Text(
            if (s.people.isEmpty()) "Nobody can ring your Air until then." else "Only ${summarize(s.people.map { it.name }, "")} can ring your Air.",
            style = Type.small, color = a.muted,
        )
    }
}

fun summarizeApps(names: List<String>) = when (names.size) {
    0 -> "no apps yet"
    1, 2, 3 -> names.joinToString(", ")
    else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
}

fun summarizeQuiet(rules: List<String>) = when (rules.size) {
    0 -> "never"
    1 -> rules[0]
    else -> "${rules[0]} and ${rules.size - 1} more"
}

/** One problem on the Air shelf and its one fix (principle 5); a null [fix] opens Setup. */
data class Problem(val title: String, val detail: String, val action: String, val icon: Curve, val fix: Fix?)

/** The first thing stopping AirBuzz, in the order it must be fixed. */
fun firstProblem(context: android.content.Context, lastError: String?, reached: Long?): Problem? = when {
    !Setup.bluetoothOn(context) -> Problem("Bluetooth is off", "Your Air needs it", "Turn on", Curve.BLUETOOTH_OFF, Fix.PAIR)
    !Setup.hasNearby(context) -> Problem("Can't use Bluetooth", "Nearby devices is off", "Allow", Curve.BLUETOOTH_OFF, Fix.NEARBY)
    Setup.bondedAirAddress(context) == null -> Problem("No Air paired", "Pair it in Google Health", "Set up", Curve.BLUETOOTH_OFF, null)
    !Setup.hasListener(context) -> Problem("Can't see alerts", "Notification access is off", "Allow", Curve.BELL_OFF, Fix.LISTENER)
    lastError != null -> Problem("Air out of reach", reached?.let { "Last reached ${timeOf(it)}" } ?: "Not reached yet", "Fix", Curve.SIGNAL_OFF, Fix.TEST)
    else -> null
}

/**
 * The Air shelf and the fade under it, at the bottom of Home and, as Settings' last row, at the same spot in Settings
 * (Anchor, decision 40). On Home it opens Settings; in Settings it ends in Test and its glow fades out when [glowFrom]
 * is 1.
 */
@Composable
fun BoxScope.ShelfFor(engine: Engine, s: AirSettings, go: (Screen) -> Unit, inSettings: Boolean = false, glowFrom: Float = 1f) {
    val context = LocalContext.current
    val a = LocalAirColors.current
    val band by engine.state.collectAsStateWithLifecycle()
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val now = rememberNow()
    val reached = lastReached(band.lastReachedAt, entries)
    val problem = remember(tick, band.lastError, reached) { firstProblem(context, band.lastError, reached) }
    val fixer = rememberFixer(go) {}
    val paused = s.enabled && s.pausedUntil > now
    val live = s.enabled && !paused && reached != null
    val glow = remember { Animatable(if (inSettings) glowFrom else 1f) }
    if (inSettings) LaunchedEffect(Unit) { glow.animateTo(0f, tween(Motion.ANCHOR, easing = Motion.Out)) }
    // The list fades out under the shelf.
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp)
        .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to a.bg)))
    AirShelf(
        problem.takeIf { s.enabled }, live,
        when { reached == null -> "Not reached yet"; live && !inSettings -> "Connected · reached ${timeOf(reached)}"; else -> "Reached ${timeOf(reached)}" },
        Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
        glow = glow.value,
        trailing = if (inSettings) ({ Pill("Test", { go(Screen.TEST) }, filled = false, small = true) }) else ({ CurveIcon(Curve.TUNE, 22.dp, a.ink, 1.8f) }),
        onFix = { p -> p.fix?.let(fixer) ?: go(Screen.SETUP) },
        onClick = if (inSettings) null else ({ go(Screen.SETTINGS) }),
    )
}

/**
 * The card floating at the bottom of Home (decision 33): it opens Settings, and carries the one problem when something
 * breaks, at the same height. The dot is green only while AirBuzz is on and has reached the Air. Light mode casts a
 * neutral shadow; dark mode glows in the accent, or in warn for a problem. When a problem arrives, its icon and words
 * cross-fade in, the border turns warn, and the glow flares once; it never pulses (decision 40).
 */
@Composable
fun AirShelf(problem: Problem?, live: Boolean, reached: String, modifier: Modifier = Modifier, glow: Float = 1f,
             trailing: @Composable () -> Unit, onFix: (Problem) -> Unit, onClick: (() -> Unit)?) {
    val a = LocalAirColors.current
    val shape = RoundedCornerShape(24.dp)
    val swap = tween<Color>(220, easing = Motion.Out)
    val border by animateColorAsState(if (problem != null) a.warn else a.line, swap, label = "border")
    val tint by animateColorAsState(if (problem != null) a.warn else a.accent, swap, label = "glow")
    // A one-time flare when a problem arrives, not when it clears or when Home first shows.
    val flare = remember { Animatable(0f) }
    var had by remember { mutableStateOf(problem != null) }
    LaunchedEffect(problem != null) {
        if (problem != null && !had) {
            flare.animateTo(1f, tween(270, easing = Motion.Out))
            flare.animateTo(0f, tween(630, easing = Motion.Out))
        }
        had = problem != null
    }
    val shades = if (a.dark) listOfNotNull(
        Shade(0.dp, 14.dp, tint.copy(alpha = 0.5f * glow)),
        Shade(0.dp, 24.dp, a.warn.copy(alpha = 0.55f * flare.value)).takeIf { flare.value > 0f },
        Shade(4.dp, 12.dp, Color.Black.copy(alpha = 0.6f)),
    ) else listOfNotNull(
        Shade(6.dp, 32.dp, Color(0x33121418).copy(alpha = 0.2f * (0.4f + 0.6f * glow))),
        Shade(0.dp, 20.dp, a.warn.copy(alpha = 0.45f * flare.value)).takeIf { flare.value > 0f },
        Shade(1.dp, 4.dp, Color(0x1A121418)),
    )
    Box(
        modifier.fillMaxWidth().softShadow(24.dp, a.surface, shades).clip(shape).background(a.surface)
            .border(if (problem != null) 1.5.dp else 1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Open Settings", onClick = onClick) else Modifier)
            .heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val rise = with(LocalDensity.current) { 8.dp.roundToPx() }
        AnimatedContent(problem, contentKey = { it?.title }, label = "shelf", transitionSpec = {
            (fadeIn(tween(220, easing = Motion.Out)) + slideInVertically(tween(220, easing = Motion.Out)) { rise }) togetherWith
                (fadeOut(tween(150, easing = Motion.In)) + slideOutVertically(tween(150, easing = Motion.In)) { -rise })
        }) { shown ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shown != null) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(a.warnTint), contentAlignment = Alignment.Center) {
                        CurveIcon(shown.icon, 22.dp, a.warn, 1.8f)
                    }
                } else {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        AirPebble(36.dp)
                        // A 10 dp green dot with a 2 dp ring of the card colour.
                        Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = 4.dp).size(14.dp).clip(CircleShape).background(a.surface),
                            contentAlignment = Alignment.Center) { Dot(if (live) a.ok else a.muted, 10.dp) }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(shown?.title ?: "Your Air", style = Type.bodyMedium, color = a.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // 16 sp lines, as the mono label has, so a problem keeps the shelf's height.
                    if (shown != null) Text(shown.detail, style = Type.small.copy(lineHeight = 16.sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)), color = a.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Label(reached)
                }
                if (shown != null) Pill(shown.action, { onFix(shown) }, small = true) else trailing()
            }
        }
    }
}

@Composable
fun HistoryRow(e: Entry) {
    val a = LocalAirColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(timeOf(e.time), Modifier.widthIn(min = 44.dp))
        HGap(10)
        BarMark(e.fate)
        HGap(10)
        Text(describe(e), style = Type.body, color = if (e.fate == Fate.IGNORED) a.muted else a.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun describe(e: Entry): String {
    val who = e.who ?: e.appLabel
    return when {
        e.kind == Kind.TEST -> if (e.result == com.davort.airbuzz.data.Result.BUZZED) "Test buzz · ${seconds(e.latencyMs)}" else "Test buzz · failed"
        e.kind == Kind.CALL && e.fate == Fate.BUZZED -> "$who called · " + (e.rangMs?.let { "rang ${it / 1000} s" } ?: "ringing")
        e.kind == Kind.CALL && e.fate == Fate.FAILED -> "$who called · couldn't reach your Air"
        e.kind == Kind.CALL -> "$who called · ${reasonLabel(e).lowercase()}"
        e.fate == Fate.BUZZED -> if (e.count > 1) "$who · ${e.count} messages, 1 buzz" else "$who · ${e.appLabel}"
        e.fate == Fate.FAILED -> "$who · couldn't reach your Air"
        else -> "${e.appLabel} · ${reasonLabel(e).lowercase()}"
    }
}

/** Why an alert was ignored, in the words the screens use. */
fun reasonLabel(e: Entry): String = when (e.reason) {
    null -> if (e.result == com.davort.airbuzz.data.Result.MERGED) "Within 15 s" else "Ignored"
    "not chosen" -> "Not chosen"
    "quiet hours" -> "At night"
    "phone in use" -> "Phone in use"
    "paused" -> "Paused"
    "AirBuzz is off" -> "AirBuzz off"
    else -> e.reason.replaceFirstChar { it.uppercase() }
}

fun seconds(ms: Long?) = ms?.let { String.format(java.util.Locale.ROOT, "%.1f s", it / 1000.0) } ?: "–"

/** Adds a person picked in the system contact picker, which grants access to that one number only. */
@Composable
fun rememberContactPicker(engine: Engine): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: return@use
                val number = c.getString(1)?.let { Rules.normalize(it, regionOf(context)) }
                engine.store.update { s -> if (s.people.any { it.name == name && it.number == number }) s else s.copy(people = s.people + Person(name, number)) }
            }
        }
    }
    return { launcher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
}

fun regionOf(context: android.content.Context): String {
    val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
    return listOf(tm?.networkCountryIso, tm?.simCountryIso, java.util.Locale.getDefault().country).firstOrNull { !it.isNullOrBlank() } ?: "SE"
}

fun openSettings(context: android.content.Context, intent: Intent) {
    try { context.startActivity(intent) } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/** Requests that need an Activity: runtime permissions and the companion device dialog. */
@Composable
fun rememberFixer(go: (Screen) -> Unit, onError: (String) -> Unit): (Fix) -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    return { fix ->
        when (fix) {
            Fix.NEARBY -> if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            Fix.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            Fix.LISTENER -> openSettings(context, Setup.listenerSettings(context))
            Fix.PAIR -> openSettings(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            Fix.AUTOSTART -> openSettings(context, Setup.autostartSettings(context))
            Fix.TEST -> go(Screen.TEST)
            Fix.COMPANION -> (context as? ComponentActivity)?.let { activity ->
                Setup.associate(activity, { sender.launch(IntentSenderRequest.Builder(it).build()) }, onError)
            }
        }
    }
}
