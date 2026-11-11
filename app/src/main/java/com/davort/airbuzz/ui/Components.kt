package com.davort.airbuzz.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.davort.airbuzz.data.Entry
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.data.Result
import java.util.Calendar

/** One piece of a rule sentence: plain words, or a blank that is one setting. */
sealed interface Part {
    data class Words(val text: String) : Part
    /** [label] names the setting for TalkBack; [after] is punctuation kept on the blank's line. */
    data class Blank(val label: String, val value: String, val after: String = "", val onClick: () -> Unit) : Part
}

val SentenceStyle: TextStyle @Composable get() = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight(400))

/**
 * A rule as a wrapping row of words, where each blank is one tinted tap target with one arrow.
 * A blank never splits across lines because it is a single item in the row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Sentence(parts: List<Part>, modifier: Modifier = Modifier) {
    val air = LocalAirColors.current
    val style = SentenceStyle
    FlowRow(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parts.forEach { part ->
            when (part) {
                is Part.Words -> part.text.split(" ").filter { it.isNotEmpty() }.forEach { word ->
                    Text("$word ", style = style, modifier = Modifier.padding(vertical = 2.dp))
                }
                is Part.Blank -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(air.tint)
                            .clickable(onClick = part.onClick)
                            .clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = "${part.label}: ${part.value}"
                            }
                            .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(part.value, style = style.copy(fontWeight = FontWeight(560)), color = air.accent)
                        Chevron(air.accent, Modifier.padding(start = 2.dp).size(16.dp))
                    }
                    Text(part.after + " ", style = style)
                }
            }
        }
    }
}

@Composable
fun Chevron(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.13f
        drawLine(color, Offset(w * 0.22f, h * 0.38f), Offset(w * 0.5f, h * 0.66f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.66f), Offset(w * 0.78f, h * 0.38f), stroke, StrokeCap.Round)
    }
}

@Composable
fun BackArrow(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.82f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.45f, h * 0.25f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.45f, h * 0.75f), stroke, StrokeCap.Round)
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = LocalAirColors.current.muted) {
    Text(text.uppercase(), style = MonoLabel, color = color, modifier = modifier)
}

/** What you will feel: bars for a ring, dots for taps. */
@Composable
fun RhythmMarks(ring: Boolean, taps: Int, modifier: Modifier = Modifier) {
    val accent = LocalAirColors.current.accent
    Row(modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ring) {
            listOf(1f, 1f, 1f, 0.55f, 0.25f).forEach { a ->
                Box(Modifier.size(width = 5.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha = a)))
            }
        } else {
            repeat(taps) { Box(Modifier.size(11.dp).clip(RoundedCornerShape(50)).background(accent)) }
        }
    }
}

/** Today's alerts on a 24-hour line: tall accent marks buzzed, short grey marks stayed quiet. */
@Composable
fun BuzzStrip(entries: List<Entry>, modifier: Modifier = Modifier) {
    val air = LocalAirColors.current
    val dayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val today = entries.filter { it.time >= dayStart && it.kind != Kind.TEST }
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        val base = size.height - 2.dp.toPx()
        drawLine(air.line, Offset(0f, base), Offset(size.width, base), 2.dp.toPx())
        today.forEach { e ->
            val x = ((e.time - dayStart) / 86_400_000f).coerceIn(0f, 1f) * (size.width - 4.dp.toPx()) + 2.dp.toPx()
            val buzzed = e.result == Result.BUZZED
            val h = if (buzzed) 28.dp.toPx() else 10.dp.toPx()
            drawLine(if (buzzed) air.accent else air.quietMark, Offset(x, base), Offset(x, base - h),
                if (buzzed) 3.5.dp.toPx() else 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** A white card section in a grouped list. */
@Composable
fun Section(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 20.dp, vertical = 18.dp)) {
        content()
    }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int) = Spacer(Modifier.width(w.dp))
