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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.davort.airbuzz.Engine
import com.davort.airbuzz.data.Entry
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.data.Result
import com.davort.airbuzz.engine
import com.davort.airbuzz.rules.CallMode
import com.davort.airbuzz.rules.Person
import com.davort.airbuzz.rules.Rules
import com.davort.airbuzz.rules.Settings as AirSettings
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
                val dark = LocalAirColors.current.dark
                val bg = MaterialTheme.colorScheme.background
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb())
                        else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
                    enableEdgeToEdge(style, style)
                }
                Surface(Modifier.fillMaxSize(), color = bg) { App(engine, settings) }
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

@Composable
fun App(engine: Engine, settings: AirSettings) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var sheet by rememberSaveable { mutableStateOf<SheetKind?>(null) }
    BackHandler(screen != Screen.HOME) {
        screen = if (screen == Screen.APPEARANCE || screen == Screen.SETUP) Screen.SETTINGS else Screen.HOME
    }
    val go: (Screen) -> Unit = { screen = it }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (screen) {
            Screen.HOME -> Home(engine, settings, go) { sheet = it }
            Screen.SETTINGS -> SettingsScreen(engine, settings, go)
            Screen.APPEARANCE -> AppearanceScreen(engine, settings) { screen = Screen.SETTINGS }
            Screen.TEST -> TestBuzzScreen(engine) { screen = Screen.HOME }
            Screen.ACTIVITY -> ActivityScreen(engine) { screen = Screen.HOME }
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

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 4.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClickLabel = "Back", onClick = onBack),
                contentAlignment = Alignment.Center) {
                BackArrow(MaterialTheme.colorScheme.onSurface, Modifier.size(24.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp), modifier = Modifier.weight(1f).semantics { heading() })
        actions()
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

@Composable
fun Home(engine: Engine, s: AirSettings, go: (Screen) -> Unit, open: (SheetKind) -> Unit) {
    val context = LocalContext.current
    val air = LocalAirColors.current
    val band by engine.state.collectAsStateWithLifecycle()
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val apps = remember(tick) { installedApps(context) }
    val problem = remember(tick, band.lastError, s.enabled) { firstProblem(context, band.lastError) }
    val now = System.currentTimeMillis()
    val paused = s.pausedUntil > now
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("AirBuzz") {
            TextButton(onClick = { go(Screen.SETTINGS) }) { Label("Settings", color = MaterialTheme.colorScheme.onSurface) }
            Switch(checked = s.enabled, onCheckedChange = { on ->
                if (on && Build.VERSION.SDK_INT >= 33 && !Setup.hasPostNotifications(context)) {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                engine.setEnabled(on)
            })
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = when {
                    !s.enabled -> "Off"
                    paused && s.pausedUntil == Long.MAX_VALUE -> "Paused"
                    paused -> "Paused until ${timeOf(s.pausedUntil)}"
                    else -> "On"
                }
                Label(status, Modifier.clickable { open(SheetKind.PAUSE) }.padding(vertical = 6.dp),
                    color = if (paused) air.warn else air.muted)
                Box(Modifier.weight(1f))
                band.lastReachedAt?.let { Label("Air reached ${timeOf(it)}") }
            }
            if (problem != null && s.enabled) {
                Gap(8)
                ProblemBanner(problem.first, problem.second) { go(if (problem.third) Screen.SETUP else Screen.TEST) }
            }
            Gap(10)
            BuzzStrip(entries)
            Gap(6)
            val dayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
            val today = entries.filter { it.time >= dayStart && it.kind != Kind.TEST }
            Row {
                Label("Today")
                Box(Modifier.weight(1f))
                Label("${today.count { it.result == Result.BUZZED }} buzzed", color = air.accent)
                Label(" · ${today.count { it.result != Result.BUZZED }} quiet")
            }
            Gap(16)
        }
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Calls", Modifier.weight(1f)); RhythmMarks(ring = true, taps = 0)
                }
                Gap(10)
                val who = when (s.callMode) {
                    CallMode.CHOSEN -> summarize(s.people.map { it.name }, "nobody yet")
                    CallMode.CONTACTS -> "my contacts"
                    CallMode.ANYONE -> "anyone"
                }
                Sentence(listOf(
                    Part.Words("When"),
                    Part.Blank("Who can ring", who) { open(SheetKind.PEOPLE) },
                    Part.Words((if (s.callMode == CallMode.ANYONE || (s.callMode == CallMode.CHOSEN && s.people.size == 1)) "calls" else "call") +
                        " from any app, ring my Air."),
                ))
            }
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Messages", Modifier.weight(1f)); RhythmMarks(ring = false, taps = s.messageTaps)
                }
                Gap(10)
                val names = s.apps.map { pkg -> apps.firstOrNull { it.pkg == pkg }?.label ?: pkg }.sorted()
                Sentence(listOf(
                    Part.Words("When"),
                    Part.Blank("Apps that tap", summarizeApps(names)) { open(SheetKind.APPS) },
                    Part.Words("message, tap"),
                    Part.Blank("Taps per message", tapsWord(s.messageTaps), after = ".") { open(SheetKind.PATTERN) },
                ))
            }
            Section {
                Label("Quiet")
                Gap(10)
                Sentence(listOf(
                    Part.Words("Stay quiet"),
                    Part.Blank("When to stay quiet", summarizeQuiet(quietRules(s)), after = ".") { open(SheetKind.QUIET) },
                ))
                Gap(6)
                Text("Chosen people still ring.", style = MaterialTheme.typography.bodyMedium, color = air.muted)
            }
        }
        Gap(18)
        Column(Modifier.padding(horizontal = 20.dp)) {
            val recent = entries.filter { it.kind != Kind.TEST }.takeLast(3).reversed()
            if (recent.isEmpty()) {
                Text("Nothing yet. Alerts appear here with what AirBuzz did.", style = MaterialTheme.typography.bodyMedium, color = air.muted)
            }
            recent.forEach { EntryRow(it) }
            TextButton(onClick = { go(Screen.ACTIVITY) }, modifier = Modifier.padding(top = 4.dp)) { Label("See all", color = air.accent) }
            Gap(12)
        }
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

