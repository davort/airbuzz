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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.Dp
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

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

/**
 * The launcher apps, read in the background: reading about 250 app names on the main thread froze AirBuzz for up to
 * five seconds the first time the apps picker opened. [App] re-reads it whenever AirBuzz comes back on screen, so a
 * new install shows up; it is null until the first read finishes.
 */
object AppList {
    private val list = MutableStateFlow<List<AppInfo>?>(null)
    val apps: StateFlow<List<AppInfo>?> = list
    suspend fun refresh(context: Context) { list.value = withContext(Dispatchers.IO) { installedApps(context) } }
}

private val iconCache = Collections.synchronizedMap(HashMap<String, ImageBitmap?>())
private val labelCache = Collections.synchronizedMap(HashMap<String, String>())

/** One app's name, without reading the whole list. */
fun appLabel(context: Context, pkg: String): String = labelCache.getOrPut(pkg) {
    val pm = context.packageManager
    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}

/** An app's icon, loaded off the main thread; its space is kept while it loads, so rows don't shift. */
@Composable
fun AppIcon(pkg: String, size: Dp) {
    val context = LocalContext.current
    val icon by produceState(iconCache[pkg], pkg) {
        if (value == null) value = withContext(Dispatchers.IO) {
            iconCache.getOrPut(pkg) { runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
        }
    }
    Box(Modifier.size(size)) { icon?.let { Image(it, null, Modifier.size(size)) } }
}

/**
 * Settings and every picker are sheets that rise from the bottom over the dimmed page (decision 40): 300 ms in on the
 * sheet curve, 220 ms back down, with the dim on the same timing. Drag one down, tap the dim or press Back to close it.
 * It keeps drawing [content] while it sinks, so the caller keeps passing the last content until it has gone. A [full]
 * sheet is Settings: it fills the screen below the status bar and has the page colour; a picker fits its content.
 */
@Composable
fun SheetLayer(visible: Boolean, onDismiss: () -> Unit, full: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val a = LocalAirColors.current
    val shown = remember { Animatable(0f) }
    val drag = remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible) {
        if (visible) { drag.floatValue = 0f; shown.animateTo(1f, tween(Motion.SHEET, easing = Motion.Out)) }
        else shown.animateTo(0f, tween(Motion.SHEET_BACK, easing = Motion.Out))
    }
    if (!visible && shown.value == 0f) return
    val fast = with(LocalDensity.current) { 1200.dp.toPx() }
    // Let go past a quarter of the sheet, or with a flick, and it closes; otherwise it springs back.
    suspend fun settle(velocity: Float) {
        if (drag.floatValue > height / 4f || velocity > fast) dismiss()
        else animate(drag.floatValue, 0f, velocity, spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> drag.floatValue = v }
    }
    val scope = rememberCoroutineScope()
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || drag.floatValue <= 0f) return Offset.Zero
                val used = maxOf(available.y, -drag.floatValue)
                drag.floatValue += used
                return Offset(0f, used)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                drag.floatValue += available.y
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag.floatValue <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        val p = shown.value * (1f - if (height > 0) (drag.floatValue / height).coerceIn(0f, 1f) else 0f)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(Color.Black.copy(alpha = if (a.dark) 0.55f else 0.32f))
            .pointerInput(Unit) { detectTapGestures { dismiss() } })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp)
                .then(if (full) Modifier.fillMaxHeight() else Modifier)
                .onSizeChanged { height = it.height }
                .graphicsLayer {
                    alpha = if (height == 0) 0f else 1f
                    translationY = (1f - shown.value) * height + drag.floatValue
                }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(if (full) a.bg else a.surface)
                .draggable(rememberDraggableState { d -> drag.floatValue = (drag.floatValue + d).coerceAtLeast(0f) }, Orientation.Vertical,
                    onDragStopped = { v -> scope.launch { settle(v) } })
                .nestedScroll(nested)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp)).background(a.context))
            content()
        }
    }
}

