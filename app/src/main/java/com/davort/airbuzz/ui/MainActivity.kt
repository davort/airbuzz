package com.davort.airbuzz.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animateFloatAsState
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

enum class Screen { HOME, SETTINGS, APPEARANCE, TEST, ACTIVITY, SETUP, ABOUT, LICENCES, LICENCE }

/** Settings and the pages reached from it live in the sheet that rises from the shelf (decision 40). */
private val Screen.inSheet get() = this != Screen.HOME && this != Screen.ACTIVITY

/** How deep a page sits below Home, which decides whether a move to it slides forward or back. */
private val Screen.depth get() = when (this) {
    Screen.HOME -> 0
    Screen.SETTINGS, Screen.ACTIVITY -> 1
    Screen.APPEARANCE, Screen.SETUP, Screen.TEST, Screen.ABOUT -> 2
    Screen.LICENCES -> 3
    Screen.LICENCE -> 4
}

private val ENTERED_FROM_SEVERAL = setOf(Screen.TEST, Screen.SETUP)

/** Where Back goes from each page, unless it remembers the page that opened it. */
private val Screen.parent get() = when (this) {
    Screen.APPEARANCE, Screen.SETUP, Screen.ABOUT -> Screen.SETTINGS
    Screen.LICENCES -> Screen.ABOUT
    Screen.LICENCE -> Screen.LICENCES
    else -> Screen.HOME
}

