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

/** Settings and the pages reached from it live in the sheet that rises from the shelf (decision 40). */
private val Screen.inSheet get() = this == Screen.SETTINGS || this == Screen.APPEARANCE || this == Screen.SETUP || this == Screen.TEST

/** How deep a page sits below Home, which decides whether a move to it slides forward or back. */
private val Screen.depth get() = when (this) {
    Screen.HOME -> 0
    Screen.SETTINGS, Screen.ACTIVITY -> 1
    Screen.APPEARANCE, Screen.SETUP, Screen.TEST -> 2
}

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
    var sheet by rememberSaveable { mutableStateOf<SheetKind?>(null) }
    val go: (Screen) -> Unit = { screen = it }
    BackHandler(screen != Screen.HOME) {
        go(if (screen == Screen.APPEARANCE || screen == Screen.SETUP) Screen.SETTINGS else Screen.HOME)
    }
    val open: (SheetKind) -> Unit = { sheet = it }
    val context = LocalContext.current
    val resumed = rememberResumeTick()
    LaunchedEffect(resumed) { AppList.refresh(context.applicationContext) }
    val inSheet = screen.inSheet
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (!settings.onboarded) {
            OnboardingScreen(engine, settings) { sheet = SheetKind.APPS }
            return@Box
        }
        // Home and Activity are pages: a deeper page slides in from the right, Back slides it away (decision 40).
        AnimatedContent(if (inSheet) Screen.HOME else screen, label = "page", transitionSpec = { pageChange(targetState.depth > initialState.depth) }) { shown ->
            when (shown) {
                Screen.ACTIVITY -> ActivityScreen(engine, open) { go(Screen.HOME) }
                else -> Home(engine, settings, sheet, go, open)
            }
        }
    }
    // Settings rises from the shelf as a sheet over Home; Appearance, the setup checklist and Test change like pages
    // inside it. While it sinks it keeps showing the page it was on.
    val sheetPage = remember { arrayOf(Screen.SETTINGS) }
    if (inSheet) sheetPage[0] = screen
    SheetLayer(inSheet && settings.onboarded, onDismiss = { go(Screen.HOME) }, full = true) {
        AnimatedContent(sheetPage[0], Modifier.weight(1f), label = "sheet page", transitionSpec = { pageChange(targetState.depth > initialState.depth) }) { shown ->
            when (shown) {
                Screen.APPEARANCE -> AppearanceScreen(engine, settings) { go(Screen.SETTINGS) }
                Screen.TEST -> TestBuzzScreen(engine) { go(Screen.HOME) }
                Screen.SETUP -> SetupScreen(engine, go) { go(Screen.SETTINGS) }
                else -> SettingsScreen(engine, settings, go)
            }
        }
    }
    // The pickers, over whichever page opened them. Back closes the picker before it moves a page.
    val lastSheet = remember { arrayOfNulls<SheetKind>(1) }
    sheet?.let { lastSheet[0] = it }
    BackHandler(sheet != null) { sheet = null }
    SheetLayer(sheet != null, onDismiss = { sheet = null }) {
        val close = { sheet = null }
        when (lastSheet[0]) {
            SheetKind.PEOPLE -> PeopleSheet(engine, settings, close) { sheet = SheetKind.QUIET }
            SheetKind.APPS -> AppsSheet(engine, settings, close)
            SheetKind.PATTERN -> PatternSheet(engine, settings, close)
            SheetKind.QUIET -> QuietSheet(engine, settings, close)
            SheetKind.PAUSE -> PauseSheet(engine, settings, close)
            null -> {}
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

/** Who rings through quiet times and a pause, such as "Bruna" or "Bruna and Mum", or null when nobody does. */
fun ringsAnyway(s: AirSettings): String? =
    if (s.ringAnyway && s.ringAnywayPeople.isNotEmpty()) summarize(s.ringAnywayPeople.map { it.name }, "") else null

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
    val now = rememberNow()
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
                            1 -> { GlyphIcon(Glyph.PAUSE, 16.dp, a.muted, 2.4f); HGap(4); Text("Paused", style = Type.status, color = a.muted) }
                            0 -> { Dot(a.ok); HGap(6); Text("On", style = Type.status, color = a.ink) }
                            else -> { Dot(a.muted); HGap(6); Text("Off", style = Type.status, color = a.muted) }
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
                CallMode.CHOSEN -> nameLadder(s.people.map { it.name }, "nobody yet", "person", "people")
                CallMode.CONTACTS -> listOf("my contacts")
                CallMode.ANYONE -> listOf("anyone")
            }
            val one = s.callMode == CallMode.ANYONE || (s.callMode == CallMode.CHOSEN && s.people.size <= 1)
            val names = remember(s.apps) { s.apps.map { appLabel(context, it) }.sorted() }
            val apps = nameLadder(names, "no apps yet", "app", "apps")
            Group(listOf(
                { RuleItem("Calls", "Rings until answered") {
                    Sentence(listOf(
                        Part.Words("When"),
                        Part.Blank("Who can ring", who[0], open = sheet == SheetKind.PEOPLE, shorter = who.drop(1)) { open(SheetKind.PEOPLE) },
                        Part.Words((if (one) "calls" else "call") + " from any app, ring my Air."),
                    ))
                } },
                { RuleItem("Messages", "Max 1 buzz per 15 s") {
                    Sentence(listOf(
                        Part.Words("When"),
                        Part.Blank("Apps that tap", apps[0], open = sheet == SheetKind.APPS, shorter = apps.drop(1)) { open(SheetKind.APPS) },
                        Part.Words(if (names.size == 1) "messages, tap" else "message, tap"),
                        Part.Blank("Taps per message", tapsWord(s.messageTaps), after = ".", open = sheet == SheetKind.PATTERN) { open(SheetKind.PATTERN) },
                    ))
                } },
                { RuleItem("Quiet", if (ringsAnyway(s) == null) "Calls too" else s.ringAnywayPeople.size.let { n -> if (n == 1) "1 person rings anyway" else "$n people ring anyway" }) {
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
        // The shelf floats over the list, which fades out under it.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to a.bg)))
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
            ringsAnyway(s)?.let { "Only $it can ring your Air until then." } ?: "Nothing buzzes until then.",
            style = Type.small, color = a.muted,
        )
    }
}

/**
 * Every way to name [names] in a blank, longest first: all of them, then fewer whole names and "and N more", then
 * only the count, such as "5 apps". The blank shows the first that fits, so a long app name is never cut.
 */
fun nameLadder(names: List<String>, none: String, one: String, many: String): List<String> {
    if (names.isEmpty()) return listOf(none)
    fun list(k: Int) = if (k == names.size) {
        if (k == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
    } else names.take(k).joinToString(", ") + " and ${names.size - k} more"
    return (names.size downTo 1).map(::list) + "${names.size} ${if (names.size == 1) one else many}"
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

/** The Air shelf as Home shows it, at the bottom of a full-screen box. */
@Composable
fun BoxScope.ShelfFor(engine: Engine, s: AirSettings, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val band by engine.state.collectAsStateWithLifecycle()
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val now = rememberNow()
    val reached = lastReached(band.lastReachedAt, entries)
    val problem = remember(tick, band.lastError, reached) { firstProblem(context, band.lastError, reached) }
    val fixer = rememberFixer(go) {}
    val paused = s.enabled && s.pausedUntil > now
    val live = s.enabled && !paused && reached != null
    // Once the problem has cost an alert, the detail says how many (issue #3); the day joins the time after midnight.
    val missed = band.problemSince?.takeIf { band.missed > 0 }?.let { since ->
        val day = if (since < dayStart(now = now)) java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(since)) + " " else ""
        "${band.missed} missed since $day${timeOf(since)}"
    }
    AirShelf(
        problem.takeIf { s.enabled }, live,
        if (reached == null) "Not reached yet" else if (live) "Connected · reached ${timeOf(reached)}" else "Reached ${timeOf(reached)}",
        missed,
        Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
        onFix = { p -> p.fix?.let(fixer) ?: go(Screen.SETUP) },
    ) { go(Screen.SETTINGS) }
}

/**
 * The card floating at the bottom of Home (decision 33): it opens Settings, and carries the one problem when something
 * breaks, at the same height. The dot is green only while AirBuzz is on and has reached the Air. Light mode casts a
 * neutral shadow; dark mode glows in the accent, or in warn for a problem. When a problem arrives, its icon and words
 * cross-fade in, the border turns warn, and the glow flares once; it never pulses (decision 40).
 */
@Composable
fun AirShelf(problem: Problem?, live: Boolean, reached: String, missed: String? = null, modifier: Modifier = Modifier, onFix: (Problem) -> Unit, onClick: () -> Unit) {
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
        Shade(0.dp, 14.dp, tint.copy(alpha = 0.5f)),
        Shade(0.dp, 24.dp, a.warn.copy(alpha = 0.55f * flare.value)).takeIf { flare.value > 0f },
        Shade(4.dp, 12.dp, Color.Black.copy(alpha = 0.6f)),
    ) else listOfNotNull(
        Shade(6.dp, 32.dp, Color(0x33121418)),
        Shade(0.dp, 20.dp, a.warn.copy(alpha = 0.45f * flare.value)).takeIf { flare.value > 0f },
        Shade(1.dp, 4.dp, Color(0x1A121418)),
    )
    Box(
        modifier.fillMaxWidth().softShadow(24.dp, a.surface, shades).clip(shape).background(a.surface)
            .border(if (problem != null) 1.5.dp else 1.dp, border, shape)
            .clickable(onClickLabel = "Open Settings", onClick = onClick)
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
                    if (shown != null && missed != null) Label(missed, color = a.warn)
                    else if (shown != null) Text(shown.detail, style = Type.small.copy(lineHeight = 16.sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)), color = a.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Label(reached)
                }
                if (shown != null) Pill(shown.action, { onFix(shown) }, small = true) else CurveIcon(Curve.TUNE, 22.dp, a.ink, 1.8f)
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
fun rememberContactPicker(engine: Engine, ringAnyway: Boolean = false): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: return@use
                val number = c.getString(1)?.let { Rules.normalize(it, regionOf(context)) }
                val p = Person(name, number)
                engine.store.update { s ->
                    if (ringAnyway) { if (p in s.ringAnywayPeople) s else s.copy(ringAnywayPeople = s.ringAnywayPeople + p) }
                    else if (p in s.people) s else s.copy(people = s.people + p)
                }
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
