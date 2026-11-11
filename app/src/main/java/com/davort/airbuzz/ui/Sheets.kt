package com.davort.airbuzz.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Gap(16)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(selected = value == selected, onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
        }
    }
}

@Composable
fun PeopleSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    val air = LocalAirColors.current
    val pick = rememberContactPicker(engine)
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Sheet("Who can ring", onClose) {
        Choice(listOf(CallMode.CHOSEN to "Chosen", CallMode.CONTACTS to "Contacts", CallMode.ANYONE to "Anyone"), s.callMode) { m ->
            engine.store.update { it.copy(callMode = m) }
        }
        Gap(10)
        Text(when (s.callMode) {
            CallMode.CHOSEN -> "Only the people below ring your Air."
            CallMode.CONTACTS -> "Callers your phone or the calling app shows by name ring your Air, plus the people below. Only the people below ring through quiet times."
            CallMode.ANYONE -> "Every call rings your Air. Only the people below ring through quiet times."
        }, style = MaterialTheme.typography.bodyMedium, color = air.muted)
        Gap(16)
        Label("Chosen people")
        Gap(4)
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(s.people, key = { it.name + it.number }) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        p.number?.let { Text(it, style = MonoLabel, color = air.muted) }
                    }
                    TextButton(onClick = { engine.store.update { st -> st.copy(people = st.people - p) } }) { Text("Remove") }
                }
                HorizontalDivider(color = air.line)
            }
            val region = regionOf(context)
            val recent = entries.filter { it.kind == Kind.CALL && it.who != null }
                .map { Person(it.who!!, it.number?.let { n -> Rules.normalize(n, region) }) }
                .distinct()
                .filter { r -> s.people.none { it.name == r.name || (r.number != null && it.number == r.number) } }
                .takeLast(5).reversed()
            if (recent.isNotEmpty()) {
                item { Gap(14); Label("Called recently"); Gap(4) }
                items(recent, key = { "recent" + it.name + it.number }) { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { engine.store.update { st -> st.copy(people = st.people + p) } }) { Text("Add") }
                    }
                }
            }
        }
        Gap(12)
        Button(onClick = pick, modifier = Modifier.fillMaxWidth()) { Text("Add from contacts") }
    }
}

@Composable
fun AppsSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    val context = LocalContext.current
    val air = LocalAirColors.current
    val apps = remember { installedApps(context) }
    val weekCounts = remember {
        engine.log.entries.value.filter { it.kind == Kind.MESSAGE }.groupingBy { it.pkg }.eachCount()
    }
    var query by remember { mutableStateOf("") }
    // Chosen apps first, then the ones that notified most this week, then by name. Order is fixed while open.
    val ordered = remember { apps.sortedWith(compareBy<AppInfo>({ it.pkg !in s.apps }, { -(weekCounts[it.pkg] ?: 0) }, { it.label.lowercase() })) }
    Sheet("Apps that tap", onClose) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search apps") }, singleLine = true)
        Gap(8)
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(ordered.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }, key = { it.pkg }) { app ->
                val on = app.pkg in s.apps
                Row(Modifier.fillMaxWidth().clickable { toggleApp(engine, app.pkg) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    appIcon(context, app.pkg)?.let { Image(it, null, Modifier.size(36.dp)) }
                    HGap(14)
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        val n = weekCounts[app.pkg] ?: 0
                        if (n > 0) Text("$n this week", style = MonoLabel, color = air.muted)
                    }
                    Switch(checked = on, onCheckedChange = { toggleApp(engine, app.pkg) })
                }
            }
        }
    }
}

private fun toggleApp(engine: Engine, pkg: String) =
    engine.store.update { s -> s.copy(apps = if (pkg in s.apps) s.apps - pkg else s.apps + pkg) }

@Composable
fun PatternSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    val air = LocalAirColors.current
    Sheet("Taps per message", onClose) {
        Choice(listOf(1 to "Once", 2 to "Twice", 3 to "Three times"), s.messageTaps) { n -> engine.store.update { it.copy(messageTaps = n) } }
        Gap(12)
        Text("Every chosen app uses this pattern. Calls ring with a tap every second until you answer, decline or silence them, for up to 60 s.",
            style = MaterialTheme.typography.bodyMedium, color = air.muted)
        Gap(16)
        OutlinedButton(onClick = { engine.testBuzz(s.messageTaps) }, modifier = Modifier.fillMaxWidth()) { Text("Try on my Air") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    val air = LocalAirColors.current
    var editing by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    Sheet("Stay quiet", onClose) {
        ToggleRow("At night", "From ${clock(s.quietStart)} to ${clock(s.quietEnd)}", s.quietHours) { on -> engine.store.update { it.copy(quietHours = on) } }
        Row(Modifier.padding(start = 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = true }) { Text("Starts ${clock(s.quietStart)}") }
            OutlinedButton(onClick = { editing = false }) { Text("Ends ${clock(s.quietEnd)}") }
        }
        Gap(8)
        HorizontalDivider(color = air.line)
        ToggleRow("In Do Not Disturb", "Whenever the phone's Do Not Disturb is on", s.quietDnd) { on -> engine.store.update { it.copy(quietDnd = on) } }
        HorizontalDivider(color = air.line)
        ToggleRow("While I use my phone", "The screen is on and unlocked, so you see it anyway", s.quietInUse) { on -> engine.store.update { it.copy(quietInUse = on) } }
        Gap(12)
        Text("Chosen people still ring through all of these.", style = MaterialTheme.typography.bodyMedium, color = air.muted)
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

fun clock(minutes: Int) = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

@Composable
fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = LocalAirColors.current.muted)
        }
        HGap(12)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PauseSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    val air = LocalAirColors.current
    val now = System.currentTimeMillis()
    val pause = { until: Long -> engine.store.update { it.copy(pausedUntil = until) }; onClose() }
    Sheet("Pause", onClose) {
        if (s.pausedUntil > now) {
            Button(onClick = { pause(0) }, modifier = Modifier.fillMaxWidth()) { Text("Resume now") }
            Gap(8)
        }
        val morning = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.quietEnd / 60); set(Calendar.MINUTE, s.quietEnd % 60); set(Calendar.SECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        listOf("30 minutes" to now + 30 * 60_000L, "1 hour" to now + 3600_000L, "Until ${clock(s.quietEnd)}" to morning,
            "Until I turn it back on" to Long.MAX_VALUE).forEach { (label, until) ->
            OutlinedButton(onClick = { pause(until) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(label) }
        }
        Gap(10)
        Text("Chosen people still ring while paused.", style = MaterialTheme.typography.bodyMedium, color = air.muted)
    }
}