/** A picker's body: serif title (decision 30), the body, and one full-width button. [SheetLayer] draws the sheet. */
@Composable
fun Sheet(title: String, onClose: () -> Unit, action: String = "Done", onAction: () -> Unit = onClose, content: @Composable ColumnScope.() -> Unit) =
    SheetBody(title, action, onAction, content)

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
fun PeopleSheet(engine: Engine, s: Settings, onClose: () -> Unit, openQuiet: () -> Unit) {
    val a = LocalAirColors.current
    val pick = rememberContactPicker(engine)
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Sheet("Who can ring your Air", onClose) {
        Seg(listOf(CallMode.CHOSEN to "Chosen", CallMode.CONTACTS to "Contacts", CallMode.ANYONE to "Anyone"), s.callMode) { m ->
            engine.store.update { it.copy(callMode = m) }
        }
        ScrollUnderFade {
            // Only Chosen has a list; Contacts and Anyone say who rings in one sentence, with room around it.
            when (s.callMode) {
                CallMode.CHOSEN -> Explainer(Curve.PEOPLE, "Only the people you choose ring your Air.", roomy = false)
                CallMode.CONTACTS -> Explainer(Curve.CONTACTS, "Anyone saved in your contacts rings your Air. Numbers you haven't saved stay on your phone.", roomy = true)
                CallMode.ANYONE -> Explainer(Curve.CALLING, "Every call rings your Air, including numbers you haven't saved.", roomy = true)
            }
            if (s.callMode == CallMode.CHOSEN) {
                Column {
                    Label(if (s.people.size >= 5) "Your people · ${s.people.size}" else "Your people")
                    Gap(4)
                    PeopleRows(s.people, firstLine = false) { list -> engine.store.update { it.copy(people = list) } }
                    TextAction("Add people", pick)
                }
                val recent = recentCallers(entries, s, regionOf(context))
                if (recent.isNotEmpty()) Column {
                    Label("Called recently")
                    Gap(4)
                    recent.forEachIndexed { i, p ->
                        LineRow(i == 0) {
                            Avatar(p.name)
                            TwoLine(p.name, Modifier.weight(1f)) { p.number?.let { Label(Rules.display(it)) } }
                            Pill("Add", { engine.store.update { st -> st.copy(people = st.people + p) } }, filled = false, small = true)
                            IconAction("Hide ${p.name}", { engine.store.update { st -> st.copy(hiddenCallers = st.hiddenCallers + (p.number ?: p.name)) } }) {
                                GlyphIcon(Glyph.CLOSE, 18.dp, a.muted)
                            }
                        }
                    }
                }
            }
            QuietNote(s, openQuiet)
        }
    }
}

fun recentCallers(entries: List<com.davort.airbuzz.data.Entry>, s: Settings, region: String): List<Person> =
    entries.filter { it.kind == Kind.CALL && it.who != null }
        .map { Person(it.who!!, it.number?.let { n -> Rules.normalize(n, region) }) }
        .distinct()
        .filter { r -> s.people.none { it.name == r.name || (r.number != null && it.number == r.number) } }
        .filter { (it.number ?: it.name) !in s.hiddenCallers }
        .takeLast(5).reversed()

/** The middle of a sheet: it scrolls when it is too tall, and fades out above the button while there is more below. */
@Composable
fun ColumnScope.ScrollUnderFade(content: @Composable ColumnScope.() -> Unit) {
    val a = LocalAirColors.current
    val scroll = rememberScrollState()
    Box(Modifier.weight(1f, fill = false)) {
        Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
        if (scroll.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, a.surface))))
    }
}

/** An icon in a soft circle beside one sentence that says what the chosen mode does. */
@Composable
private fun Explainer(curve: Curve, text: String, roomy: Boolean) {
    val a = LocalAirColors.current
    Row(Modifier.fillMaxWidth().padding(top = if (roomy) 20.dp else 8.dp, bottom = if (roomy) 20.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(a.bg), contentAlignment = Alignment.Center) { CurveIcon(curve, 22.dp, a.ink, 1.8f) }
        Text(text, style = Type.body, color = a.ink, modifier = Modifier.weight(1f))
    }
}

