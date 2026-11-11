package com.davort.airbuzz.ui

import android.provider.Settings as SystemSettings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davort.airbuzz.Engine
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.data.Result
import com.davort.airbuzz.rules.Settings
import com.davort.airbuzz.rules.ThemeMode
import java.text.DateFormat
import java.util.Date
import kotlin.math.sin

@Composable
fun SettingsScreen(engine: Engine, s: Settings, go: (Screen) -> Unit) {
    val air = LocalAirColors.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Settings", onBack = { go(Screen.HOME) })
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Section {
                Label("Strength")
                Gap(10)
                Choice(listOf(false to "Gentle", true to "Strong"), s.strong) { strong -> engine.store.update { it.copy(strong = strong) } }
                Gap(10)
                Text("Strong is easier to feel but uses more of your Air's battery. The Air has one strength, so this also sets how strongly its alarms buzz.",
                    style = MaterialTheme.typography.bodyMedium, color = air.muted)
            }
            NavRow("Test buzz", "See how long your Air takes to buzz") { go(Screen.TEST) }
            NavRow("Activity", "Every alert this week and what AirBuzz did") { go(Screen.ACTIVITY) }
            NavRow("Appearance", "Theme and accent colours") { go(Screen.APPEARANCE) }
            NavRow("Setup checklist", "Permissions and background running") { go(Screen.SETUP) }
        }
        Gap(20)
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Works with Google Fitbit Air. AirBuzz is not made by, endorsed by or affiliated with Google or Fitbit. It talks to the Air over an unofficial protocol, so a firmware update could stop it working.",
                style = MaterialTheme.typography.bodySmall, color = air.muted)
            Gap(8)
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            Text("AirBuzz $version · Free software under the GNU GPL v3", style = MonoLabel, color = air.muted)
            Gap(24)
        }
    }
}

@Composable
fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest).clickable(onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = LocalAirColors.current.muted)
        }
        Chevron(LocalAirColors.current.muted, Modifier.size(20.dp).padding(2.dp).rotate(-90f))
    }
}

@Composable
fun AppearanceScreen(engine: Engine, s: Settings, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Appearance", onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Section {
                Label("Theme")
                Gap(10)
                Choice(listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), s.theme) { t ->
                    engine.store.update { it.copy(theme = t) }
                }
            }
            Section {
                Label("Light accent · ${LightAccents.getOrElse(s.lightAccent) { LightAccents[0] }.name}")
                Gap(12)
                Swatches(LightAccents, s.lightAccent, Color.White) { i -> engine.store.update { it.copy(lightAccent = i) } }
                Gap(14)
                Preview(dark = false, accent = LightAccents.getOrElse(s.lightAccent) { LightAccents[0] }.color)
            }
            Section {
                Label("Dark accent · ${DarkAccents.getOrElse(s.darkAccent) { DarkAccents[0] }.name}")
                Gap(12)
                Swatches(DarkAccents, s.darkAccent, Color(0xFF0E0E0E)) { i -> engine.store.update { it.copy(darkAccent = i) } }
                Gap(14)
                Preview(dark = true, accent = DarkAccents.getOrElse(s.darkAccent) { DarkAccents[0] }.color)
            }
        }
        Gap(24)
    }
}

@Composable
fun Swatches(accents: List<Accent>, selected: Int, mark: Color, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        accents.forEachIndexed { i, a ->
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(a.color).clickable { onSelect(i) }
                .semantics { contentDescription = a.name + if (i == selected) ", selected" else "" },
                contentAlignment = Alignment.Center) {
                if (i == selected) Box(Modifier.size(16.dp).clip(RoundedCornerShape(50)).background(mark.copy(alpha = 0.9f)))
            }
        }
    }
}

