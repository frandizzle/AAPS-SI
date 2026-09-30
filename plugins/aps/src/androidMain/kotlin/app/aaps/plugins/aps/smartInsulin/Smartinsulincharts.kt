package app.aaps.plugins.aps.smartInsulin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kotlin.math.roundToInt

/*
 * The two parts of the SI tab that were drawn rather than listed in 3.4: the time-in-range bars and
 * the 24h circadian table. Parsing and colour rules are the 3.4 fragment's, unchanged.
 */

private val Green = Color(0xFF43A047)
private val Amber = Color(0xFFFB8C00)
private val Red = Color(0xFFE53935)
private val Blue = Color(0xFF64B5F6)
private val GreyText = Color(0xFF888888)
private val LightText = Color(0xFFDDDDDD)
private val HeaderText = Color(0xFFCCCCCC)
private val Track = Color(0xFF444444)

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
    Text("How much time BG has spent in the healthy range over the last 24h.", fontSize = 11.sp, color = LightText,
         modifier = Modifier.padding(bottom = 8.dp))
    TirBar("Fasting", parseTir(d.tirRawLine, "Fasting"), "fasting")
    TirBar("Meal", parseTir(d.tirRawLine, "Meal"), "meal")

    // Estimated HbA1c (ADAG: HbA1c% = (avgBG_mgdl + 46.7) / 28.7)
    if (d.estimatedHba1c > 0.0) {
        val avg = if (d.isMmol) "${"%.1f".format(d.avgBgMgdl24h / 18.0)} mmol/L" else "${"%.0f".format(d.avgBgMgdl24h)} mg/dL"
        val windowNote = if (d.bgWindowHours < 24) "  (${d.bgWindowHours}h data)" else ""
        val color = when {
            d.estimatedHba1c < 6.5 -> Green
            d.estimatedHba1c < 7.5 -> Amber
            else                   -> Red
        }
        Text("Est. HbA1c: ${"%.1f".format(d.estimatedHba1c)}%  •  avg: $avg$windowNote", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    } else {
        Text("Est. HbA1c: building… (~2h needed)", fontSize = 13.sp, color = GreyText)
    }
}

@Composable
private fun TirBar(title: String, tir: TirValues?, label: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(3.dp))
    ) {
        if (tir == null || tir.inPct + tir.highPct + tir.lowPct <= 0f) {
            Box(Modifier.weight(1f).height(14.dp).background(Color(0xFF9E9E9E)))
        } else {
            if (tir.lowPct > 0f) Box(Modifier.weight(tir.lowPct).height(14.dp).background(Red))
            if (tir.inPct > 0f) Box(Modifier.weight(tir.inPct).height(14.dp).background(Green))
            if (tir.highPct > 0f) Box(Modifier.weight(tir.highPct).height(14.dp).background(Amber))
        }
    }
    Text(
        if (tir == null) "Not enough data yet — needs ~2 hours of $label readings"
        else "${tir.inPct.roundToInt()}% in range  •  ${tir.highPct.roundToInt()}% high  •  ${tir.lowPct.roundToInt()}% low",
        fontSize = 11.sp, color = LightText, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
    )
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

private fun confColor(p: Int) = when {
    p >= 60 -> Green
    p >= 30 -> Amber
    else    -> Red
}

// Ceiling: <0.95 = blue (restricted), >1.05 = amber (boosted), else grey
private fun ceilColor(m: Float) = when {
    m > 1.05f -> Amber
    m < 0.95f -> Blue
    else      -> Color(0xFFAAAAAA)
}

fun todayDow(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1

/**
 * @param selectedDow 0 = Sunday, matching [SmartInsulinPlugin.circadianDataForDay]
 */
@Composable
internal fun SiCircadianSection(plugin: SmartInsulinPlugin, selectedDow: Int, onSelectDow: (Int) -> Unit) {
    val today = todayDow()
    val currentHr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val dayLabels = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    // Day selector, Monday first
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        intArrayOf(1, 2, 3, 4, 5, 6, 0).forEach { d ->
            val selected = d == selectedDow
            Text(
                if (d == today) "Today" else dayLabels[d],
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = if (selected) Color.Black else Color(0xFFAAAAAA),
                modifier = Modifier
                    .weight(1f)
                    .background(if (selected) Green else Color(0xFF333333))
                    .clickable { onSelectDow(d) }
                    .padding(vertical = 4.dp)
            )
        }
    }

    val rows = parseCircRows(plugin.circadianDataForDay(selectedDow))
    if (rows.isEmpty()) return

    val isMmol = plugin.isMmol
    val profIsfMgdl = plugin.profileIsfMgdl
    val profBasal = plugin.profileBasalU
    val profIsfDisp = if (isMmol && profIsfMgdl > 0) (profIsfMgdl / 18.0).toFloat() else profIsfMgdl.toFloat()

    // ISF: orange = lower ISF (more aggressive), blue = higher (less aggressive), grey = at profile
    fun isfColor(v: Float): Color {
        if (profIsfDisp <= 0f) return LightText
        val r = v / profIsfDisp
        return when {
            r < 0.97f -> Amber
            r > 1.03f -> Blue
            else      -> GreyText
        }
    }

    // Basal: orange = higher basal (more aggressive), blue = lower, grey = at profile
    fun basColor(v: Float): Color {
        if (profBasal <= 0.0) return LightText
        val r = v / profBasal.toFloat()
        return when {
            r > 1.03f -> Amber
            r < 0.97f -> Blue
            else      -> GreyText
        }
    }

    Text(
        "Hourly multipliers learned from your BG patterns.\nISF and Bas = values the loop actually delivers for that hour.\n" +
            "Ceil = aggressiveness cap — lower = more conservative.\n" +
            "Conf = confidence — how much real data collected. Green ≥60%, amber ≥30%, red <30%.",
        fontSize = 11.sp, color = LightText, modifier = Modifier.padding(bottom = 8.dp)
    )

    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        HeaderCell("Hr", 1f)
        HeaderCell(if (isMmol) "ISF mmol" else "ISF mg/dL", 2f)
        HeaderCell("Basal U/h", 2f)
        HeaderCell("Ceil", 2f)
        HeaderCell("Conf", 3f)
    }

    rows.forEach { row ->
        val isCur = selectedDow == today && row.hour == currentHr
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp)
                .then(if (isCur) Modifier.background(Color(0x22FFFFFF)).padding(horizontal = 4.dp, vertical = 2.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cell(if (isCur) "→${row.hour}" else "  ${row.hour}", 1f, if (isCur) Color.White else HeaderText, isCur)
            Cell(if (isMmol) "%.2f".format(row.isfVal) else "%.1f".format(row.isfVal), 2f, isfColor(row.isfVal), isCur)
            Cell("%.3f".format(row.basVal), 2f, basColor(row.basVal), isCur)
            Cell("%.3f".format(row.ceil), 2f, ceilColor(row.ceil), isCur)
            Row(Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically) {
                val fill = (row.confPct.coerceIn(0, 100) / 100f)
                Row(Modifier.width(40.dp).height(8.dp)) {
                    if (fill > 0f) Box(Modifier.weight(fill).height(8.dp).background(confColor(row.confPct)))
                    if (fill < 1f) Box(Modifier.weight(1f - fill).height(8.dp).background(Track))
                }
                Text(" ${row.confPct}%", fontSize = 11.sp, color = confColor(row.confPct),
                     fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) =
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = HeaderText, modifier = Modifier.weight(weight))

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, color: Color, bold: Boolean) =
    Text(text, fontSize = 12.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(weight))