/** Says whether quiet times silence calls, and opens Stay quiet, where people can be let through. */
@Composable
private fun QuietNote(s: Settings, onClick: () -> Unit) {
    val a = LocalAirColors.current
    val who = ringsAnyway(s)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(a.bg).clickable(role = Role.Button, onClick = onClick)
        .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CurveIcon(Curve.MOON, 20.dp, a.muted, 1.8f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (who == null) "Quiet times silence calls too." else "Only $who ${ring(s.ringAnywayPeople.size)} in quiet times.", style = Type.small, color = a.muted)
            Text(if (who == null) "Let someone ring anyway" else "Change in Stay quiet", style = Type.smallMedium, color = a.accent)
        }
        GlyphIcon(Glyph.CHEVRON, 18.dp, a.muted)
    }
}

fun ring(people: Int) = if (people == 1) "rings" else "ring"

/** A 40 dp round tap target around a small icon, such as a bin. */
@Composable
fun IconAction(description: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) { icon() }
}

/** How long a removed person's Undo row stays. */
const val UNDO_MS = 5000

private data class Removed(val index: Int, val person: Person)

/**
 * People, each with a bin. The bin removes the person at once and leaves an Undo row in their place, with a line
 * under it that drains for five seconds; then the row folds away. Removing someone else ends the last Undo early.
 * [firstLine] draws a hairline above the first row, for a list that follows other rows.
 */
@Composable
fun PeopleRows(people: List<Person>, firstLine: Boolean, onChange: (List<Person>) -> Unit) {
    val a = LocalAirColors.current
    var removed by remember { mutableStateOf<Removed?>(null) }
    val rows: List<Pair<Person, Boolean>> = people.map { it to false }.toMutableList().apply {
        removed?.let { add(it.index.coerceAtMost(size), it.person to true) }
    }
    rows.forEachIndexed { i, (p, gone) ->
        key(p, gone) {
            if (gone) UndoRow(p, line = firstLine || i > 0,
                onUndo = { removed?.let { r -> onChange(people.toMutableList().apply { add(r.index.coerceAtMost(size), r.person) }) }; removed = null },
                onGone = { removed = null })
            else LineRow(!firstLine && i == 0) {
                Avatar(p.name)
                TwoLine(p.name, Modifier.weight(1f)) { Label(p.number?.let(Rules::display) ?: "Matched by name") }
                IconAction("Remove ${p.name}", {
                    removed = Removed(people.indexOf(p), p)
                    onChange(people - p)
                }) { CurveIcon(Curve.BIN, 20.dp, a.muted, 1.8f) }
            }
        }
    }
    // The Undo row's draining line stands in for the hairline above "Add people".
    if (rows.isNotEmpty() && !rows.last().second) Box(Modifier.fillMaxWidth().height(1.dp).background(a.line))
}

@Composable
private fun UndoRow(p: Person, line: Boolean, onUndo: () -> Unit, onGone: () -> Unit) {
    val a = LocalAirColors.current
    val left = remember { Animatable(1f) }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(Unit) {
        left.animateTo(0f, tween(UNDO_MS, easing = LinearEasing))
        shown.targetState = false
    }
    LaunchedEffect(shown.isIdle, shown.currentState) { if (shown.isIdle && !shown.currentState) onGone() }
    AnimatedVisibility(shown, enter = fadeIn(tween(150, easing = Motion.Out)),
        exit = shrinkVertically(tween(220, easing = Motion.InOut)) + fadeOut(tween(150, easing = Motion.In))) {
        Column(Modifier.fillMaxWidth()) {
            if (line) Box(Modifier.fillMaxWidth().height(1.dp).background(a.line))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.alpha(0.45f)) { Avatar(p.name) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.name, style = Type.bodyMedium, color = a.muted)
                    Label("Removed")
                }
                Pill("Undo", onUndo, filled = false, small = true)
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(a.line)) {
                Box(Modifier.fillMaxWidth(left.value).height(2.dp).background(a.accent))
            }
        }
    }
}

