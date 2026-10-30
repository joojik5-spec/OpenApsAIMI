package app.aaps.compose.dashboard

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import app.aaps.plugins.aps.openAPSAIMI.advisor.assistant.AimiAssistantActivity
import app.aaps.plugins.main.general.dashboard.AimiDashboardComposeRootView
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction
import app.aaps.plugins.main.skins.DashboardHomeVariant

/**
 * AIMI dashboard home in Compose: embeds [AimiDashboardComposeRootView] (Compose column + shared
 * [DashboardShellController]; graph / notifications / adjustment remain in [AndroidView] slots).
 * A small green button opens the AIMI Assistant.
 */
@Composable
fun DashboardOverviewHost(
    paddingValues: PaddingValues,
    fabBottomOffset: Dp,
    dashboardHomeVariant: DashboardHomeVariant,
    availablePluginClassNames: Set<String>,
    onToolAction: (DashboardV2ToolAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(bottom = fabBottomOffset),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                require(ctx is FragmentActivity) { "DashboardOverviewHost requires FragmentActivity context" }
                AimiDashboardComposeRootView(
                    context = ctx,
                    dashboardHomeVariant = dashboardHomeVariant,
                    availablePluginClassNames = availablePluginClassNames,
                    onToolAction = onToolAction,
                )
            },
            update = { },
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 12.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF22C55E))
                .clickable { context.startActivity(Intent(context, AimiAssistantActivity::class.java)) },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "SA", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
