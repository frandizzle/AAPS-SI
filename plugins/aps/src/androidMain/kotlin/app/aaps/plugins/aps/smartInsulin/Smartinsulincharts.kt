package app.aaps.plugins.aps.smartInsulin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsTheme
import java.util.Calendar
import kotlin.math.roundToInt

/*
 * The two drawn parts of the SI tab: the time-in-range bars and the 24h circadian table. Parsing and
 * the meaning of each colour are the 3.4 fragment's; the colours themselves come from the theme.
 */

// ── Time in range ───────────────────────────────────────────────────────────

private data class TirValues(val inPct: Float, val highPct: Float, val lowPct: Float)

private fun parseTir(s: String, prefix: String): TirValues? {
    val m = Regex("""$prefix:(\d+)%in/(\d+)%hi/(\d+)%lo""").find(s) ?: return null
    return TirValues(
        m.groupValues[1].toFloatOrNull() ?: return null,
        m.groupValues[2].toFloatOrNull() ?: return null,
        m.groupValues[3].toFloatOrNull() ?: return null
    )
}

@Composable
internal fun SiTirSection(d: SmartInsulinPlugin.FragmentData) {
    val colors = AapsTheme.generalColors

    // Estimated HbA1c (ADAG: HbA1c% = (avgBG_mgdl + 46.7) / 28.7)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (d.estimatedHba1c > 0.0) {
            val hba1cColor = when {
                d.estimatedHba1c < 6.5 -> colors.statusNormal
                d.estimatedHba1c < 7.5 -> colors.statusWarning
                else                   -> colors.statusCritical
            }
            val avg = if (d.isMmol) "%.1f".format(d.avgBgMgdl24h / 18.0) else "%.0f".format(d.avgBgMgdl24h)
            SiStat("Est. HbA1c", "%.1f%%".format(d.estimatedHba1c), if (d.bgWindowHours < 24) "${d.bgWindowHours}h of data" else "last 24h",
                   Modifier.weight(1f), valueColor = hba1cColor)
            SiStat("Average BG", avg, if (d.isMmol) "mmol/L" else "mg/dL", Modifier.weight(1f))
        } else {
            SiStat("Est. HbA1c", "—", "building… (~2h needed)", Modifier.weight(1f))
        }
    }
    SiSpacer(16)
    TirBar("Fasting", parseTir(d.tirRawLine, "Fasting"), "fasting")
    SiSpacer(12)
    TirBar("Meal", parseTir(d.tirRawLine, "Meal"), "meal")
    SiSpacer(12)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Legend("Low", colors.bgLow)
        Legend("In range", colors.bgInRange)
        Legend("High", colors.bgHigh)
    }
}

