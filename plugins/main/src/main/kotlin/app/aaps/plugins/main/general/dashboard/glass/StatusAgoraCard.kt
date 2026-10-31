package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.plugins.main.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Only ever called for GlassPillLocation.BOTTOM_ROW entries (see the bottom-row filter below), but the
// `when` must still cover every GlassPillId since top-grid IDs share the same enum.
private fun glassPillValue(id: GlassPillId, state: GlassUiState): String = when (id) {
    GlassPillId.IOB -> state.iobText
    GlassPillId.TARGET -> state.targetText
    GlassPillId.BASAL_RATE -> state.basalPercentText
    GlassPillId.LAST_BOLUS -> state.lastBolusText
    GlassPillId.LAST_CARBS -> state.lastCarbsText
    GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
    GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY -> ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatusAgoraCard(
    state: GlassUiState,
    isDark: Boolean,
    onOpenLoop: () -> Unit,
    onOpenLoopDashboard: () -> Unit,
    onOpenTarget: () -> Unit,
    onOpenInsulin: () -> Unit,
    onOpenPump: () -> Unit,
    onOpenCannula: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenBasal: () -> Unit,
    onOpenSensorInsert: () -> Unit,
    onOpenSensorQuality: () -> Unit,
    onOpenTools: () -> Unit,
    selectedPills: List<GlassPillId>,
) {
    // Minute clock: keeps "last value N min ago" and the stale grey moving between view-model refreshes.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val glucoseStale = GlassCgmLinkStatus.isStale(state.isGlucoseActual, state.glucoseTimestampMs, nowMs)
    val linkBanner = GlassCgmLinkStatus.banner(state.warmup, state.isGlucoseActual, state.glucoseTimestampMs, nowMs)
    val neutralColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    GlassContainer(isDark = isDark, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // LEFT: pump status
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (GlassPillId.PUMP_RESERVOIR in selectedPills) {
                        GlassPill(
                            label = state.insulinLabel,
                            value = state.insulinAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.insulinAgeStatus),
                            modifier = Modifier.width(100.dp).clickable { onOpenPump() },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_insulin),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                    if (GlassPillId.CANNULA in selectedPills) {
                        GlassPill(
                            label = state.cannulaLabel,
                            value = state.cannulaAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.cannulaAgeStatus),
                            modifier = Modifier.width(100.dp).clickable { onOpenCannula() },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_cannula),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                    if (GlassPillId.BATTERY in selectedPills) {
                        GlassPill(
                            label = state.batteryLabel,
                            value = state.batteryAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.batteryAgeStatus),
                            modifier = Modifier.width(100.dp).clickable { onOpenBattery() },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_battery),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                    GlassPill(
                        label = stringResource(R.string.dashboard_glass_tools_label),
                        value = "",
                        isDark = isDark,
                        modifier = Modifier.width(100.dp).clickable { onOpenTools() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_glyco_settings),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }

                // CENTER: big glucose value (the daily numbers are in the dashboard row below)
                Box(
                    modifier = Modifier.weight(1.3f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        // An old value stays visible, greyed and struck through, like the classic overview.
                        Text(
                            text = state.currentBg,
                            fontSize = GLUCOSE_FONT_SIZE,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (glucoseStale) neutralColor else Color(if (state.glucoseDigitsColor != 0) state.glucoseDigitsColor else state.glucoseColor),
                            textDecoration = if (glucoseStale) TextDecoration.LineThrough else null,
                            letterSpacing = (-2).sp,
                            lineHeight = GLUCOSE_FONT_SIZE,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.clickable { onOpenLoop() }
                        )
                        Text(
                            text = state.unit,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (state.trendArrowRes != null) {
                                Icon(
                                    painter = painterResource(id = state.trendArrowRes),
                                    contentDescription = null,
                                    tint = Color(state.glucoseColor),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = state.deltaText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(state.glucoseColor),
                                modifier = Modifier.padding(start = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = state.timeAgo,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                    }
                }

                // RIGHT: CGM & loop
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (GlassPillId.SENSOR in selectedPills) {
                        GlassPill(
                            label = state.sensorLabel,
                            value = state.sensorAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.sensorAgeStatus),
                            modifier = Modifier.width(100.dp).combinedClickable(
                                onClick = onOpenSensorQuality,
                                onLongClick = onOpenSensorInsert
                            ),
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_sensor),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                    if (GlassPillId.LOOP_STATUS in selectedPills) {
                        GlassPill(
                            label = stringResource(R.string.dashboard_glass_loop_label),
                            value = state.loopStatusText,
                            isDark = isDark,
                            modifier = Modifier.width(100.dp).combinedClickable(
                                onClick = onOpenLoopDashboard,
                                onLongClick = onOpenLoop
                            ),
                            leadingIcon = {
                                if (state.loopIsRunning) {
                                    val infiniteTransition = rememberInfiniteTransition()
                                    val pulseAlpha by infiniteTransition.animateFloat(
                                        initialValue = 0f,
                                        targetValue = 0.8f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1000, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        )
                                    )
                                    // pulseAlpha is read in the draw phase only: reading it in composition
                                    // recomposed this pill on every frame and kept the render thread busy.
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .drawBehind { drawCircle(Color(0xFF22C55E), alpha = pulseAlpha) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .background(Color(0xFF10B981), CircleShape)
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF94A3B8), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .background(Color(0xFF94A3B8), CircleShape)
                                        )
                                    }
                                }
                            }
                        )
                    }
                    if (GlassPillId.ACTIVITY in selectedPills) {
                        GlassPill(
                            label = stringResource(R.string.dashboard_glass_activity_label),
                            value = stringResource(R.string.dashboard_glass_activity_value, state.stepsText, state.hrText),
                            isDark = isDark,
                            modifier = Modifier.width(100.dp),
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_settings),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                }
            }

            linkBanner?.let { banner ->
                Spacer(modifier = Modifier.height(8.dp))
                CgmLinkBannerText(banner)
            }

            DailyDashboard(state = state, isDark = isDark)

            val bottomRowPills = GLASS_PILL_CATALOG.filter { it.location == GlassPillLocation.BOTTOM_ROW && it.id in selectedPills }
            if (bottomRowPills.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    bottomRowPills.forEach { entry ->
                        val isTarget = entry.id == GlassPillId.TARGET
                        BottomMetricPill(
                            title = if (isTarget && state.isTempTargetActive) "" else stringResource(entry.labelRes),
                            value = glassPillValue(entry.id, state),
                            isDark = isDark,
                            onClick = when (entry.id) {
                                GlassPillId.IOB -> onOpenInsulin
                                GlassPillId.TARGET -> onOpenTarget
                                GlassPillId.BASAL_RATE -> onOpenBasal
                                GlassPillId.LAST_BOLUS -> onOpenInsulin
                                GlassPillId.LAST_CARBS -> onOpenInsulin
                                GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
                                GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY -> null
                            },
                            modifier = Modifier.weight(1f),
                            accentColor = if (isTarget && state.isTempTargetActive) Color(0xFFF4D700) else null
                        )
                    }
                }
            }
        }
    }
}

