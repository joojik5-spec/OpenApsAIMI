package app.aaps.core.ui.compose.preference

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import java.util.Locale

/** How strong the advice is. It only changes the color of the hint. */
enum class PreferenceHintLevel { ENABLE, SKIP, CAUTION }

/** Short advice for one setting. [text] already includes the reason. */
data class PreferenceHint(val level: PreferenceHintLevel, val text: String)

/**
 * Holds offline advice for settings. A plugin sets the [provider]. Switch preferences show the
 * advice under their summary. Advice is shown for Russian language only.
 */
object PreferenceHints {

    @Volatile
    var provider: ((String) -> PreferenceHint?)? = null

    /** Changes when the advice is recalculated, so screens redraw. */
    val version = mutableIntStateOf(0)

    fun bump() {
        version.intValue += 1
    }

    fun hintFor(key: String): PreferenceHint? {
        if (Locale.getDefault().language != "ru") return null
        return provider?.invoke(key)
    }
}

private val GreenLight = Color(0xFF2E9E4F)
private val RedLight = Color(0xFFD9534F)

/** Draws the advice line for [key] if there is one. */
@Composable
fun PreferenceHintLine(key: String) {
    @Suppress("UNUSED_VARIABLE")
    val redrawOn = PreferenceHints.version.intValue
    val hint = PreferenceHints.hintFor(key) ?: return
    val color = when (hint.level) {
        PreferenceHintLevel.CAUTION -> RedLight
        else                        -> GreenLight
    }
    Text(text = hint.text, color = color, style = MaterialTheme.typography.bodySmall)
}
