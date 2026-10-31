package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One glass card for the glucose chart and the IOB chart: a header with a slim hour selector, the glucose chart on
 * top and the IOB chart under it on the same time axis. The finger position is shared by the two charts.
 */
@Composable
internal fun GlassCombinedChartCard(
    isDark: Boolean,
    selectedHours: Int,
    onSelectHours: (Int) -> Unit,
    onOpenStats: () -> Unit,
    statsLabel: String,
    bgChart: @Composable () -> Unit,
    iobChart: @Composable () -> Unit,
) {
    val muted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    GlassContainer(isDark = isDark, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = statsLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = muted,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onOpenStats() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                HourSegment(selectedHours = selectedHours, onSelectHours = onSelectHours, isDark = isDark)
            }
            bgChart()
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(if (isDark) Color(0x1FFFFFFF) else Color(0x1A000000))
            )
            iobChart()
        }
    }
}

/** Slim capsule with the hour choices; the selected one is a filled pill. */
@Composable
private fun HourSegment(selectedHours: Int, onSelectHours: (Int) -> Unit, isDark: Boolean) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (isDark) Color(0x1FFFFFFF) else Color(0x14000000))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(6, 12, 18, 24).forEach { hours ->
            val selected = hours == selectedHours
            Box(
                modifier = Modifier
                    .clip(shape)
                    .then(
                        if (selected) Modifier.background(Brush.verticalGradient(listOf(Color(0xFF38BDF8), Color(0xFF0284C7))), shape)
                        else Modifier
                    )
                    .clickable { onSelectHours(hours) }
                    .width(38.dp)
                    .height(26.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${hours}h",
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        selected -> Color.White
                        isDark -> Color(0xFFCBD5E1)
                        else -> Color(0xFF334155)
                    },
                    maxLines = 1
                )
            }
        }
    }
}