/**
 * Row of the daily numbers that matter: time in range today (with the change against yesterday), time low and
 * high, average glucose, variability (CV) and the estimated A1c. All values come from the dashboard state.
 */
@Composable
private fun DailyDashboard(state: GlassUiState, isDark: Boolean) {
    val neutral = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val tir = state.tir
    val delta = tir?.deltaVsYesterday
    val tirSub = when {
        delta == null || delta == 0 -> ""
        delta > 0                   -> "↑$delta"
        else                        -> "↓${abs(delta)}"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        DashboardTile("TIR", tir?.let { "${it.inRangePct.roundToInt()}%" } ?: "--", tirSub, GlassColors.emerald, neutral, Modifier.weight(1f))
        DashboardTile("Low", tir?.let { "${it.belowPct.roundToInt()}%" } ?: "--", "", GlassColors.red, neutral, Modifier.weight(1f))
        DashboardTile("High", tir?.let { "${it.abovePct.roundToInt()}%" } ?: "--", "", GlassColors.amber, neutral, Modifier.weight(1f))
        DashboardTile("Avg", state.avgBgText, "", if (isDark) Color.White else Color(0xFF0F172A), neutral, Modifier.weight(1f))
        DashboardTile("CV", state.cvText, "", if (isDark) Color.White else Color(0xFF0F172A), neutral, Modifier.weight(1f))
        DashboardTile("eA1c", state.gmiText, "", if (isDark) Color.White else Color(0xFF0F172A), neutral, Modifier.weight(1f))
    }
}

@Composable
private fun DashboardTile(label: String, value: String, sub: String, valueColor: Color, labelColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = if (sub.isEmpty()) label else "$label $sub",
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Size of the main glucose number. */
private val GLUCOSE_FONT_SIZE = 64.sp

/** "Reconnecting… · last value 14 min ago", in the attention colour of the other dashboard skins. */
@Composable
private fun CgmLinkBannerText(banner: GlassCgmLinkStatus.Banner) {
    val phase = stringResource(
        when (banner.phase) {
            CgmWarmupStatus.Phase.CONNECTING -> R.string.dashboard_warmup_phase_connecting
            CgmWarmupStatus.Phase.RECONNECTING -> R.string.dashboard_warmup_phase_reconnecting
            CgmWarmupStatus.Phase.PAIRING -> R.string.dashboard_warmup_phase_pairing
            CgmWarmupStatus.Phase.WARMING,
            CgmWarmupStatus.Phase.OTHER -> R.string.dashboard_warmup_label
        }
    )
    Text(
        text = banner.minutesSinceLastValue?.let { stringResource(R.string.dashboard_glass_cgm_link_banner, phase, it.toInt()) } ?: phase,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = colorResource(R.color.dashboard_metric_attention),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
