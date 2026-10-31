package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.overview.graph.DeviationDataPoint
import kotlin.math.abs

/**
 * Insulin balance strip, like the deviation bars of the original AAPS but shown only where it matters:
 * red (down) where there was too much insulin while glucose was in the lower half of the range, green (up) where there
 * was too little while glucose was in the upper half. Everything else is left empty, so a bar always means "look here".
 */
@Composable
internal fun DeviationStrip(
    bars: List<DeviationBar>,
    timeRangeHours: Int,
    historyFraction: Float,
    isDark: Boolean,
    scrubProgress: Float? = null,
    embedded: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth().height(70.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Insulin balance",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
            )
            Text(
                text = "less ▲   ▼ more",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
            )
        }
        Canvas(modifier = Modifier.fillMaxSize().padding(top = 4.dp).edgeFade(bottomBandPx = 0f)) {
            val chartW = size.width - 65f
            val mid = size.height / 2f
            drawLine(
                color = if (isDark) Color(0x24FFFFFF) else Color(0x1F000000),
                start = Offset(0f, mid),
                end = Offset(chartW, mid),
                strokeWidth = 1f
            )
            val barW = (chartW * historyFraction / (timeRangeHours * 12f)).coerceAtLeast(3f)
            for (bar in bars) {
                val x = bar.progress * chartW * historyFraction
                val h = (mid - 3f) * bar.size.coerceIn(0.08f, 1f)
                if (bar.excess) {
                    drawRoundRect(
                        color = Color(0xFFEF4444),
                        topLeft = Offset(x - barW / 2f, mid),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2f)
                    )
                } else {
                    drawRoundRect(
                        color = Color(0xFF22C55E),
                        topLeft = Offset(x - barW / 2f, mid - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2f)
                    )
                }
            }
            scrubProgress?.let { p ->
                val x = p * chartW * historyFraction
                drawLine(
                    color = if (isDark) Color(0x99FFFFFF) else Color(0x99334155),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f
                )
            }
        }
    }
}

/**
 * Turns the deviation points into bars for the strip. A point becomes a bar only if it says "too much insulin" while
 * glucose is in the lower half of the range, or "too little" while it is in the upper half.
 */
internal fun buildDeviationBars(
    deviations: List<DeviationDataPoint>,
    nowEpochMs: Long,
    rangeHours: Int,
    readings: List<BgReadingPoint>,
    lowLine: Float,
    highLine: Float,
): List<DeviationBar> {
    if (deviations.isEmpty() || readings.isEmpty()) return emptyList()
    val windowStart = nowEpochMs - rangeHours * 3_600_000L
    val inWindow = deviations.filter { it.timestamp in windowStart..nowEpochMs }
    if (inWindow.isEmpty()) return emptyList()
    val maxAbs = inWindow.maxOf { abs(it.value) }.coerceAtLeast(0.0001)
    val midLine = (lowLine + highLine) / 2f
    return inWindow.mapNotNull { d ->
        val progress = ((d.timestamp - windowStart).toFloat() / (nowEpochMs - windowStart).toFloat()).coerceIn(0f, 1f)
        val bg = readings.minByOrNull { abs(it.progress - progress) }?.value ?: return@mapNotNull null
        val size = (abs(d.value) / maxAbs).toFloat()
        when {
            d.value < 0 && bg < midLine -> DeviationBar(progress, size, excess = true)
            d.value > 0 && bg > midLine -> DeviationBar(progress, size, excess = false)
            else -> null
        }
    }
}