enum class SheetKind { PEOPLE, APPS, PATTERN, QUIET, PAUSE, CHATS }

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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun App(engine: Engine, settings: AirSettings) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var sheet by rememberSaveable { mutableStateOf<SheetKind?>(null) }
    // The app whose chats the chat sheet shows, and whether Apps that buzz opened it, so Done goes back there.
    var chatPkg by rememberSaveable { mutableStateOf("") }
    var chatsFromApps by rememberSaveable { mutableStateOf(false) }
    val openChats: (String, Boolean) -> Unit = { pkg, fromApps -> chatPkg = pkg; chatsFromApps = fromApps; sheet = SheetKind.CHATS }
    var licence by rememberSaveable { mutableIntStateOf(0) }
    // Test buzz and the setup checklist each have more than one way in, so Back returns to the page that opened them,
    // such as Settings rather than Home (issue #10). Activity opens only from Home.
    var from by rememberSaveable { mutableStateOf(mapOf<Screen, Screen>()) }
    val go: (Screen) -> Unit = { to ->
        if (to in ENTERED_FROM_SEVERAL && to != screen) from = from + (to to screen)
        screen = to
    }
    val back: () -> Unit = { screen = from[screen] ?: screen.parent }
    BackHandler(screen != Screen.HOME) { back() }
    val open: (SheetKind) -> Unit = { sheet = it }
    val context = LocalContext.current
    val resumed = rememberResumeTick()
    LaunchedEffect(resumed) { AppList.refresh(context.applicationContext) }
    // 0.8.0 and 0.8.1 could keep an app with no chats ticked, which blocked it; no ticks now means all messages.
    LaunchedEffect(Unit) { engine.store.update { s -> if (s.chats.values.any { it.isEmpty() }) s.copy(chats = s.chats.filterValues { it.isNotEmpty() }) else s } }
    val inSheet = screen.inSheet
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (!settings.onboarded) {
            OnboardingScreen(engine, settings) { sheet = SheetKind.APPS }
            return@Box
        }
        // Home and Activity are pages; the day's graph moves between them and the rest fades (see graphPageChange).
        SharedTransitionLayout {
            AnimatedContent(if (inSheet) Screen.HOME else screen, label = "page", transitionSpec = { graphPageChange(targetState.depth > initialState.depth) }) { shown ->
                CompositionLocalProvider(LocalPageShare provides PageShare(this@SharedTransitionLayout, this, screen == Screen.ACTIVITY)) {
                    when (shown) {
                        Screen.ACTIVITY -> ActivityScreen(engine, open, { openChats(it, false) }, back)
                        else -> Home(engine, settings, sheet, go, open)
                    }
                }
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
                Screen.TEST -> TestBuzzScreen(engine, back)
                Screen.SETUP -> SetupScreen(engine, go, back)
                Screen.ABOUT -> AboutScreen(go) { go(Screen.SETTINGS) }
                Screen.LICENCES -> LicencesScreen({ licence = it; go(Screen.LICENCE) }) { go(Screen.ABOUT) }
                Screen.LICENCE -> LicenceScreen(Licences[licence]) { go(Screen.LICENCES) }
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
            SheetKind.APPS -> AppsSheet(engine, settings, close) { openChats(it, true) }
            SheetKind.PATTERN -> PatternSheet(engine, settings, close)
            SheetKind.QUIET -> QuietSheet(engine, settings, close)
            SheetKind.PAUSE -> PauseSheet(engine, settings, close)
            SheetKind.CHATS -> ChatsSheet(engine, settings, chatPkg) { sheet = if (chatsFromApps) SheetKind.APPS else null }
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

/** Whether [p] is one of the chosen callers, by number when it has one, as [Rules.decideCall] matches them. */
fun isChosen(s: AirSettings, p: Person): Boolean = s.people.any { it == p || (p.number != null && it.number == p.number) }

/** The ring-anyway people who can ring at all: in Chosen, only those who are chosen too (Davor, 18 Nov). */
fun ringingAnyway(s: AirSettings): List<Person> = when {
    !s.ringAnyway -> emptyList()
    s.callMode == CallMode.CHOSEN -> s.ringAnywayPeople.filter { isChosen(s, it) }
    else -> s.ringAnywayPeople
}

/** Who rings through quiet times and a pause, such as "Bruna" or "Bruna and Mum", or null when nobody does. */
fun ringsAnyway(s: AirSettings): String? = ringingAnyway(s).takeIf { it.isNotEmpty() }?.let { summarize(it.map { p -> p.name }, "") }

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
                Text("AirBuzz", style = Type.heading, color = a.ink, modifier = Modifier.weight(1f).semantics { heading() })
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
                // Pressing the day tints a panel just outside it in the pressed accent and shrinks it to 97 %, without a
                // ripple, firm enough to read as a tap (Davor, 15 Nov). The › after "Today" nudges right twice on the
                // first Home that shows it, once ever, to hint that the line opens Activity.
                // Home scrolls, so Compose reports a quick tap's press only as the finger lifts, and Activity then covers
                // Home at once. So the pressed look holds for at least 180 ms, and Activity opens 100 ms after the tap,
                // once the tint has come in.
                val scope = rememberCoroutineScope()
                val interaction = remember { MutableInteractionSource() }
                val pressed = rememberHeldPress(interaction)
                val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(if (pressed) 100 else 200, easing = Motion.Out), label = "press")
                val panel by animateColorAsState(if (pressed) a.tintStrong else a.tintStrong.copy(alpha = 0f), tween(if (pressed) 100 else 200), label = "panel")
                val nudge = remember { Animatable(0f) }
                if (!s.activityHinted) LaunchedEffect(Unit) {
                    delay(1_000)
                    repeat(2) {
                        nudge.animateTo(6f, tween(225, easing = Motion.Out))
                        nudge.animateTo(0f, tween(225, easing = Motion.InOut))
                    }
                    engine.store.update { it.copy(activityHinted = true) }
                }
                Column(Modifier.fillMaxWidth()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .drawBehind {
                        val x = 10.dp.toPx(); val y = 6.dp.toPx()
                        drawRoundRect(panel, Offset(-x, -y), Size(size.width + 2 * x, size.height + 2 * y), CornerRadius(Radius.NOTE.toPx()))
                    }
                    .clickable(interaction, indication = null, onClickLabel = "Open Activity") { scope.launch { delay(100); go(Screen.ACTIVITY) } },
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BuzzStrip(entries, start, height = 44.dp, tall = 26.dp, short = 9.dp, line = Modifier.todayLine())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Today")
                        HGap(4)
                        GlyphIcon(Glyph.CHEVRON, 12.dp, a.ink, 3f, Modifier.graphicsLayer { translationX = nudge.value.dp.toPx() })
                        Spacer(Modifier.weight(1f))
                        Label("${today.count { it.fate == Fate.BUZZED }} buzzed", color = a.accent)
                        HGap(6)
                        Label("· ${today.count { it.fate == Fate.IGNORED }} ignored")
                    }
                }
            } }

            val who = when (s.callMode) {
                CallMode.CHOSEN -> nameLadder(s.people.map { it.name }, "nobody yet", "person", "people")
                CallMode.CONTACTS -> listOf("my contacts")
                CallMode.ANYONE -> listOf("anyone")
            }
            val one = s.callMode == CallMode.ANYONE || (s.callMode == CallMode.CHOSEN && s.people.size <= 1)
            // An app limited to some chats reads "3 WhatsApp chats" (#33).
            val names = remember(s.apps, s.chats) {
                s.apps.map { appLabel(context, it) to s.chats[it] }.sortedBy { it.first }.map { (label, chats) -> appInSentence(label, chats) }
            }
            val apps = nameLadder(names, "no apps yet", "app", "apps")
            val oneSender = s.apps.size == 1 && s.chats[s.apps.first()].let { it == null || it.size == 1 }
            Group(listOf(
                { RuleItem("Calls", "Rings until answered") {
                    Sentence(listOf(
                        Part.Words("When"),
                        Part.Blank("People who ring", who[0], open = sheet == SheetKind.PEOPLE, shorter = who.drop(1)) { open(SheetKind.PEOPLE) },
                        Part.Words((if (one) "calls" else "call") + " from any app, ring my Fitbit Air."),
                    ))
                } },
                { RuleItem("Messages", "Max 1 buzz per 15 s") {
                    Sentence(listOf(
                        Part.Words("When"),
                        Part.Blank("Apps that buzz", apps[0], open = sheet == SheetKind.APPS, shorter = apps.drop(1)) { open(SheetKind.APPS) },
                        Part.Words(if (oneSender) "messages, buzz" else "message, buzz"),
                        Part.Blank("Buzzes per message", tapsWord(s.messageTaps), after = ".", open = sheet == SheetKind.PATTERN) { open(SheetKind.PATTERN) },
                    ))
                } },
                { RuleItem("Quiet", if (ringsAnyway(s) == null) "Calls too" else ringingAnyway(s).size.let { n -> if (n == 1) "1 person rings anyway" else "$n people ring anyway" }) {
                    Sentence(listOf(
                        Part.Words("Stay quiet"),
                        Part.Blank("When to stay quiet", summarizeQuiet(quietRules(s)), after = ".", open = sheet == SheetKind.QUIET) { open(SheetKind.QUIET) },
                    ))
                } },
            ))

            Spacer(Modifier.weight(1f))
            val recent = entries.filter { it.kind != Kind.TEST }.takeLast(3).reversed()
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open Activity") { go(Screen.ACTIVITY) }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            ringsAnyway(s)?.let { "Only $it can ring your Fitbit Air until then." } ?: "Nothing buzzes until then.",
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

