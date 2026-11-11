package com.davort.airbuzz.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davort.airbuzz.Engine
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.rules.CallMode
import com.davort.airbuzz.rules.Person
import com.davort.airbuzz.rules.Rules
import com.davort.airbuzz.rules.Settings
import java.util.Calendar
import java.util.Locale

data class AppInfo(val pkg: String, val label: String)

/** Apps in the launcher, which is all AirBuzz may list on Android 11 and later without asking for more. */
fun installedApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.pkg != context.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

private val iconCache = HashMap<String, ImageBitmap?>()

fun appIcon(context: Context, pkg: String): ImageBitmap? = iconCache.getOrPut(pkg) {
    runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
}

/** A bottom sheet: handle, serif title (decision 30), the body, and one full-width button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(title: String, onClose: () -> Unit, action: String = "Done", onAction: () -> Unit = onClose, content: @Composable ColumnScope.() -> Unit) {
    val a = LocalAirColors.current
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = a.surface,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (a.dark) 0.55f else 0.32f),
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(a.context)) },
    ) {
        SheetBody(title, action, onAction, content)
    }
}

@Composable
fun SheetBody(title: String, action: String, onAction: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val a = LocalAirColors.current
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = Type.heading, color = a.heading)
        content()
        Pill(action, onAction, Modifier.fillMaxWidth())
    }
}

/** A row in a sheet list, separated from the one above by a hairline. */
@Composable
fun LineRow(first: Boolean, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val a = LocalAirColors.current
    Column(Modifier.fillMaxWidth()) {
        if (!first) Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(a.line))
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content,
        )
    }
}

@Composable
fun PeopleSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    val a = LocalAirColors.current
    val pick = rememberContactPicker(engine)
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Unticked people stay in the list until the sheet closes, so a slip can be undone.
    var unticked by remember { mutableStateOf(setOf<Person>()) }
    DisposableEffect(Unit) {
        onDispose { if (unticked.isNotEmpty()) engine.store.update { st -> st.copy(people = st.people - unticked) } }
    }
    Sheet("Who can ring your Air", onClose) {
        PeopleBody(s, unticked, { p -> unticked = if (p in unticked) unticked - p else unticked + p },
            recentCallers(entries, s, regionOf(context)), pick,
            onMode = { m -> engine.store.update { it.copy(callMode = m) } },
            onAdd = { p -> engine.store.update { st -> st.copy(people = st.people + p) } })
    }
}

fun recentCallers(entries: List<com.davort.airbuzz.data.Entry>, s: Settings, region: String): List<Person> =
    entries.filter { it.kind == Kind.CALL && it.who != null }
        .map { Person(it.who!!, it.number?.let { n -> Rules.normalize(n, region) }) }
        .distinct()
        .filter { r -> s.people.none { it.name == r.name || (r.number != null && it.number == r.number) } }
        .takeLast(5).reversed()

@Composable
fun PeopleBody(
    s: Settings, unticked: Set<Person>, toggle: (Person) -> Unit, recent: List<Person>, pick: () -> Unit,
    onMode: (CallMode) -> Unit, onAdd: (Person) -> Unit,
) {
    val a = LocalAirColors.current
    Seg(listOf(CallMode.CHOSEN to "Chosen", CallMode.CONTACTS to "Contacts", CallMode.ANYONE to "Anyone"), s.callMode, onSelect = onMode)
    Text(when (s.callMode) {
        CallMode.CHOSEN -> "Only the people below ring your Air."
        CallMode.CONTACTS -> "Callers shown by name ring your Air. Only the people below ring through quiet times."
        CallMode.ANYONE -> "Every call rings your Air. Only the people below ring through quiet times."
    }, style = Type.small, color = a.muted)
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        s.people.forEachIndexed { i, p ->
            LineRow(i == 0, onClick = { toggle(p) }) {
                Avatar(p.name)
                TwoLine(p.name, Modifier.weight(1f)) { Label(p.number?.let(Rules::display) ?: "Matched by name") }
                Mark(p !in unticked, radio = false)
            }
        }
        if (recent.isNotEmpty()) {
            Gap(12)
            Label("Called recently")
            recent.forEachIndexed { i, p ->
                LineRow(i == 0) {
                    Avatar(p.name)
                    TwoLine(p.name, Modifier.weight(1f)) { p.number?.let { Label(Rules.display(it)) } }
                    Pill("Add", { onAdd(p) }, filled = false, small = true)
                }
            }
        }
        TextAction("Add people", pick)
    }
}