/** A sentence card drawn in the other mode's palette, so both accents can be judged from either mode. */
@Composable
fun Preview(dark: Boolean, accent: Color) {
    val c = airColors(dark, accent)
    val bg = if (dark) Color(0xFF191919) else Color.White
    val ink = if (dark) Color(0xFFECECEA) else Color(0xFF141414)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("When ", color = ink, style = MaterialTheme.typography.titleMedium)
            Text(" Mum and 3 more ", color = accent, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight(560)),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.tint))
            Text(" call,", color = ink, style = MaterialTheme.typography.titleMedium)
        }
        Text("ring my Air.", color = ink, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Test buzz: the band drawing pulses signal arcs while AirBuzz connects, then shakes with vibration marks when the
 * Air acknowledges the tap, with one phone haptic. The marks stay, next to the measured time.
 */
@Composable
fun TestBuzzScreen(engine: Engine, onBack: () -> Unit) {
    val air = LocalAirColors.current
    val run by engine.test.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    val reduceMotion = remember {
        SystemSettings.Global.getFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val shake = remember { Animatable(0f) }
    val buzzedMs = run?.buzzedMs
    LaunchedEffect(buzzedMs) {
        if (buzzedMs != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (!reduceMotion) { shake.snapTo(0f); shake.animateTo(1f, tween(1000, easing = LinearEasing)) }
        }
    }
    DisposableEffect(Unit) { onDispose { engine.clearTest() } }
    val r = run?.result
    val connecting = run != null && buzzedMs == null && r == null
    var didNotFeel by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Test buzz", onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Band(connecting, buzzed = buzzedMs != null, shake = shake.value, modifier = Modifier.size(150.dp))
                HGap(8)
                Column {
                    Label(when {
                        run == null -> "Ready"
                        connecting -> "Connecting"
                        buzzedMs != null -> "Tap to buzz"
                        else -> "Couldn't reach your Air"
                    })
                    Text(if (buzzedMs != null) seconds(buzzedMs) else if (connecting) "…" else "–",
                        style = MaterialTheme.typography.displayMedium.copy(fontSize = 52.sp, fontWeight = FontWeight(500)))
                    r?.let { Label(if (it.link == "open") "Open link" else if (it.link == "open→new") "Open link, then new" else "New connection") }
                }
            }
            Gap(14)
            Timeline(buzzedMs)
            Gap(18)
            if (r != null && !r.ok && r.taps == 0) {
                Text(r.message, style = MaterialTheme.typography.bodyLarge, color = air.warn)
                Gap(12)
            }
            r?.phases?.let { phases ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(16.dp)) {
                    phases.forEach { (name, ms) ->
                        Row { Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(seconds(ms), style = MonoLabel.copy(fontSize = 13.sp)) }
                    }
                    if (r.wrongKeys + r.lostRaces > 0) {
                        Gap(6)
                        Text("Google Health was using your Air, so AirBuzz waited and tried again.", style = MaterialTheme.typography.bodySmall, color = air.muted)
                    }
                }
                Gap(16)
            }
            if (r != null && r.taps > 0 && !didNotFeel) {
                Text("Did your Air buzz?", style = MaterialTheme.typography.titleMedium)
                Gap(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBack) { Text("Yes") }
                    OutlinedButton(onClick = { didNotFeel = true }) { Text("No") }
                }
                Gap(12)
            }
            if (didNotFeel) {
                Text("The Air confirmed the tap, so the motor was asked to run. Try Strong in Settings, and check the band is snug on your wrist.",
                    style = MaterialTheme.typography.bodyMedium, color = air.muted)
                Gap(12)
            }
            Button(onClick = { didNotFeel = false; engine.clearTest(); engine.testBuzz() }, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
                Text(if (run == null) "Buzz my Air" else "Test again")
            }
            Gap(24)
        }
    }
}