@Composable
private fun TirBar(title: String, tir: TirValues?, label: String) {
    val colors = AapsTheme.generalColors
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (tir != null)
            Text("${tir.inPct.roundToInt()}% in range", style = MaterialTheme.typography.labelLarge, color = colors.bgInRange)
    }
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (tir != null && tir.inPct + tir.highPct + tir.lowPct > 0f) {
            if (tir.lowPct > 0f) Box(Modifier.weight(tir.lowPct).height(14.dp).background(colors.bgLow))
            if (tir.inPct > 0f) Box(Modifier.weight(tir.inPct).height(14.dp).background(colors.bgInRange))
            if (tir.highPct > 0f) Box(Modifier.weight(tir.highPct).height(14.dp).background(colors.bgHigh))
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        if (tir == null) "Not enough data yet — needs ~2 hours of $label readings"
        else "${tir.lowPct.roundToInt()}% low  ·  ${tir.highPct.roundToInt()}% high",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Circadian 24h ───────────────────────────────────────────────────────────

private data class CircRow(val hour: Int, val isfVal: Float, val basVal: Float, val ceil: Float, val confPct: Int)

private fun parseCircRows(raw: String) = Regex("""[→►\s]\s*(\d{1,2})\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(\d+)%""")
    .findAll(raw).mapNotNull { m ->
        CircRow(
            m.groupValues[1].toIntOrNull() ?: return@mapNotNull null,
            m.groupValues[2].toFloatOrNull() ?: return@mapNotNull null,
            m.groupValues[3].toFloatOrNull() ?: return@mapNotNull null,
            m.groupValues[4].toFloatOrNull() ?: return@mapNotNull null,
            m.groupValues[5].toIntOrNull() ?: return@mapNotNull null
        )
    }.toList()

fun todayDow(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1

/**
 * @param selectedDow 0 = Sunday, matching [SmartInsulinPlugin.circadianDataForDay]
 */
@Composable
internal fun SiCircadianSection(plugin: SmartInsulinPlugin, selectedDow: Int, onSelectDow: (Int) -> Unit) {
    val colors = AapsTheme.generalColors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val today = todayDow()
    val currentHr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val dayLabels = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    // Day selector, Monday first — a segmented row
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(50)) {
        Row(Modifier.fillMaxWidth().padding(3.dp)) {
            intArrayOf(1, 2, 3, 4, 5, 6, 0).forEach { d ->
                val selected = d == selectedDow
                Text(
                    if (d == today) "Today" else dayLabels[d],
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else muted,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(if (d == today) 1.3f else 1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onSelectDow(d) }
                        .padding(vertical = 7.dp)
                )
            }
        }
    }

    val rows = parseCircRows(plugin.circadianDataForDay(selectedDow))
    if (rows.isEmpty()) return

    val isMmol = plugin.isMmol
    val profIsfMgdl = plugin.profileIsfMgdl
    val profBasal = plugin.profileBasalU
    val profIsfDisp = if (isMmol && profIsfMgdl > 0) (profIsfMgdl / 18.0).toFloat() else profIsfMgdl.toFloat()
    val stronger = colors.statusWarning      // more insulin than profile
    val weaker = AapsTheme.elementColors.insulin // less insulin than profile

    fun isfColor(v: Float): Color {
        if (profIsfDisp <= 0f) return muted
        val r = v / profIsfDisp
        return when {
            r < 0.97f -> stronger
            r > 1.03f -> weaker
            else      -> muted
        }
    }

    fun basColor(v: Float): Color {
        if (profBasal <= 0.0) return muted
        val r = v / profBasal.toFloat()
        return when {
            r > 1.03f -> stronger
            r < 0.97f -> weaker
            else      -> muted
        }
    }

    fun ceilColor(m: Float) = when {
        m > 1.05f -> stronger
        m < 0.95f -> weaker
        else      -> muted
    }

    fun confColor(p: Int) = when {
        p == 0  -> muted
        p >= 60 -> colors.statusNormal
        p >= 30 -> colors.statusWarning
        else    -> colors.statusCritical
    }

    SiSpacer(12)
    Text(
        "What the loop delivers each hour, learned from your BG. Orange = more insulin than your profile, " +
            "blue = less. Ceil caps aggressiveness for the hour; Conf is how much data backs it.",
        style = MaterialTheme.typography.bodySmall, color = muted
    )
    SiSpacer(12)

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        HeaderCell("Hr", 0.8f)
        HeaderCell("ISF", 1.4f)
        HeaderCell("Basal", 1.4f)
        HeaderCell("Ceil", 1.3f)
        HeaderCell("Conf", 2.2f)
    }

    rows.forEach { row ->
        val isCur = selectedDow == today && row.hour == currentHr
        val bg = when {
            isCur              -> MaterialTheme.colorScheme.primaryContainer
            row.hour % 2 == 0  -> MaterialTheme.colorScheme.surfaceContainerHigh
            else               -> Color.Transparent
        }
        val weight = if (isCur) FontWeight.Bold else FontWeight.Normal
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cell("%02d".format(row.hour), 0.8f, if (isCur) MaterialTheme.colorScheme.onPrimaryContainer else muted, weight)
            Cell(if (isMmol) "%.2f".format(row.isfVal) else "%.1f".format(row.isfVal), 1.4f, isfColor(row.isfVal), weight)
            Cell("%.3f".format(row.basVal), 1.4f, basColor(row.basVal), weight)
            Cell("%.2f".format(row.ceil), 1.3f, ceilColor(row.ceil), weight)
            Row(Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically) {
                SiMeter(row.confPct / 100f, confColor(row.confPct), Modifier.weight(1f))
                Text(
                    "${row.confPct}%", style = MaterialTheme.typography.labelSmall, color = confColor(row.confPct),
                    textAlign = TextAlign.End, modifier = Modifier.width(34.dp)
                )
            }
        }
    }
    SiSpacer(8)
    Text(
        if (isMmol) "ISF in mmol/U · basal in U/h" else "ISF in mg/dL/U · basal in U/h",
        style = MaterialTheme.typography.labelSmall, color = muted
    )
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(weight))

@Composable
private fun RowScope.Cell(text: String, weight: Float, color: Color, fontWeight: FontWeight) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = fontWeight, modifier = Modifier.weight(weight))