/**
 * Debug builds only: `store/screenshots.sh` creates `files/store-demo`, so Home shows a working Fitbit Air on an emulator
 * that has none. Release builds never look.
 */
fun storeDemo(context: android.content.Context) =
    context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        java.io.File(context.filesDir, "store-demo").exists()

/** The first thing stopping AirBuzz, in the order it must be fixed. */
fun firstProblem(context: android.content.Context, lastError: String?, notAnswering: Boolean, reached: Long?, heard: Long? = null): Problem? = when {
    storeDemo(context) -> null
    !Setup.bluetoothOn(context) -> Problem("Bluetooth is off", "Your Fitbit Air needs it", "Turn on", Curve.BLUETOOTH_OFF, Fix.PAIR)
    !Setup.hasNearby(context) -> Problem("Can’t use Bluetooth", "Nearby devices is off", "Allow", Curve.BLUETOOTH_OFF, Fix.NEARBY)
    Setup.bondedAirAddress(context) == null -> Problem("No Fitbit Air paired", "Pair it in Google Health", "Set up", Curve.BLUETOOTH_OFF, null)
    !Setup.hasListener(context) -> Problem("Can’t see alerts", "Notification access is off", "Allow", Curve.BELL_OFF, Fix.LISTENER)
    lastError != null && notAnswering -> Problem("Fitbit Air not answering", heard?.let { "Last heard ${timeOf(it)}" } ?: "Not heard yet", "Fix", Curve.SIGNAL_OFF, Fix.TEST)
    lastError != null -> Problem("Fitbit Air out of reach", reached?.let { "Last reached ${timeOf(it)}" } ?: "Not reached yet", "Fix", Curve.SIGNAL_OFF, Fix.TEST)
    else -> null
}

/**
 * The shelf's line when nothing is wrong (issue #32). "Connected" means the open link is up, and "checked" is when the
 * Air was last heard on it, Google Health's traffic included, about every 15 min. The engine raises "not answering"
 * after 20 min of silence and "out of reach" after 10 min without the link, and those problems replace this line.
 * The last buzz lives in Settings, where it doesn't read as the last contact.
 */