/** The band as a line drawing: arcs pulse while connecting, then it shakes and vibration marks spread to both sides. */
@Composable
fun Band(connecting: Boolean, buzzed: Boolean, shake: Float, modifier: Modifier = Modifier) {
    val air = LocalAirColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    // The arcs animate only while connecting; an endless animation would keep the screen redrawing.
    val pulse = if (connecting) {
        rememberInfiniteTransition(label = "arcs").animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "pulse").value
    } else 0f
    Canvas(modifier.semantics { contentDescription = if (buzzed) "Your Air buzzed" else if (connecting) "Connecting to your Air" else "Your Air" }) {
        val w = size.width
        val h = size.height
        val bandW = w * 0.22f
        val bandH = h * 0.78f
        val dx = if (shake in 0.001f..0.999f) sin(shake * 40f) * (1f - shake) * w * 0.035f else 0f
        translate(left = dx) {
            val left = (w - bandW) / 2
            val top = (h - bandH) / 2
            drawRoundRect(ink, Offset(left, top), Size(bandW, bandH), CornerRadius(bandW / 2), style = Stroke(2.dp.toPx()))
            drawRoundRect(ink, Offset(left + bandW * 0.22f, h * 0.38f), Size(bandW * 0.56f, h * 0.24f), CornerRadius(bandW * 0.2f), style = Stroke(1.5.dp.toPx()))
        }
        val cx = w / 2
        val cy = h / 2
        if (connecting) {
            for (i in 0..1) {
                val p = (pulse + i * 0.5f) % 1f
                val rad = bandW * 0.9f + p * w * 0.3f
                drawArc(air.accent.copy(alpha = 1f - p), -40f, 80f, false, Offset(cx - rad, cy - rad), Size(rad * 2, rad * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        if (buzzed) {
            val spread = if (shake > 0f) shake.coerceAtMost(1f) else 1f
            listOf(0.65f to 0.18f, 0.85f to 0.11f).forEach { (dist, half) ->
                val off = bandW / 2 + spread * dist * w * 0.35f
                for (side in listOf(-1f, 1f)) {
                    val x = cx + side * off
                    drawLine(air.accent, Offset(x, cy - h * half), Offset(x, cy + h * half), 3.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

/** Measured time on a 0–8 s scale, with AirBuzz's 3 s target shaded. */
@Composable
fun Timeline(buzzedMs: Long?) {
    val air = LocalAirColors.current
    Column {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val w = size.width
            val y = size.height / 2
            drawRoundRect(air.tint, Offset(0f, y - 8.dp.toPx()), Size(w * 3f / 8f, 16.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawLine(air.line, Offset(0f, y), Offset(w, y), 2.dp.toPx())
            for (s in 0..8) drawLine(air.quietMark, Offset(w * s / 8f, y - 4.dp.toPx()), Offset(w * s / 8f, y + 4.dp.toPx()), 1.dp.toPx())
            buzzedMs?.let {
                val x = (it / 8000f).coerceIn(0f, 1f) * w
                drawLine(air.accent, Offset(x, y - 12.dp.toPx()), Offset(x, y + 12.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
            }
        }
        Row {
            Label("0 s"); Box(Modifier.weight(3f)); Label("3 s target"); Box(Modifier.weight(4f)); Label("8 s")
        }
    }
}

@Composable
fun ActivityScreen(engine: Engine, onBack: () -> Unit) {
    val air = LocalAirColors.current
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(0) } // 0 all, 1 buzzed, 2 quiet
    val dayFormat = remember { DateFormat.getDateInstance(DateFormat.FULL) }
    val shown = entries.reversed().filter {
        when (filter) { 1 -> it.result == Result.BUZZED; 2 -> it.result != Result.BUZZED; else -> true }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Activity", onBack = onBack)
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Buzzed", "Quiet").forEachIndexed { i, label ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            if (shown.isEmpty()) item { Gap(16); Text("Nothing this week.", color = air.muted) }
            shown.groupBy { dayFormat.format(Date(it.time)) }.forEach { (day, rows) ->
                item(key = "day$day") { Gap(14); Label(day); Gap(4) }
                items(rows) { EntryRow(it) }
            }
            item { Gap(24) }
        }
    }
}

@Composable
fun SetupScreen(engine: Engine, go: (Screen) -> Unit, onBack: () -> Unit) {
    val air = LocalAirColors.current
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val entries by engine.log.entries.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    val fixer = rememberFixer(go) { error = it }
    val testedOk = entries.any { it.kind == Kind.TEST && it.result == Result.BUZZED }
    val checks = remember(tick, testedOk) { Setup.checks(context, testedOk) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Setup checklist", onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            checks.forEach { c ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val color = when (c.done) { true -> air.ok; false -> air.warn; null -> air.muted }
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
                    HGap(14)
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge)
                        Text(c.detail, style = MaterialTheme.typography.bodyMedium, color = air.muted)
                    }
                    if (c.done != true && c.fix != null) TextButton(onClick = { error = null; fixer(c.fix) }) { Text(c.fixLabel ?: "Fix") }
                }
            }
        }
        error?.let { Gap(12); Text(it, color = air.warn, modifier = Modifier.padding(horizontal = 24.dp)) }
        Gap(24)
    }
}
