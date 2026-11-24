package com.davort.airbuzz.ui

import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.Dp
import java.util.Locale
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

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
 * When an open picker's content jumps in height, such as switching Chosen to Contacts, the sheet is laid out at its new
 * height at once but drawn from where its top was, then glides there (300 ms, in-out): a transform, not a height
 * animation. Changes under 48 dp, such as an Undo row folding or the keyboard rising, already move frame by frame.
 */
@Composable
fun SheetLayer(visible: Boolean, onDismiss: () -> Unit, full: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val a = LocalAirColors.current
    val shown = remember { Animatable(0f) }
    val drag = remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val resize = remember { Animatable(0f) }
    val jump = with(LocalDensity.current) { 48.dp.toPx() }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible) {
        if (visible) { drag.floatValue = 0f; shown.animateTo(1f, tween(Motion.SHEET, easing = Motion.Out)) }
        else if (height > 0 && drag.floatValue >= height) shown.snapTo(0f) // already dragged away
        else shown.animateTo(0f, tween(Motion.SHEET_BACK, easing = Motion.Out))
    }
    if (!visible && shown.value == 0f) return
    val fast = with(LocalDensity.current) { 1200.dp.toPx() }
    // Let go past a quarter of the sheet, or with a flick, and it closes; otherwise it springs back.
    suspend fun settle(velocity: Float) {
        // A dismissed sheet keeps the finger's speed on its way down, as a spring without bounce, instead of restarting
        // at a fixed pace (Emil: a draggable sheet hands its release velocity to the animation).
        if (drag.floatValue > height / 4f || velocity > fast) {
            animate(drag.floatValue, height.toFloat(), velocity, spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> drag.floatValue = v }
            dismiss()
        }
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
                .onSizeChanged {
                    val delta = (it.height - height).toFloat()
                    if (!full && visible && height > 0 && shown.value == 1f && kotlin.math.abs(delta) >= jump) scope.launch {
                        resize.snapTo(resize.value + delta)
                        resize.animateTo(0f, tween(Motion.SHEET, easing = Motion.InOut))
                    }
                    height = it.height
                }
                .graphicsLayer {
                    alpha = if (height == 0) 0f else 1f
                    translationY = (1f - shown.value) * height + drag.floatValue + resize.value
                }
                // While a shrunk sheet glides down, fill the gap under it with the sheet's colour.
                .drawBehind { if (resize.value < 0f) drawRect(if (full) a.bg else a.surface, Offset(0f, size.height - 1f), Size(size.width, -resize.value + 1f)) }
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
fun Sheet(title: String, onClose: () -> Unit, action: String = "Done", onAction: () -> Unit = onClose, actionShown: Boolean = true,
          content: @Composable ColumnScope.() -> Unit) =
    SheetBody(title, action, onAction, actionShown, content)

/** A sheet hides its button while a search has the keyboard up, so the results get the room (Davor, 24 Nov). */
@Composable
fun SheetBody(title: String, action: String, onAction: () -> Unit, actionShown: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val a = LocalAirColors.current
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = Space.TIGHT.dp, bottom = Space.SECTION.dp), verticalArrangement = Arrangement.spacedBy(Space.TIGHT.dp)) {
        Text(title, style = Type.heading, color = a.heading)
        content()
        if (actionShown) Pill(action, onAction, Modifier.fillMaxWidth())
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
    val cascaded = remember { mutableStateListOf<Person>() }
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Sheet("Who can ring your Fitbit\u00A0Air", onClose) {
        CallChoice(s.callMode, s.people, icon = true) { m -> engine.store.update { it.copy(callMode = m) } }
        ScrollUnderFade {
            // Only Chosen has a list.
            if (s.callMode == CallMode.CHOSEN) {
                Column {
                    Label(if (s.people.size >= 5) "Your people · ${s.people.size}" else "Your people")
                    Gap(Space.HINT)
                    // At the top, so it stays in reach however long the list grows.
                    TextAction("Add people", pick)
                    PeopleRows(s.people) { list -> engine.store.update { st ->
                        // Someone removed here can't ring at all, so they leave ring anyway too; Undo puts them back in both.
                        val gone = st.people - list.toSet()
                        val back = list - st.people.toSet()
                        cascaded += st.ringAnywayPeople.filter { it in gone }
                        val restore = back.filter { it in cascaded && it !in st.ringAnywayPeople }
                        cascaded -= restore.toSet()
                        st.copy(people = list, ringAnywayPeople = st.ringAnywayPeople - gone.toSet() + restore)
                    } }
                }
                val recent = recentCallers(entries, s, regionOf(context))
                if (recent.isNotEmpty()) Column {
                    Label("Called recently")
                    Gap(Space.HINT)
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

/** The middle of a sheet: it scrolls when it is too tall, and fades out under the title and above the button. */
@Composable
fun ColumnScope.ScrollUnderFade(content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.weight(1f, fill = false).edgeFades(scroll, LocalAirColors.current.surface).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(Space.SECTION.dp), content = content)
}

/** An icon in a soft circle beside one sentence that says what the chosen mode does. */
/**
 * Who can ring, as one choice and what it will do (Davor, 15 Nov): the choice, then one future-tense sentence with
 * the who in the accent, and a quieter line for every other call. Onboarding's step and the sheet on Home share it.
 */
@Composable
fun CallChoice(mode: CallMode, people: List<Person>, icon: Boolean = false, onSelect: (CallMode) -> Unit) {
    val a = LocalAirColors.current
    Column {
        PillChoice(listOf(CallMode.CHOSEN to "Chosen", CallMode.CONTACTS to "Contacts", CallMode.ANYONE to "Anyone"), mode, onSelect)
        val names = people.map { it.name }
        // A call always rings the phone; the caption says which calls won't also ring the Fitbit Air (Davor, 15 Nov).
        val other = "Other calls will ring only your\u00A0phone."
        val (before, who, rest) = when (mode) {
            CallMode.CHOSEN -> when (names.size) {
                0 -> Triple("Only ", "the people you add", other)
                1 -> Triple("Only calls from ", names[0], other)
                2 -> Triple("Only calls from ", "${names[0]} and ${names[1]}", other)
                // As on Home's Calls rule.
                else -> Triple("Only calls from ", "${names[0]} and ${names.size - 1}\u00A0more", other)
            }
            CallMode.CONTACTS -> Triple("", "Everyone in your contacts", "Numbers you haven't saved will ring only your\u00A0phone.")
            CallMode.ANYONE -> Triple("", "Every call", "Even from numbers you haven't\u00A0saved.")
        }
        // Read as one sentence by a screen reader; the panel eases to the new height when the sentence changes. The sheet
        // on Home shows the choice's icon above it; onboarding's step already has its own icon, so it shows none.
        Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = Space.SECTION.dp, bottom = (Space.SECTION - Space.TIGHT).dp)
            .animateContentSize(tween(220, easing = Motion.InOut)).semantics(mergeDescendants = true) {}) {
            if (icon) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(a.bg), contentAlignment = Alignment.Center) {
                    CurveIcon(when (mode) { CallMode.CHOSEN -> Curve.PEOPLE; CallMode.CONTACTS -> Curve.CONTACTS; CallMode.ANYONE -> Curve.CALLING }, 24.dp, a.ink, 1.8f)
                }
                Gap(Space.INLINE)
            }
            Text(buildAnnotatedString {
                append(before)
                withStyle(SpanStyle(color = a.accent, fontWeight = FontWeight(500))) { append(who) }
                append(" will ring your Fitbit\u00A0Air.")
            }, style = Type.body.copy(fontSize = 20.sp, lineHeight = 28.sp, lineBreak = LineBreak.Heading), color = a.ink)
            Gap(6)
            Text(rest, style = Type.small.copy(lineBreak = LineBreak.Heading), color = a.muted)
        }
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
            Text(if (who == null) "Quiet times silence calls too." else "Only $who ${ring(ringingAnyway(s).size)} in quiet times.", style = Type.small, color = a.muted)
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

/**
 * People, each under a hairline and with a bin. The bin, or a swipe to the left, removes the person at once: their row
 * slides out to the left and a tinted Undo row takes its place, whose Undo button empties over five seconds; then the
 * row folds away. Each removal keeps its own five seconds, however many people are removed meanwhile.
 */
@Composable
fun PeopleRows(people: List<Person>, topLine: Boolean = true, onChange: (List<Person>) -> Unit) {
    val a = LocalAirColors.current
    // Removed people still showing Undo. They keep their place from the last order shown, so one folding away
    // doesn't move another; people added since come last, as they do in [people].
    val removed = remember { mutableStateListOf<Person>() }
    // Removed by a swipe: the swipe already filled the row with the tint, so the Undo row only fades in over it.
    val swiped = remember { HashSet<Person>() }
    val lastOrder = remember { ArrayList<Person>() }
    val shown = lastOrder.filter { it in people || it in removed } + people.filter { it !in lastOrder }
    lastOrder.clear(); lastOrder.addAll(shown)
    // An Undo row has no hairline above or below it. The row after one hides its hairline until the Undo row starts to
    // fold away, and while a row is swiped both hairlines fade with the swipe, so none vanish at the swap.
    val folding = remember { mutableStateListOf<Person>() }
    val pull = remember { mutableStateMapOf<Person, Float>() }
    val isGone = { q: Person -> q in removed && q !in people }
    shown.forEachIndexed { i, p ->
        key(p) {
            val gone = isGone(p)
            val prev = shown.getOrNull(i - 1)
            // Under a list heading the first row has no hairline, as in "Called recently"; under Add people it has one.
            val line = if (prev == null) topLine else !isGone(prev) || prev in folding
            AnimatedContent(gone, label = "remove", transitionSpec = {
                // The swiped row already looks like the Undo row's tint and bin, so it stays underneath while the name and Undo fade in.
                if (targetState && p in swiped) fadeIn(tween(160, easing = Motion.Out)) togetherWith ExitTransition.KeepUntilTransitionsFinished
                else if (targetState) (slideInHorizontally(tween(260, easing = Motion.Out)) { it / 5 } + fadeIn(tween(200, 60, Motion.Out))) togetherWith
                    (slideOutHorizontally(tween(220, easing = Motion.In)) { -it / 2 } + fadeOut(tween(160, easing = Motion.In)))
                else (slideInHorizontally(tween(260, easing = Motion.Out)) { -it / 5 } + fadeIn(tween(200, 60, Motion.Out))) togetherWith
                    fadeOut(tween(120, easing = Motion.In))
            }) { removing ->
                if (removing) UndoRow(p,
                    onUndo = {
                        val before = shown.takeWhile { it != p }.count { it in people }
                        removed.remove(p); swiped.remove(p); pull.remove(p)
                        onChange(people.toMutableList().apply { add(before.coerceAtMost(size), p) })
                    },
                    onFold = { folding.add(p); pull.remove(p) },
                    onGone = { removed.remove(p); swiped.remove(p); folding.remove(p) })
                else SwipeToRemove("Remove ${p.name}", line, { prev?.let { pull[it] } ?: 0f }, { pull[p] = it }, onRemove = { bySwipe ->
                    if (bySwipe) swiped.add(p)
                    removed.add(p)
                    onChange(people - p)
                }) {
                    Avatar(p.name)
                    TwoLine(p.name, Modifier.weight(1f)) { Label(p.number?.let(Rules::display) ?: "Matched by name") }
                }
            }
        }
    }
}

/**
 * A row under a hairline that ends in a bin and that a swipe to the left also removes. [line] false hides the hairline;
 * it also fades as this row or the one above ([prevPull]) is swiped. The row reports its swipe to [onPull] from 0 to 1
 * at the 40% mark. It is exactly as tall as the Undo row, and uncovers a strip in that row's tint and shape, and the strip's bin takes over from the row's, which fades. Past 40% of the width the bin
 * turns accent and follows the row, with a tick; letting go there, or flinging left, slides the row away and calls
 * [onRemove] with true. Anything less springs back. The row's bin calls it with false.
 */
@Composable
private fun SwipeToRemove(
    removeLabel: String, line: Boolean, prevPull: () -> Float, onPull: (Float) -> Unit, onRemove: (bySwipe: Boolean) -> Unit, content: @Composable RowScope.() -> Unit) {
    val a = LocalAirColors.current
    val view = LocalView.current
    val density = LocalDensity.current
    var dx by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    fun move(x: Float) {
        dx = x
        if (width > 0) onPull((-x / (width * 0.4f)).coerceIn(0f, 1f))
    }
    val follow by animateFloatAsState(if (armed) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "follow")
    val binScale by animateFloatAsState(if (armed && !leaving) 1.15f else 1f, spring(0.5f, Spring.StiffnessMedium), label = "bin")
    fun arm(on: Boolean) {
        if (on == armed) return
        armed = on
        view.performHapticFeedback(when {
            Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            else -> if (on) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK
        })
    }
    Column(Modifier.fillMaxWidth()) {
        val lineAlpha by animateFloatAsState(if (line) 1f else 0f, tween(160, easing = Motion.InOut), label = "line")
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).graphicsLayer {
            val own = if (width > 0) (-dx / (width * 0.4f)).coerceIn(0f, 1f) else 0f
            alpha = lineAlpha * (1 - own) * (1 - prevPull())
        }.background(a.line))
        Box(Modifier.fillMaxWidth().clipToBounds().onSizeChanged { width = it.width }) {
            Box(Modifier.matchParentSize().drawBehind {
                val shown = -dx
                if (shown > 0f) {
                    val inset = 4.dp.toPx()
                    drawRoundRect(a.tintPressed, Offset(size.width - shown, inset), Size(shown, size.height - 2 * inset),
                        CornerRadius(minOf(14.dp.toPx(), shown / 2)))
                }
            })
            // The bin rests 36 dp from the right edge. Once armed it follows the row's edge 30 dp behind it, so a row swiped
            // all the way leaves it where the Undo row's bin sits, in place of the person's initial.
            Box(Modifier.align(Alignment.CenterStart).graphicsLayer {
                val shown = -dx
                val half = size.width / 2
                val rest = width - 36.dp.toPx() - half
                translationX = rest + (width - shown + 30.dp.toPx() - half - rest) * follow
                alpha = (shown / 72.dp.toPx()).coerceIn(0f, 1f)
                scaleX = binScale; scaleY = binScale
            }) { CurveIcon(Curve.BIN, 20.dp, lerp(a.muted, a.accent, follow), 1.8f) }
            Row(
                Modifier.fillMaxWidth().offset { IntOffset(dx.roundToInt(), 0) }
                    .draggable(rememberDraggableState { d ->
                        move((dx + d).coerceIn(-width.toFloat(), 0f))
                        arm(-dx > width * 0.4f)
                    }, Orientation.Horizontal, onDragStopped = { v ->
                        val fling = with(density) { 600.dp.toPx() }
                        if (v < -fling || (armed && v < fling)) {
                            arm(true); leaving = true
                            animate(dx, -width.toFloat(), v, spring(1f, Spring.StiffnessMedium)) { x, _ -> move(x) }
                            onRemove(true)
                        } else {
                            armed = false
                            animate(dx, 0f, v, spring(0.8f, Spring.StiffnessMedium)) { x, _ -> move(x) }
                        }
                    })
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                Box(Modifier.graphicsLayer { alpha = 1f - (-dx / 32.dp.toPx()).coerceIn(0f, 1f) }) {
                    IconAction(removeLabel, { onRemove(false) }) { CurveIcon(Curve.BIN, 20.dp, a.muted, 1.8f) }
                }
            }
        }
    }
}

