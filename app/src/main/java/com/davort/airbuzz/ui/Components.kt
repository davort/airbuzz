package com.davort.airbuzz.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.davort.airbuzz.data.Entry
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.data.Result

// Glyphs, drawn on a 24-unit grid like the design's SVG icons.

enum class Glyph(val lines: List<List<Offset>>) {
    BACK(listOf(listOf(Offset(15f, 5f), Offset(8f, 12f), Offset(15f, 19f)))),
    CHEVRON(listOf(listOf(Offset(9f, 5f), Offset(16f, 12f), Offset(9f, 19f)))),
    DOWN(listOf(listOf(Offset(6f, 9f), Offset(12f, 15f), Offset(18f, 9f)))),
    UP(listOf(listOf(Offset(6f, 15f), Offset(12f, 9f), Offset(18f, 15f)))),
    PLUS(listOf(listOf(Offset(12f, 5f), Offset(12f, 19f)), listOf(Offset(5f, 12f), Offset(19f, 12f)))),
    CHECK(listOf(listOf(Offset(5f, 12.5f), Offset(9.5f, 17f), Offset(19f, 7.5f)))),
    PAUSE(listOf(listOf(Offset(9f, 6f), Offset(9f, 18f)), listOf(Offset(15f, 6f), Offset(15f, 18f)))),
    CLOSE(listOf(listOf(Offset(6.5f, 6.5f), Offset(17.5f, 17.5f)), listOf(Offset(17.5f, 6.5f), Offset(6.5f, 17.5f)))),
}

/** Icons with curves, as SVG paths on the same 24-unit grid, drawn from the Paper design. */
enum class Curve(val d: String) {
    SIGNAL_OFF("M3 9a13 13 0 0 1 18 0M6 12.5a8.5 8.5 0 0 1 12 0M9 16a4 4 0 0 1 6 0M12 19.5h.01M4 3l16 18"),
    BELL_OFF("M8.7 3.9A6 6 0 0 1 18 9c0 3.3.8 5.5 1.6 6.9M6.3 6.3A6 6 0 0 0 6 9c0 5.5-2.5 8-2.5 8H17M10.3 20a2 2 0 0 0 3.4 0M4 4l16 16"),
    BLUETOOTH_OFF("M12 12v9l4.5-4.5L14 14M12 12V3l4.5 4.5L14 10M12 12l-5 5M7 7l5 5M4 4l16 16"),
    /** Three slider lines with knobs, for Settings. */
    TUNE("M4 7h9M17 7h3M4 12h3M11 12h9M4 17h11M19 17h1M15 5v4M9 10v4M17 15v4"),
    BLUETOOTH("M7 7l10 10-5 5V2l5 5L7 17"),
    LINK("M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"),
    BELL("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a2 2 0 0 0 3.4 0"),
    PHONE("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"),
    APPS("M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z"),
    PEOPLE("M16 19v-1a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v1M9.5 10.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7M21 19v-1a4 4 0 0 0-3-3.87M15.5 3.63a3.5 3.5 0 0 1 0 6.75"),
    CONTACTS("M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a2.5 2.5 0 0 1 0-5H20M15 14a3 3 0 0 0-6 0M12 10.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4"),
    /** A phone with waves, for "Anyone". */
    CALLING("M14 2.5a8 8 0 0 1 7.5 7.5M14 6a4.5 4.5 0 0 1 4 4M21 16.5v3a2 2 0 0 1-2.2 2A18 18 0 0 1 2.5 5.2 2 2 0 0 1 4.5 3h3a2 2 0 0 1 2 1.7c.13.96.36 1.9.7 2.8a2 2 0 0 1-.45 2.1L8.5 10.9a15 15 0 0 0 4.6 4.6l1.3-1.3a2 2 0 0 1 2.1-.45c.9.34 1.84.57 2.8.7a2 2 0 0 1 1.7 2.05z"),
    MOON("M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z"),
    BIN("M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M10 11v6M14 11v6"),
}

