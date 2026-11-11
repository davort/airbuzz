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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    var sheet by rememberSaveable { mutableStateOf<SheetKind?>(null) }
    BackHandler(screen != Screen.HOME) {
        screen = if (screen == Screen.APPEARANCE || screen == Screen.SETUP) Screen.SETTINGS else Screen.HOME
    }
    val go: (Screen) -> Unit = { screen = it }
    val open: (SheetKind) -> Unit = { sheet = it }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (screen) {
            Screen.HOME -> Home(engine, settings, sheet, go, open)
            Screen.SETTINGS -> SettingsScreen(engine, settings, go)
            Screen.APPEARANCE -> AppearanceScreen(engine, settings) { screen = Screen.SETTINGS }
            Screen.TEST -> TestBuzzScreen(engine) { screen = Screen.HOME }
            Screen.ACTIVITY -> ActivityScreen(engine, open) { screen = Screen.HOME }
            Screen.SETUP -> SetupScreen(engine, go) { screen = Screen.SETTINGS }
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
    val band by engine.state.collectAsStateWithLifecycle()
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val now = rememberNow()
    val apps = remember(tick) { installedApps(context) }
    val problem = remember(tick, band.lastError, s.enabled) { firstProblem(context, band.lastError) }
    val paused = s.enabled && s.pausedUntil > now
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Page {
        // Top bar: title, state, switch (decision 20), Settings.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AirBuzz", style = Type.heading, color = a.heading, modifier = Modifier.weight(1f).semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    paused -> { GlyphIcon(Glyph.PAUSE, 16.dp, a.muted, 2.4f); HGap(4); Text("Paused", style = Type.smallMedium, color = a.muted) }
                    s.enabled -> { Dot(a.ok); HGap(6); Text("On", style = Type.smallMedium, color = a.ink) }
                    else -> { Dot(a.muted); HGap(6); Text("Off", style = Type.smallMedium, color = a.muted) }
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
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Settings", role = Role.Button) { go(Screen.SETTINGS) }
                .semantics { contentDescription = "Settings" }, contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.TUNE, 22.dp, a.ink, 1.8f)
            }
        }

        if (paused) {
            PausedCard(s, now, onResume = { engine.store.update { it.copy(pausedUntil = 0L) } }) { open(SheetKind.PAUSE) }
        } else {
            lastReached(band.lastReachedAt, entries)?.let { Label("Air reached ${timeOf(it)}") }
            val start = dayStart(now = now)
            val today = entries.filter { it.time >= start && it.kind != Kind.TEST }
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open Activity") { go(Screen.ACTIVITY) },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BuzzStrip(entries, start)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Today", Modifier.weight(1f))
                    Label("${today.count { it.fate == Fate.BUZZED }} buzzed", color = a.accent)
                    HGap(6)
                    Label("· ${today.count { it.fate == Fate.IGNORED }} ignored")
                    HGap(6)
                    GlyphIcon(Glyph.CHEVRON, 14.dp, a.muted, 2.4f)
                }
            }
        }

        if (problem != null && s.enabled) ProblemBanner(problem.first, problem.second) { go(if (problem.third) Screen.SETUP else Screen.TEST) }

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
            recent.forEach { HistoryRow(it) }
        }
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
    val forever = s.pausedUntil == Long.MAX_VALUE
    Card(gap = 6.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.PAUSE, 14.dp, a.muted, 2.4f)
            HGap(6)
            val left = (s.pausedUntil - now).coerceAtLeast(0) / 60_000
            Label(if (forever) "Paused" else if (left >= 60) "Paused · ${left / 60} h ${left % 60} min left" else "Paused · $left min left")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (forever) "Until you resume" else "Until ${timeOf(s.pausedUntil)}", style = Type.title, color = a.ink, modifier = Modifier.weight(1f))
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

/** The first thing stopping AirBuzz, as (message, fix label, opens setup). */
fun firstProblem(context: android.content.Context, lastError: String?): Triple<String, String, Boolean>? = when {
    !Setup.hasNearby(context) -> Triple("AirBuzz can't use Bluetooth.", "Fix", true)
    Setup.bondedAirAddress(context) == null -> Triple("No paired Fitbit Air on this phone.", "Fix", true)
    !Setup.hasListener(context) -> Triple("AirBuzz can't see notifications.", "Fix", true)
    lastError != null -> Triple("Couldn't reach your Air: $lastError", "Test", false)
    else -> null
}

/** One problem, one fix (principle 5). */
@Composable
fun ProblemBanner(text: String, action: String, onFix: () -> Unit) {
    val a = LocalAirColors.current
    Card(gap = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(a.warn)
            HGap(12)
            Text(text, style = Type.small, color = a.ink, modifier = Modifier.weight(1f))
            HGap(12)
            Pill(action, onFix, filled = false, small = true)
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