@Composable
fun AppsSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    val context = LocalContext.current
    val a = LocalAirColors.current
    val apps = remember { installedApps(context) }
    val weekCounts = remember {
        engine.log.entries.value.filter { it.kind == Kind.MESSAGE }.groupingBy { it.pkg }.eachCount()
    }
    var query by remember { mutableStateOf("") }
    // Chosen apps first, then the ones that notified most this week, then by name. Order is fixed while open.
    val ordered = remember { apps.sortedWith(compareBy<AppInfo>({ it.pkg !in s.apps }, { -(weekCounts[it.pkg] ?: 0) }, { it.label.lowercase() })) }
    Sheet("Apps that tap", onClose) {
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, a.outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search apps", style = Type.body, color = a.muted)
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = Type.body.copy(color = a.ink), cursorBrush = SolidColor(a.accent))
        }
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            ordered.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }.forEachIndexed { i, app ->
                LineRow(i == 0, onClick = { engine.store.update { st -> st.copy(apps = if (app.pkg in st.apps) st.apps - app.pkg else st.apps + app.pkg) } }) {
                    appIcon(context, app.pkg)?.let { Image(it, null, Modifier.size(36.dp)) }
                    val n = weekCounts[app.pkg] ?: 0
                    TwoLine(app.label, Modifier.weight(1f), if (n > 0) ({ Label("$n this week") }) else null)
                    Mark(app.pkg in s.apps, radio = false)
                }
            }
        }
    }
}

@Composable
fun PatternSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    Sheet("Pattern for messages", onClose) {
        PatternBody(s.messageTaps, { n -> engine.store.update { it.copy(messageTaps = n) } }) { n -> engine.testBuzz(n) }
    }
}

@Composable
fun PatternBody(taps: Int, onSelect: (Int) -> Unit, onTry: (Int) -> Unit) {
    val a = LocalAirColors.current
    Column {
        listOf(1 to "Tap once", 2 to "Tap twice", 3 to "Tap three times").forEachIndexed { i, (n, name) ->
            LineRow(i == 0, onClick = { onSelect(n) }) {
                Mark(taps == n, radio = true)
                Text(name, style = Type.bodyMedium, color = a.ink, modifier = Modifier.weight(1f))
                Box(Modifier.width(40.dp)) { Rhythm(n) }
                Pill("Try", { onTry(n) }, filled = false, small = true)
            }
        }
    }
    Text("Calls always ring until you answer: a tap every second, for up to a minute.", style = Type.small, color = a.muted)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    Sheet("Stay quiet", onClose) {
        QuietBody(s, { change -> engine.store.update(change) }) { editing = it }
    }
    editing?.let { start ->
        val minutes = if (start) s.quietStart else s.quietEnd
        val state = rememberTimePickerState(minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    engine.store.update { if (start) it.copy(quietStart = m) else it.copy(quietEnd = m) }
                    editing = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
fun QuietBody(s: Settings, update: ((Settings) -> Settings) -> Unit, editTime: (Boolean) -> Unit) {
    val a = LocalAirColors.current
    Column {
        LineRow(true, onClick = { update { it.copy(quietHours = !it.quietHours) } }) {
            TwoLine("At night", Modifier.weight(1f)) { Label("${clock(s.quietStart)}–${clock(s.quietEnd)}") }
            AirSwitch(s.quietHours, { on -> update { it.copy(quietHours = on) } })
        }
        if (s.quietHours) Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Starts ${clock(s.quietStart)}", { editTime(true) }, filled = false, small = true)
            Pill("Ends ${clock(s.quietEnd)}", { editTime(false) }, filled = false, small = true)
        }
        LineRow(false, onClick = { update { it.copy(quietDnd = !it.quietDnd) } }) {
            TwoLine("In Do Not Disturb", Modifier.weight(1f)) { Label("Follows the phone") }
            AirSwitch(s.quietDnd, { on -> update { it.copy(quietDnd = on) } })
        }
        LineRow(false, onClick = { update { it.copy(quietInUse = !it.quietInUse) } }) {
            TwoLine("While I use the phone", Modifier.weight(1f)) { Label("Screen on and unlocked") }
            AirSwitch(s.quietInUse, { on -> update { it.copy(quietInUse = on) } })
        }
    }
    Text("Chosen callers always ring.", style = Type.small, color = a.muted)
}

fun clock(minutes: Int) = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

/** The four ways to pause, as (name, until). [Long.MAX_VALUE] means until turned back on. */
fun pauseOptions(s: Settings, now: Long): List<Pair<String, Long>> {
    val morning = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, s.quietEnd / 60); set(Calendar.MINUTE, s.quietEnd % 60); set(Calendar.SECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    return listOf("For 30 minutes" to now + 30 * 60_000L, "For 1 hour" to now + 3600_000L, "Until morning" to morning,
        "Until I turn it back on" to Long.MAX_VALUE)
}

@Composable
fun PauseSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val options = remember { pauseOptions(s, now) }
    var choice by remember { mutableStateOf(2) }
    Sheet("Pause AirBuzz", onClose, action = "Pause", onAction = {
        engine.store.update { it.copy(pausedUntil = options[choice].second) }
        onClose()
    }) {
        PauseBody(options, choice) { choice = it }
    }
}

@Composable
fun PauseBody(options: List<Pair<String, Long>>, choice: Int, onChoose: (Int) -> Unit) {
    val a = LocalAirColors.current
    Column {
        options.forEachIndexed { i, (name, until) ->
            LineRow(i == 0, onClick = { onChoose(i) }) {
                Mark(choice == i, radio = true)
                TwoLine(name, Modifier.weight(1f), if (until != Long.MAX_VALUE) ({ Label("until ${timeOf(until)}") }) else null)
            }
        }
    }
    Text("Chosen callers still ring while AirBuzz is paused.", style = Type.small, color = a.muted)
}
