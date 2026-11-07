package dev.skyward.rf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.ui.theme.Sky

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Sky.surface)
            .border(1.dp, Sky.outline, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                style = SkywardThemeLabel(),
                color = Sky.textDim,
            )
            Spacer(Modifier.weight(1f))
            if (trailing != null) {
                Text(trailing, style = SkywardThemeLabel(), color = Sky.cyan)
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
fun SkywardThemeLabel() = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    letterSpacing = 1.2.sp,
    fontWeight = FontWeight.Medium,
)

@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier, accent: Color = Sky.text) {
    Column(modifier) {
        Text(label.uppercase(), style = SkywardThemeLabel(), color = Sky.textDim)
        Spacer(Modifier.height(2.dp))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent)
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Sky.surface.copy(alpha = 0.92f))
            .border(1.dp, Sky.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(color, 7)
        Text(text, style = SkywardThemeLabel(), color = Sky.text)
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = Sky.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = SkywardThemeLabel(), color = Sky.textDim, modifier = Modifier.width(104.dp))
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = valueColor,
        )
    }
}

@Composable
fun SwatchRow(color: Color, label: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(18.dp)
                .height(9.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Sky.text)
        Spacer(Modifier.weight(1f))
        Text(detail, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Sky.textDim)
    }
}

/**
 * A live count that doubles as a layer/radio toggle.
 *
 * Off state dims the chip and the dot but keeps the count visible, so "hidden" is clearly
 * different from "nothing there".
 */
@Composable
fun ToggleChip(
    label: String,
    count: Int?,
    color: Color,
    on: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (on) Sky.surfaceHigh else Sky.surface.copy(alpha = 0.72f))
            .border(1.dp, if (on) color.copy(alpha = 0.5f) else Sky.outline, RoundedCornerShape(9.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (on) color else Sky.textDim, 6)
        Spacer(Modifier.width(5.dp))
        Text(label, style = SkywardThemeLabel(), color = if (on) Sky.text else Sky.textDim, fontSize = 9.sp)
        if (count != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                count.toString(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (on) color else Sky.textDim,
            )
        }
    }
}

/**
 * Column titles for the INTEL table: the live count, then the logged count.
 *
 * The two numbers share a row in the table below, so they are named once at the top instead of
 * a separate heading over each half.
 */
@Composable
fun IntelPairHeader() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(46.dp))
        Box(Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(
            "LIVE",
            style = SkywardThemeLabel(),
            fontSize = 8.sp,
            color = Sky.textDim,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
        Text(
            "LOGGED",
            style = SkywardThemeLabel(),
            fontSize = 8.sp,
            color = Sky.textDim,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}

/**
 * One row of the INTEL table: a one-word label and the two numbers that belong to it.
 *
 * A row used to belong to a LIVE table or a LOGGED table, so every layer name was printed twice
 * and the count you were comparing sat a table away. One label and two numbers reads both states
 * in one glance.
 *
 * A null count prints a dash: the track and the individual networks are not a logged entity in
 * their own right, and a sub-GHz carrier has no live count to give. A switched-off layer dims its
 * label but keeps both numbers, so "hidden" never looks like "zero".
 */
@Composable
fun IntelPairRow(
    label: String,
    live: Int?,
    logged: Int?,
    on: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = SkywardThemeLabel(),
            fontSize = 9.sp,
            color = if (on) Sky.text else Sky.textDim,
            modifier = Modifier.width(46.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Sky.outline.copy(alpha = 0.35f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            live?.toString() ?: DASH,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (on && live != null) Sky.cyan else Sky.textDim,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
        Text(
            logged?.toString() ?: DASH,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (logged != null && logged > 0) Sky.logged else Sky.textDim,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}

/** The missing half of a row: no count exists, which is not the same as a count of zero. */
private const val DASH = "\u2014"
