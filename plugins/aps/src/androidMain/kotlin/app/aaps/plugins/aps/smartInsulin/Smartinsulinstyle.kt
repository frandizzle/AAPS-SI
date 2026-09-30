package app.aaps.plugins.aps.smartInsulin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsTheme

/*
 * Visual language for the SmartInsulin tab: 4.0 Material 3 cards and type, with every colour taken
 * from the theme so the tab reads correctly in light and dark.
 *
 * The card builders ([SmartInsulinTabCards]) still hand over the 3.4 fragment's ARGB ints. Rather than
 * rewrite ~1000 lines of wording logic, [siTone] classifies each int by hue into a meaning (good,
 * warning, critical, info, P/F, neutral) and the theme decides what that meaning looks like.
 */

enum class SiTone { GOOD, WARNING, CRITICAL, INFO, SPECIAL, STRONG, MUTED }

/** Classify a 3.4 hard-coded colour into what it meant. */
fun siTone(argb: Int): SiTone {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    val (h, s, v) = Triple(hsv[0], hsv[1], hsv[2])
    return when {
        s < 0.25f -> if (v > 0.8f) SiTone.STRONG else SiTone.MUTED
        h < 18f || h >= 340f -> SiTone.CRITICAL
        h < 65f  -> SiTone.WARNING
        h < 170f -> SiTone.GOOD
        h < 250f -> SiTone.INFO
        else     -> SiTone.SPECIAL
    }
}

@Composable
fun SiTone.color(): Color = when (this) {
    SiTone.GOOD     -> AapsTheme.generalColors.statusNormal
    SiTone.WARNING  -> AapsTheme.generalColors.statusWarning
    SiTone.CRITICAL -> AapsTheme.generalColors.statusCritical
    SiTone.INFO     -> AapsTheme.elementColors.insulin
    SiTone.SPECIAL  -> MaterialTheme.colorScheme.tertiary
    SiTone.STRONG   -> MaterialTheme.colorScheme.onSurface
    SiTone.MUTED    -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun siColor(argb: Int): Color = siTone(argb).color()

// ── Card ─────────────────────────────────────────────────────────────────────

/**
 * A tab section: tinted icon badge, title, optional one-line subtitle, and a chevron that folds the
 * body away. The fold state survives recomposition and rotation, keyed by [title].
 */
@Composable
fun SiSection(
    title: String,
    icon: ImageVector,
    accent: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f))
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(
                Icons.Filled.ExpandMore, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevron)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}

// ── Items ────────────────────────────────────────────────────────────────────

@Composable
fun SiItems(items: List<SiItem>, onAction: (SiAction) -> Unit) {
    items.forEachIndexed { i, item ->
        SiItemView(item, onAction, first = i == 0)
    }
}

@Composable
private fun SiItemView(item: SiItem, onAction: (SiAction) -> Unit, first: Boolean) {
    val top = if (first) 0.dp else 10.dp
    when (item) {
        is SiItem.Row    -> SiRow(item, Modifier.padding(top = top))

        SiItem.Divider   -> HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

        is SiItem.Mono   -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = top)
        ) {
            val tabular = looksTabular(item.text)
            Text(
                if (tabular) item.text.trimEnd() else reflow(item.text),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (tabular) FontFamily.Monospace else null,
                color = siColor(item.color).let { if (siTone(item.color) == SiTone.STRONG) MaterialTheme.colorScheme.onSurface else it },
                modifier = Modifier.padding(10.dp)
            )
        }

        is SiItem.Note   -> Text(
            reflow(item.text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = top)
        )

        is SiItem.Header -> Text(
            item.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = if (first) 0.dp else 16.dp)
        )

        is SiItem.Action -> TextButton(onClick = { onAction(item.action) }, modifier = Modifier.padding(top = 2.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(item.text.trimStart('►', '▼', '▶', ' '), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A status line: coloured headline, with ✓ / ✗ prefixes turned into icons, and a muted explanation. */
@Composable
private fun SiRow(row: SiItem.Row, modifier: Modifier) {
    val tone = siTone(row.color)
    val color = tone.color()
    val (icon, text) = when {
        row.primary.startsWith("✓") -> Icons.Filled.CheckCircle to row.primary.removePrefix("✓").trim()
        row.primary.startsWith("✗") -> Icons.Filled.Cancel to row.primary.removePrefix("✗").trim()
        else                        -> null to row.primary.trim()
    }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.titleSmall, color = color)
        }
        row.detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                if (looksTabular(it)) it.trimEnd() else reflow(it),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (looksTabular(it)) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = if (icon != null) 26.dp else 0.dp)
            )
        }
    }
}

/**
 * The 3.4 strings were wrapped by hand for a fixed-width TextView, so sentences break mid-line
 * ("how much extra insulin the\nresistant phase"). Join a break when it falls mid-sentence - the
 * line before doesn't end a clause and the next starts lower-case - and keep deliberate ones.
 */
fun reflow(text: String): String {
    val lines = text.trimEnd().lines()
    if (lines.size < 2) return text.trimEnd()
    val sb = StringBuilder(lines[0])
    for (i in 1 until lines.size) {
        val prev = lines[i - 1].trimEnd()
        val next = lines[i]
        val midSentence = prev.isNotEmpty() && next.isNotEmpty() &&
            prev.last() !in ".:;!?)" && next.first().isLowerCase()
        sb.append(if (midSentence) " " else "\n").append(if (midSentence) next.trimStart() else next)
    }
    return sb.toString()
}

/** Column-aligned text (runs of spaces between values, or `key=value` fields) keeps a fixed-width font. */
fun looksTabular(text: String): Boolean =
    text.lines().any { line -> Regex("""\S {2,}\S""").containsMatchIn(line.trim()) } ||
        text.lines().count { Regex("""\w+=\S+""").containsMatchIn(it) } >= 1

// ── Small building blocks ────────────────────────────────────────────────────

/** Rounded pill with a coloured dot, for the mode / learning / activity state line. */
@Composable
fun SiPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
        }
    }
}

/** Headline number with a label above and a qualifier below. */
@Composable
fun SiStat(label: String, value: String, sub: String?, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/** Thin rounded bar; [fraction] of it filled with [color]. */
@Composable
fun SiMeter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Int = 6) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .height(height.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f).height(height.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun SiSpacer(h: Int = 12) = Spacer(Modifier.height(h.dp))