@Composable
fun CurveIcon(curve: Curve, size: Dp = 24.dp, color: Color = LocalAirColors.current.ink, stroke: Float = 2f) {
    val path = remember(curve) { PathParser().parsePathString(curve.d).toPath() }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, pivot = Offset.Zero) { drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/** What the pebble drawing shows around the Air: nothing, grey rings pulsing while connecting, or accent rings for a tap. */
enum class Buzz { REST, CONNECTING, TAPPED }

/**
 * The Air as its black capsule seen from above (decision 38). The capsule fills the composable, half as tall as it is
 * wide, so its left edge lines up with text; tap rings are drawn outside it, up to 18 dp into the margin. [shiftPx] moves
 * the capsule sideways for the shake on a tap.
 */
@Composable
fun AirPebble(width: Dp, buzz: Buzz = Buzz.REST, shiftPx: Float = 0f, description: String? = null, wave: Float = 0f) {
    val a = LocalAirColors.current
    // A slow 1.2 s breath while connecting: a loader, so it may loop (decision 40).
    val pulse = if (buzz == Buzz.CONNECTING) {
        rememberInfiniteTransition(label = "rings").animateFloat(1f, 0.35f, infiniteRepeatable(tween(600, easing = Motion.InOut), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    Canvas(Modifier.size(width, width / 2).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val h = size.height
        val r = h / 2
        val ring = when (buzz) { Buzz.CONNECTING -> a.muted; Buzz.TAPPED -> a.accent; Buzz.REST -> null }
        if (ring != null) listOf(9.dp to 1f, 17.dp to if (buzz == Buzz.CONNECTING) 0.55f else 0.45f).forEach { (o, op) ->
            val p = o.toPx()
            drawRoundRect(ring.copy(alpha = op * pulse), Offset(-p, -p), Size(size.width + 2 * p, h + 2 * p), CornerRadius(r + p),
                style = Stroke(2.4.dp.toPx()))
        }
        // [wave] 0..1: one ring leaves the capsule when the Air taps, and fades as it goes.
        if (wave in 0.001f..0.999f) {
            val p = 4.dp.toPx() + wave * 22.dp.toPx()
            drawRoundRect(a.accent.copy(alpha = 0.95f * (1f - wave)), Offset(-p, -p), Size(size.width + 2 * p, h + 2 * p), CornerRadius(r + p),
                style = Stroke(2.4.dp.toPx()))
        }
        translate(left = shiftPx) {
            drawRoundRect(Pebble, cornerRadius = CornerRadius(r))
            if (a.dark) drawRoundRect(a.muted, cornerRadius = CornerRadius(r), style = Stroke(1.dp.toPx()))
            if (width >= 48.dp) drawLine(Color(0xFF3A3C40), Offset(size.width * 0.15f, h * 0.22f), Offset(size.width * 0.65f, h * 0.22f),
                h * 0.075f, StrokeCap.Round)
        }
    }
}

/** One CSS-style box shadow: offset, blur and colour. */
data class Shade(val y: Dp, val blur: Dp, val color: Color)

/**
 * Draws [shades] behind a rounded box of colour [fill], blurred like CSS box-shadow, which Compose's elevation shadows
 * can't match.
 */
fun Modifier.softShadow(corner: Dp, fill: Color, shades: List<Shade>) = drawBehind {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    shades.forEach { s ->
        paint.color = fill.toArgb()
        // CSS blur is twice the Gaussian sigma; Android's shadow radius is (sigma - 0.5) / 0.57735.
        paint.setShadowLayer(((s.blur.toPx() / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.1f), 0f, s.y.toPx(), s.color.toArgb())
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner.toPx(), corner.toPx(), paint) }
    }
}

@Composable
fun GlyphIcon(glyph: Glyph, size: Dp = 24.dp, color: Color = LocalAirColors.current.ink, stroke: Float = 2f, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        glyph.lines.forEach { pts ->
            val path = Path().apply {
                moveTo(pts[0].x * k, pts[0].y * k)
                pts.drop(1).forEach { lineTo(it.x * k, it.y * k) }
            }
            drawPath(path, color, style = Stroke(stroke * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The small down arrow inside a blank. */
@Composable
fun Caret(color: Color) {
    Canvas(Modifier.size(width = 13.dp, height = 11.dp)) {
        val k = size.width / 8f
        // Lowered so the arrow centres on the lowercase letters beside it, not on the pill.
        val y = 1.5.dp.toPx()
        val path = Path().apply { moveTo(1f * k, 1.5f * k + y); lineTo(4f * k, 4.5f * k + y); lineTo(7f * k, 1.5f * k + y) }
        drawPath(path, color, style = Stroke(1.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Small uppercase label in Google Sans Code. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = LocalAirColors.current.muted) {
    Text(text.uppercase(), style = Type.label, color = color, maxLines = 1, softWrap = false, modifier = modifier)
}

// Sentences with blanks (decision 11).

/** One piece of a rule sentence: plain words, or a blank that is one setting. */
sealed interface Part {
    data class Words(val text: String) : Part
    /**
     * [label] names the setting for TalkBack; [after] is punctuation kept on the blank's line; [open] while its sheet is up.
     * [shorter] are ever shorter wordings of [value]; the blank shows the longest one that fits on a line, so a name is
     * never cut, and the last one when none fit.
     */
    data class Blank(
        val label: String, val value: String, val after: String = "", val open: Boolean = false,
        val shorter: List<String> = emptyList(), val onClick: () -> Unit,
    ) : Part
}

/**
 * A rule as a wrapping row of words, where each blank is one tinted tap target with one arrow.
 * A blank never splits across lines because it is a single item in the row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Sentence(parts: List<Part>, modifier: Modifier = Modifier, style: TextStyle = Type.sentence) {
    val a = LocalAirColors.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val blankStyle = style.copy(fontWeight = FontWeight(560), lineHeight = 30.sp)
    BoxWithConstraints(modifier) {
    // The blank's padding, the gap before its arrow and the arrow itself.
    val chrome = with(density) { (8 + 8 + 3 + 13).dp.roundToPx() }
    fun fits(text: String, after: String) =
        measurer.measure(text, blankStyle, softWrap = false).size.width + chrome +
            (if (after.isEmpty()) 0 else measurer.measure(after, style, softWrap = false).size.width) <= constraints.maxWidth
    FlowRow(Modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { part ->
            when (part) {
                is Part.Words -> part.text.split(" ").filter { it.isNotEmpty() }.forEach { word ->
                    Text(word, style = style, color = a.ink, softWrap = false, modifier = Modifier.align(Alignment.CenterVertically))
                }
                is Part.Blank -> Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(100), label = "press")
                    Row(
                        Modifier
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (pressed || part.open) a.tintPressed else a.tint)
                            .clickable(interaction, indication = null, onClick = part.onClick)
                            .clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = "${part.label}: ${part.value}"
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A line as tall as the blank, so the words centre in it and share the sentence's baseline.
                        val shown = (listOf(part.value) + part.shorter).let { all -> all.firstOrNull { fits(it, part.after) } ?: all.last() }
                        Text(shown, style = blankStyle, color = a.accent, softWrap = false, maxLines = 1)
                        Spacer(Modifier.width(3.dp))
                        Caret(a.accent)
                    }
                    if (part.after.isNotEmpty()) Text(part.after, style = style, color = a.ink, softWrap = false)
                }
            }
        }
    }
    }
}

// Controls.

/** Material's switch in the design's colours: ink track when on, a muted ring when off. */
@Composable
fun AirSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val a = LocalAirColors.current
    Switch(checked, onChange, modifier, colors = SwitchDefaults.colors(
        checkedThumbColor = a.bg, checkedTrackColor = a.accent, checkedBorderColor = a.accent,
        uncheckedThumbColor = a.muted, uncheckedTrackColor = Color.Transparent, uncheckedBorderColor = a.muted,
    ))
}

/** Slim segmented button (decision 26): 40 dp, thin outline, the selected one filled with a check. */
@Composable
fun <T> Seg(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val a = LocalAirColors.current
    Row(modifier.fillMaxWidth().height(40.dp).clip(CircleShape).border(1.dp, a.outline, CircleShape).selectableGroup()) {
        options.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(a.outline))
            val on = value == selected
            Row(
                Modifier.weight(1f).fillMaxHeight().background(if (on) a.selected else Color.Transparent)
                    .selectable(on, role = Role.RadioButton) { onSelect(value) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) { GlyphIcon(Glyph.CHECK, 16.dp, a.ink); Spacer(Modifier.width(6.dp)) }
                Text(label, style = if (on) Type.smallMedium else Type.small, color = a.ink, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Pill button: filled in ink, or outlined. Large is 56 dp, small 40 dp. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, small: Boolean = false, enabled: Boolean = true) {
    val a = LocalAirColors.current
    val shape = CircleShape
    Box(
        modifier.height(if (small) 40.dp else 56.dp).alpha(if (enabled) 1f else 0.38f).clip(shape)
            .then(if (filled) Modifier.background(a.ink) else Modifier.border(BorderStroke(1.dp, a.outline), shape))
            .clickable(enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 16.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (small) Type.smallMedium else Type.bodyMedium, color = if (filled) a.onInk else a.ink, maxLines = 1, softWrap = false)
    }
}

/** An accent text action with a leading glyph, such as "+ Add people", 56 dp tall so it is an easy target. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, glyph: Glyph = Glyph.PLUS) {
    val a = LocalAirColors.current
    Row(Modifier.fillMaxWidth().height(56.dp).clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(glyph, 22.dp, a.accent)
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.bodyMedium, color = a.accent)
    }
}

/** 22 dp radio (ring and dot) or checkbox (filled square with a check). */
@Composable
fun Mark(on: Boolean, radio: Boolean, modifier: Modifier = Modifier) {
    val a = LocalAirColors.current
    val shape = if (radio) CircleShape else RoundedCornerShape(6.dp)
    Box(
        modifier.size(22.dp).clip(shape)
            .then(if (on && !radio) Modifier.background(a.ink) else Modifier.border(2.dp, if (on) a.ink else a.muted, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (on && radio) Box(Modifier.size(12.dp).clip(CircleShape).background(a.ink))
        if (on && !radio) GlyphIcon(Glyph.CHECK, 16.dp, a.onInk, 2.4f)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Avatar(name: String) {
    val a = LocalAirColors.current
    Box(Modifier.size(40.dp).clip(CircleShape).border(1.dp, a.line, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = Type.bodyMedium, color = a.ink)
    }
}

/** Title over a small label or detail. */
@Composable
fun TwoLine(title: String, modifier: Modifier = Modifier, sub: (@Composable () -> Unit)? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Type.bodyMedium, color = LocalAirColors.current.ink)
        sub?.invoke()
    }
}

/** Taps as dots, a ring as bars fading into dots; shown only in the pattern pickers (decision 22). */
@Composable
fun Rhythm(taps: Int, ring: Boolean = false) {
    val accent = LocalAirColors.current.accent
    if (ring) {
        Canvas(Modifier.size(width = 44.dp, height = 14.dp)) {
            val k = size.width / 44f
            for (i in 0 until 4) drawRoundRect(accent, Offset(i * 7f * k, 0f), androidx.compose.ui.geometry.Size(4f * k, 14f * k),
                androidx.compose.ui.geometry.CornerRadius(2f * k))
            listOf(0.8f, 0.5f, 0.25f).forEachIndexed { i, o -> drawCircle(accent.copy(alpha = o), 1.5f * k, Offset((31f + i * 5f) * k, 7f * k)) }
        }
    } else {
        val w = 10 + (taps - 1) * 13
        Canvas(Modifier.size(width = w.dp, height = 14.dp)) {
            val k = size.width / w
            for (i in 0 until taps) drawCircle(accent, 4.5f * k, Offset((5f + i * 13f) * k, 7f * k))
        }
    }
}

// Containers.

private fun groupShape(i: Int, n: Int): RoundedCornerShape {
    val top = if (i == 0) 24.dp else 4.dp
    val bottom = if (i == n - 1) 24.dp else 4.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** A grouped list: items on the surface colour, 2 dp apart, with 24 dp outer corners. */
@Composable
fun Group(items: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    val a = LocalAirColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, item ->
            Box(Modifier.fillMaxWidth().clip(groupShape(i, items.size)).background(a.surface)) { item() }
        }
    }
}

/** One row in a [Group]. */
@Composable
fun GroupRow(onClick: (() -> Unit)? = null, vertical: Dp = 14.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, gap: Dp = 10.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalAirColors.current.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(gap), content = content,
    )
}

/** A label on the left and something on the right. */
@Composable
fun LabelRow(left: String, right: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(left, Modifier.weight(1f))
        right()
    }
}

/**
 * A screen body: 20 dp side margins and a 12 dp gap between children. It scrolls when it must, and otherwise fills the
 * screen so a `Modifier.weight(1f)` spacer can push the last items to the bottom.
 */
@Composable
fun Page(gap: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(gap), content = content,
        )
    }
}

/** Top bar of an inner screen: back arrow (a down chevron when it [close]s a sheet), serif title, and optional content on the right. */
@Composable
fun ScreenBar(title: String, onBack: () -> Unit, close: Boolean = false, right: @Composable RowScope.() -> Unit = {}) {
    val a = LocalAirColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 32.dp, height = 48.dp).clickable(onClickLabel = if (close) "Close" else "Back", role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.CenterStart) {
            GlyphIcon(if (close) Glyph.DOWN else Glyph.BACK, 24.dp, a.ink)
        }
        Text(title, style = Type.heading, color = a.heading, modifier = Modifier.weight(1f).semantics { heading() })
        right()
    }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int) = Spacer(Modifier.width(w.dp))

// History marks and the buzz strip (decisions 21 and 23).

enum class Fate { BUZZED, IGNORED, FAILED }

val Entry.fate: Fate get() = when (result) {
    Result.BUZZED -> Fate.BUZZED
    Result.FAILED -> Fate.FAILED
    Result.SKIPPED, Result.MERGED -> Fate.IGNORED
}

/** The history glyph, the same marks as the strip: tall accent = buzzed, short grey = ignored, tall warning = failed. */
@Composable
fun BarMark(fate: Fate) {
    val a = LocalAirColors.current
    val (h, color) = when (fate) { Fate.BUZZED -> 14 to a.accent; Fate.IGNORED -> 6 to a.context; Fate.FAILED -> 14 to a.warn }
    Box(Modifier.size(width = 10.dp, height = 16.dp), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.size(width = 3.dp, height = h.dp).clip(RoundedCornerShape(1.dp)).background(color))
    }
}

/** A day's alerts on a 24-hour line. Tests are left out. */
@Composable
fun BuzzStrip(entries: List<Entry>, dayStart: Long, modifier: Modifier = Modifier, height: Dp = 28.dp, tall: Dp = 16.dp, short: Dp = 6.dp, hours: Boolean = false) {
    val a = LocalAirColors.current
    val day = entries.filter { it.time in dayStart until dayStart + 86_400_000L && it.kind != Kind.TEST }
    // Bars already there when the strip appears stand still; one that arrives later grows from the axis (decision 40).
    val known = remember(dayStart) { day.map { it.time }.toMutableSet() }
    val grow = remember(dayStart) { mutableStateMapOf<Long, Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(day.size, dayStart) {
        day.filter { it.time !in known }.forEach { e ->
            known += e.time
            val an = Animatable(0f).also { grow[e.time] = it }
            launch { an.animateTo(1f, tween(360, easing = Motion.Out)) }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width - 3.dp.toPx()
            val axis = size.height - 5.dp.toPx()
            drawRect(a.line, Offset(0f, axis - 1.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
            // Ignored first, so buzzed and failed marks sit on top.
            day.sortedBy { it.fate != Fate.IGNORED }.forEach { e ->
                val x = ((e.time - dayStart) / 86_400_000f).coerceIn(0f, 1f) * w
                val (full, color) = when (e.fate) { Fate.BUZZED -> tall to a.accent; Fate.IGNORED -> short to a.context; Fate.FAILED -> tall to a.warn }
                val k = if (e.time in known) grow[e.time]?.value ?: 1f else 0f
                val h = full * k
                val bottom = size.height - 6.dp.toPx()
                drawRoundRect(color, Offset(x, bottom - h.toPx()), androidx.compose.ui.geometry.Size(3.dp.toPx(), h.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
            }
        }
        if (hours) Row(Modifier.fillMaxWidth()) {
            listOf("00", "06", "12", "18", "24").forEachIndexed { i, h ->
                if (i > 0) Spacer(Modifier.weight(1f))
                Label(h)
            }
        }
    }
}