@Composable
fun AppsSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    val context = LocalContext.current
    val a = LocalAirColors.current
    val apps by AppList.apps.collectAsStateWithLifecycle()
    val weekCounts = remember {
        engine.log.entries.value.filter { it.kind == Kind.MESSAGE }.groupingBy { it.pkg }.eachCount()
    }
    var query by remember { mutableStateOf("") }
    // Chosen apps first, then the ones that notified most this week, then by name. Order is fixed while open.
    val ordered = remember(apps == null) { apps?.sortedWith(compareBy<AppInfo>({ it.pkg !in s.apps }, { -(weekCounts[it.pkg] ?: 0) }, { it.label.lowercase() })) }
    Sheet("Apps that tap", onClose) {
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, a.outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search apps", style = Type.body, color = a.muted)
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = Type.body.copy(color = a.ink), cursorBrush = SolidColor(a.accent))
        }
        // Lazy, so only the rows on screen are built and only their icons load.
        val shown = ordered.orEmpty().filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        if (ordered == null) Label("Reading your apps…", Modifier.height(420.dp))
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
            itemsIndexed(shown, key = { _, app -> app.pkg }) { i, app ->
                LineRow(i == 0, onClick = { engine.store.update { st -> st.copy(apps = if (app.pkg in st.apps) st.apps - app.pkg else st.apps + app.pkg) } }) {
                    AppIcon(app.pkg, 36.dp)
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
    val pick = rememberContactPicker(engine, ringAnyway = true)
    Sheet("Stay quiet", onClose) {
        ScrollUnderFade { Column { QuietBody(s, { change -> engine.store.update(change) }, pick) { editing = it } } }
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
fun QuietBody(s: Settings, update: ((Settings) -> Settings) -> Unit, pick: () -> Unit, editTime: (Boolean) -> Unit) {
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
        // Off by default with nobody in it: nobody rings through quiet times until people are added (decision 42).
        LineRow(false, onClick = { update { it.copy(ringAnyway = !it.ringAnyway) } }) {
            TwoLine("Let some people ring anyway", Modifier.weight(1f)) { Label(if (s.ringAnyway) "Through quiet times and pauses" else "Off") }
            AirSwitch(s.ringAnyway, { on -> update { it.copy(ringAnyway = on) } })
        }
        if (s.ringAnyway) {
            PeopleRows(s.ringAnywayPeople, firstLine = true) { list -> update { it.copy(ringAnywayPeople = list) } }
            TextAction("Add people", pick)
        }
    }
    if (!s.ringAnyway) Text("While it's off, quiet times silence every call.", style = Type.small, color = a.muted)
}

fun clock(minutes: Int) = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

/** The four ways to pause, as (name, until). [Long.MAX_VALUE] means until turned back on. */
/** Each option's name and when the pause ends; null turns AirBuzz off until switched back on (decision 37). */
fun pauseOptions(s: Settings, now: Long): List<Pair<String, Long?>> {
    val morning = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, s.quietEnd / 60); set(Calendar.MINUTE, s.quietEnd % 60); set(Calendar.SECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    return listOf("For 30 minutes" to now + 30 * 60_000L, "For 1 hour" to now + 3600_000L, "Until morning" to morning,
        "Turn off" to null)
}

@Composable
fun PauseSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val options = remember { pauseOptions(s, now) }
    var choice by remember { mutableStateOf(2) }
    val until = options[choice].second
    Sheet("Pause AirBuzz", onClose, action = if (until == null) "Turn off" else "Pause", onAction = {
        if (until == null) {
            engine.store.update { it.copy(pausedUntil = 0L) }
            engine.setEnabled(false)
        } else engine.store.update { it.copy(pausedUntil = until) }
        onClose()
    }) {
        PauseBody(options, choice, ringsAnyway(s)?.let { "$it still ${ring(s.ringAnywayPeople.size)} while paused, but not when AirBuzz is off." }
            ?: "Nothing buzzes while paused.") { choice = it }
    }
}

@Composable
fun PauseBody(options: List<Pair<String, Long?>>, choice: Int, note: String, onChoose: (Int) -> Unit) {
    val a = LocalAirColors.current
    Column {
        options.forEachIndexed { i, (name, until) ->
            LineRow(i == 0, onClick = { onChoose(i) }) {
                Mark(choice == i, radio = true)
                TwoLine(name, Modifier.weight(1f)) { Label(until?.let { "until ${timeOf(it)}" } ?: "nothing buzzes") }
            }
        }
    }
    Text(note, style = Type.small, color = a.muted)
}