fun shelfLabel(enabled: Boolean, linkOpen: Boolean, heard: Long?, reached: Long?): String = when {
    !Engine.OPEN_LINK || !enabled -> reached?.let { "Last buzz ${timeOf(it)}" } ?: "Not reached yet"
    !linkOpen -> "Connecting…"
    heard == null -> "Connected"
    else -> "Connected · checked ${timeOf(heard)}"
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
    val problem = remember(tick, band.lastError, band.notAnswering, reached, band.lastHeardAt) {
        firstProblem(context, band.lastError, band.notAnswering, reached, band.lastHeardAt)
    }
    val fixer = rememberFixer(go) {}
    val paused = s.enabled && s.pausedUntil > now
    val live = s.enabled && !paused && if (Engine.OPEN_LINK) band.linkOpen else reached != null
    // Once the problem has cost an alert, the detail says how many (issue #3); the day joins the time after midnight.
    val missed = band.problemSince?.takeIf { band.missed > 0 }?.let { since ->
        val day = if (since < dayStart(now = now)) java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(since)) + " " else ""
        "${band.missed} missed since $day${timeOf(since)}"
    }
    AirShelf(
        problem.takeIf { s.enabled }, live,
        // Google Health's sync is information, not a problem (decision 33), so any problem wins over it.
        band.healthSyncing && live,
        shelfLabel(s.enabled, band.linkOpen, band.lastHeardAt, reached),
        missed,
        Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
        onFix = { p -> p.fix?.let(fixer) ?: go(Screen.SETUP) },
    ) { go(Screen.SETTINGS) }
}

/**
 * The card floating at the bottom of Home (decision 33): it opens Settings, and carries the one problem when something
 * breaks, at the same height. The dot is green only while AirBuzz is on and its link to the Air is up. Light mode casts a
 * neutral shadow; dark mode glows in the accent, or in warn for a problem. When a problem arrives, its icon and words
 * cross-fade in, the border turns warn, and the glow flares once; it never pulses (decision 40). While Google Health
 * syncs with the Air and nothing is wrong, a grey sync icon says buzzes may wait, with no warn colour and no fix.
 */
@Composable
fun AirShelf(problem: Problem?, live: Boolean, syncing: Boolean, reached: String, missed: String? = null, modifier: Modifier = Modifier, onFix: (Problem) -> Unit, onClick: () -> Unit) {
    val a = LocalAirColors.current
    val shape = RoundedCornerShape(Radius.CARD)
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
        AnimatedContent(problem to (syncing && problem == null), contentKey = { (p, sync) -> p?.title ?: sync }, label = "shelf", transitionSpec = {
            (fadeIn(tween(220, easing = Motion.Out)) + slideInVertically(tween(220, easing = Motion.Out)) { rise }) togetherWith
                (fadeOut(tween(150, easing = Motion.In)) + slideOutVertically(tween(150, easing = Motion.In)) { -rise })
        }) { (shown, sync) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shown != null) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(a.warnTint), contentAlignment = Alignment.Center) {
                        CurveIcon(shown.icon, 22.dp, a.warn, 1.8f)
                    }
                } else if (sync) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(a.line), contentAlignment = Alignment.Center) {
                        CurveIcon(Curve.SYNC, 22.dp, a.muted, 1.8f)
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
                    Text(shown?.title ?: if (sync) "Google Health is syncing" else "Your Fitbit Air", style = Type.bodyMedium, color = a.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // 16 sp lines, as the mono label has, so a problem keeps the shelf's height.
                    if (shown != null && missed != null) Label(missed, color = a.warn)
                    else if (shown != null || sync) Text(shown?.detail ?: "Buzzes may wait up to 6 s", style = Type.small.copy(lineHeight = 16.sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)), color = a.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Label(reached)
                }
                if (shown != null) Pill(shown.action, { onFix(shown) }, small = true) else CurveIcon(Curve.TUNE, 22.dp, a.ink, 1.8f)
            }
        }
    }
}

/** One alert in two lines: the app and who on the first, what happened on the second, so a reason is never cut. */
@Composable
fun HistoryRow(e: Entry) {
    val a = LocalAirColors.current
    val (title, detail) = describe(e)
    Row(Modifier.fillMaxWidth()) {
        // The time and the bar line up with the first line.
        Box(Modifier.height(24.dp).widthIn(min = 44.dp), contentAlignment = Alignment.CenterStart) { Label(timeOf(e.time)) }
        HGap(10)
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) { BarMark(e.fate) }
        HGap(10)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.bodyMedium, color = if (e.fate == Fate.IGNORED) a.muted else a.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = Type.small, color = if (e.fate == Fate.FAILED) a.warn else a.muted)
        }
    }
}