/**
 * A removed person: a tinted row with a bin where their initial was. The row holds the time left as a darker fill that
 * empties right to left; the Undo button keeps the surface colour so it stands out against it.
 */
@Composable
private fun UndoRow(p: Person, onUndo: () -> Unit, onFold: () -> Unit, onGone: () -> Unit) {
    val a = LocalAirColors.current
    val left = remember { Animatable(1f) }
    val visible = remember { MutableTransitionState(true) }
    LaunchedEffect(Unit) {
        left.animateTo(0f, tween(UNDO_MS, easing = LinearEasing))
        onFold()
        visible.targetState = false
    }
    LaunchedEffect(visible.isIdle, visible.currentState) { if (visible.isIdle && !visible.currentState) onGone() }
    AnimatedVisibility(visible, enter = EnterTransition.None,
        exit = shrinkVertically(tween(240, easing = Motion.InOut)) + fadeOut(tween(160, easing = Motion.In))) {
        // The same height as a person's row: an empty 1 dp where its hairline was, then 4 + 6 dp around the content where
        // the row has 10 dp, so a swiped row turns into this one without moving.
        Row(Modifier.fillMaxWidth().padding(top = 1.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(a.tint)
            .drawBehind { drawRect(a.tintPressed, size = size.copy(width = size.width * left.value)) }
            .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { CurveIcon(Curve.BIN, 20.dp, a.accent, 1.8f) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.name, style = Type.bodyMedium, color = a.ink, maxLines = 1)
                Label("Removed", color = a.accent)
            }
            val shape = CircleShape
            Box(Modifier.height(40.dp).clip(shape).background(a.surface).border(1.dp, a.accent, shape)
                .clickable(role = Role.Button, onClickLabel = "Undo removing ${p.name}", onClick = onUndo),
                contentAlignment = Alignment.Center) {
                Text("Undo", style = Type.smallMedium, color = a.accent, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
    }
}

/** A rounded search field with a magnifier and a muted hint while empty. */
@Composable
fun SearchField(query: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val a = LocalAirColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, a.outline, RoundedCornerShape(22.dp))
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        CurveIcon(Curve.SEARCH, 18.dp, a.muted)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(hint, style = Type.body, color = a.muted)
            BasicTextField(query, onChange, modifier.fillMaxWidth(), singleLine = true, textStyle = Type.body.copy(color = a.ink), cursorBrush = SolidColor(a.accent))
        }
    }
}