@Composable
fun ProblemBanner(text: String, action: String, onFix: () -> Unit) {
    val air = LocalAirColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(air.warn.copy(alpha = 0.14f)).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onFix) { Text(action, color = air.warn) }
    }
}

@Composable
fun EntryRow(e: Entry) {
    val air = LocalAirColors.current
    val buzzed = e.result == Result.BUZZED
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(timeOf(e.time), style = MonoLabel.copy(fontSize = 13.sp), color = air.muted, modifier = Modifier.size(width = 64.dp, height = 20.dp))
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).then(
            if (buzzed) Modifier.background(air.accent)
            else Modifier.border(1.5.dp, if (e.result == Result.FAILED) air.warn else air.muted, RoundedCornerShape(50))))
        HGap(10)
        Text(describe(e), style = MaterialTheme.typography.bodyLarge, color = if (buzzed) MaterialTheme.colorScheme.onSurface else air.muted)
    }
}

fun describe(e: Entry): String {
    val who = e.who ?: e.appLabel
    return when {
        e.kind == Kind.TEST -> if (e.result == Result.BUZZED) "Test buzz · ${seconds(e.latencyMs)}" else "Test buzz · failed"
        e.kind == Kind.CALL && e.result == Result.BUZZED -> "$who called · " + (e.rangMs?.let { "rang ${it / 1000} s" } ?: "ringing")
        e.kind == Kind.CALL -> "$who called · ${e.reason ?: "quiet"}"
        e.result == Result.BUZZED -> if (e.count > 1) "$who · ${e.count} messages, 1 buzz" else "$who · ${e.appLabel}"
        e.result == Result.FAILED -> "$who · couldn't reach your Air"
        else -> "${e.appLabel} · ${e.reason ?: "merged"}"
    }
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