/** What an alert was and what AirBuzz did with it, as (app and who, what happened). */
fun describe(e: Entry): Pair<String, String> {
    if (e.kind == Kind.TEST) return "Test buzz" to if (e.result == com.davort.airbuzz.data.Result.BUZZED) "Buzzed in ${seconds(e.latencyMs)}" else "Failed"
    val title = if (e.who != null && e.who != e.appLabel) "${e.appLabel} · ${e.who}" else e.appLabel
    val call = e.kind == Kind.CALL
    // A call the phone app reported as missed says so where every call row says "Call".
    val callWord = if (e.missed) "Missed call" else "Call"
    val detail = when (e.fate) {
        Fate.BUZZED -> when {
            call -> "$callWord, " + (e.rangMs?.let { "rang ${it / 1000} s" } ?: "ringing")
            e.count > 1 -> "${e.count} messages, 1 buzz"
            else -> "Buzzed"
        }
        Fate.FAILED -> if (call) "$callWord, couldn’t reach your Fitbit Air" else "Couldn’t reach your Fitbit Air"
        // An app or caller that wasn't picked reads "Ignored", the word the Today line and Activity count it under.
        // Only the first letter is lowered after "Call, ", so names such as Fitbit Air and AirBuzz keep their capitals.
        Fate.IGNORED -> (if (e.reason == "not chosen") "Ignored" else reasonLabel(e)).let { if (call) "$callWord, ${if (it.startsWith("AirBuzz")) it else it.replaceFirstChar(Char::lowercase)}" else it }
    }
    return title to detail
}

/** Why an alert was ignored, in the words the screens use. */
fun reasonLabel(e: Entry): String = when (e.reason) {
    null -> if (e.result == com.davort.airbuzz.data.Result.MERGED) "Within 15 s" else "Ignored"
    "not chosen" -> "Not chosen"
    "chat not chosen" -> "Chat not chosen"
    "silent on the phone" -> "Silent on the phone"
    "quiet hours" -> "At night"
    "phone in use" -> "Phone in use"
    "paused" -> "Paused"
    "AirBuzz is off" -> "AirBuzz off"
    // The app took its notification back before the Air answered, usually because it was read elsewhere (Davor, 17 Nov).
    // A no-break space keeps "first" with the app's name, so a wrap never leaves it alone on a line.
    // A call that ended before the band answered; on a missed call this reads "Missed call, ended before your Fitbit Air rang".
    "stopped ringing before the Fitbit Air was reached" -> "Ended before your Fitbit\u00A0Air rang"
    "gone from the phone before the Fitbit Air was reached" -> "Not buzzed · cleared by ${e.appLabel}\u00A0first"
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
                    // In Chosen, someone picked to ring anyway becomes a chosen caller too, or they could never ring.
                    if (ringAnyway) s.copy(
                        ringAnywayPeople = if (p in s.ringAnywayPeople) s.ringAnywayPeople else s.ringAnywayPeople + p,
                        people = if (s.callMode != CallMode.CHOSEN || isChosen(s, p)) s.people else s.people + p)
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
fun rememberFixer(go: (Screen) -> Unit, onBlocked: () -> Unit = {}, onError: (String) -> Unit): (Fix) -> Unit {
    val context = LocalContext.current
    // After a second "Don't allow", Android answers no at once without showing its dialog; [onBlocked] hears about it
    // so the page can point to App info instead (#16). A refusal with no rationale alone isn't enough: dismissing the
    // first dialog without choosing looks the same, and Android still asks next time. So a first "Don't allow" (which
    // turns the rationale on) is remembered, and only a no-rationale refusal after it counts as blocked.
    var asked by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context as? ComponentActivity ?: return@rememberLauncherForActivityResult
        if (granted) return@rememberLauncherForActivityResult
        val prefs = activity.getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE)
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, asked)) prefs.edit().putBoolean(asked, true).apply()
        else if (prefs.getBoolean(asked, false)) onBlocked()
    }
    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    return { fix ->
        when (fix) {
            Fix.NEARBY -> if (Build.VERSION.SDK_INT >= 31) { asked = Manifest.permission.BLUETOOTH_CONNECT; permission.launch(asked) }
            Fix.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) { asked = Manifest.permission.POST_NOTIFICATIONS; permission.launch(asked) }
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