@Composable
fun AppsSheet(engine: Engine, s: Settings, onClose: () -> Unit, openChats: (String) -> Unit) {
    val context = LocalContext.current
    val a = LocalAirColors.current
    val apps by AppList.apps.collectAsStateWithLifecycle()
    val weekCounts = remember {
        engine.log.entries.value.filter { it.kind == Kind.MESSAGE }.groupingBy { it.pkg }.eachCount()
    }
    var query by remember { mutableStateOf("") }
    // Chosen apps first, then the ones that notified most this week, then by name. Order is fixed while open.
    val ordered = remember(apps == null) { apps?.sortedWith(compareBy<AppInfo>({ it.pkg !in s.apps }, { -(weekCounts[it.pkg] ?: 0) }, { it.label.lowercase() })) }
    Sheet("Apps that buzz", onClose) {
        SearchField(query, "Search apps") { query = it }
        // Lazy, so only the rows on screen are built and only their icons load.
        val shown = ordered.orEmpty().filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        if (ordered == null) Label("Reading your apps…", Modifier.height(420.dp))
        val list = rememberLazyListState()
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).edgeFades(list, a.surface), list) {
            itemsIndexed(shown, key = { _, app -> app.pkg }) { i, app ->
                val toggle = { engine.store.update { st -> st.copy(apps = if (app.pkg in st.apps) st.apps - app.pkg else st.apps + app.pkg) } }
                // A chosen app whose chats can be told apart opens its chats from the whole row, so a tap there never
                // turns off an app with chats picked; only its checkbox does (Davor, 24 Nov). Other rows tick as before.
                val chats = app.pkg in CHAT_APPS && app.pkg in s.apps
                LineRow(i == 0, onClick = if (chats) ({ openChats(app.pkg) }) else toggle) {
                    AppIcon(app.pkg, 36.dp)
                    val n = weekCounts[app.pkg] ?: 0
                    if (chats) TwoLine(app.label, Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(chatsSummary(s.chats[app.pkg]), color = a.accent)
                            Spacer(Modifier.width(2.dp))
                            GlyphIcon(Glyph.CHEVRON, 12.dp, a.accent, 3f)
                        }
                    } else TwoLine(app.label, Modifier.weight(1f), if (n > 0) ({ Label("$n this week") }) else null)
                    // Every row keeps its checkbox in the same 48 dp box, so ticking WhatsApp on or off doesn't change
                    // the row's height (Davor, 24 Nov). The box sits flush with the row's end, like the other marks.
                    Box(Modifier.offset(x = 13.dp).size(48.dp).clip(RoundedCornerShape(12.dp))
                        .then(if (chats) Modifier.clickable(role = Role.Checkbox, onClickLabel = "Stop ${app.label} buzzing", onClick = toggle) else Modifier),
                        contentAlignment = Alignment.Center) { Mark(app.pkg in s.apps, radio = false) }
                }
            }
            if (query.isBlank()) item(key = "notes") {
                Column(Modifier.padding(top = Space.TIGHT.dp), verticalArrangement = Arrangement.spacedBy(Space.TIGHT.dp)) {
                    RingersNote(s)
                    Text("Tap a ticked WhatsApp, Telegram or Discord to pick its chats. A\u00A0chat is a person, a group or a\u00A0channel.", style = Type.small.copy(lineBreak = LineBreak.Paragraph), color = a.muted,
                        modifier = Modifier.fillMaxWidth())
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
        listOf(1 to "Buzz once", 2 to "Buzz twice", 3 to "Buzz three times").forEachIndexed { i, (n, name) ->
            LineRow(i == 0, onClick = { onSelect(n) }) {
                Mark(taps == n, radio = true)
                Text(name, style = Type.bodyMedium, color = a.ink, modifier = Modifier.weight(1f))
                Box(Modifier.width(40.dp)) { Rhythm(n) }
                Pill("Try", { onTry(n) }, filled = false, small = true)
            }
        }
    }
    Text("Calls always ring until you answer: a buzz every second, for up to a minute.", style = Type.small, color = a.muted)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietSheet(engine: Engine, s: Settings, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    val pick = rememberContactPicker(engine, ringAnyway = true)
    Sheet("Stay quiet", onClose) {
        ScrollUnderFade { QuietBody(s, { change -> engine.store.update(change) }, pick) { editing = it } }
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
        if (s.ringAnyway) Text(when (s.callMode) {
            CallMode.CHOSEN -> "Your chosen callers ring outside quiet times. In quiet times and pauses, only people you add here ring."
            CallMode.CONTACTS -> "Your contacts ring outside quiet times. In quiet times and pauses, only people you add here ring."
            CallMode.ANYONE -> "Every caller rings outside quiet times. Only people you add here ring in quiet times and pauses too."
        }, style = Type.small.copy(lineBreak = LineBreak.Heading), color = a.muted)
        if (s.ringAnyway && s.callMode != CallMode.CHOSEN) {
            TextAction("Add people", pick)
            PeopleRows(s.ringAnywayPeople) { list -> update { it.copy(ringAnywayPeople = list) } }
        }
        if (!s.ringAnyway) Text("While it's off, quiet times silence every call.", style = Type.small, color = a.muted)
    }
    // In Chosen, ring anyway picks from the chosen callers, so everyone added can ring (Davor, 18 Nov). Someone else
    // picked from contacts becomes a chosen caller too. Anyone on the list who isn't chosen, from before or from
    // another mode, waits under "Can't ring yet" until they are added to Who can ring or removed.
    if (s.ringAnyway && s.callMode == CallMode.CHOSEN) {
        if (ringingAnyway(s).isNotEmpty()) Column {
            Label("Rings anyway")
            Gap(Space.HINT)
            PeopleRows(ringingAnyway(s), topLine = false) { list -> update { it.copy(ringAnywayPeople = list + it.ringAnywayPeople.filter { p -> !isChosen(it, p) }) } }
        }
        val rest = s.people.filter { p -> ringingAnyway(s).none { it == p || (p.number != null && it.number == p.number) } }
        val waiting = s.ringAnywayPeople.filter { !isChosen(s, it) }
        if (waiting.isNotEmpty()) Column {
            Label("Can't ring yet · not in Who can ring")
            Gap(Space.HINT)
            waiting.forEachIndexed { i, p ->
                LineRow(i == 0) {
                    Avatar(p.name)
                    TwoLine(p.name, Modifier.weight(1f)) { p.number?.let { Label(Rules.display(it)) } }
                    Pill("Allow", { update { st -> st.copy(people = st.people + p) } }, filled = false, small = true)
                    IconAction("Remove ${p.name}", { update { st -> st.copy(ringAnywayPeople = st.ringAnywayPeople - p) } }) {
                        GlyphIcon(Glyph.CLOSE, 18.dp, a.muted)
                    }
                }
            }
        }
        Column {
            if (rest.isNotEmpty()) { Label("From Who can ring"); Gap(Space.HINT) }
            rest.forEachIndexed { i, p ->
                LineRow(i == 0) {
                    Avatar(p.name)
                    TwoLine(p.name, Modifier.weight(1f)) { p.number?.let { Label(Rules.display(it)) } }
                    Pill("Add", { update { st -> st.copy(ringAnywayPeople = st.ringAnywayPeople + p) } }, filled = false, small = true)
                }
            }
            TextAction(if (rest.isEmpty()) "Add people" else "Someone else", pick)
            Text("They join Who can ring too.", style = Type.small, color = a.muted)
        }
    }
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
        PauseBody(options, choice, ringsAnyway(s)?.let { "$it still ${ring(ringingAnyway(s).size)} while paused, but not when AirBuzz is off." }
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
