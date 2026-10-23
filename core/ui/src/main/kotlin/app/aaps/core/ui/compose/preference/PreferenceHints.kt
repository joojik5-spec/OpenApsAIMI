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

    /** Advice for number settings: key, current value, min, max, is whole number. */
    @Volatile
    var numberProvider: ((String, Double, Double, Double, Boolean) -> PreferenceHint?)? = null

    /** Advice for profile sections: section (basal, isf, ic, target), values, start times in seconds, mg/dl. */
    @Volatile
    var profileProvider: ((String, List<Double>, List<Int>, Boolean) -> List<String>)? = null

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

/** Draws the advice line for a number setting: which value is better now. */
@Composable
fun PreferenceNumberHintLine(key: String, value: Double, min: Double, max: Double, isInt: Boolean) {
    @Suppress("UNUSED_VARIABLE")
    val redrawOn = PreferenceHints.version.intValue
    if (Locale.getDefault().language != "ru") return
    val hint = PreferenceHints.numberProvider?.invoke(key, value, min, max, isInt) ?: return
    Text(text = hint.text, color = GreenLight, style = MaterialTheme.typography.bodySmall)
}

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

/** Draws advice lines for a profile section (Russian only). */
@Composable
fun ProfileHintLines(section: String, values: List<Double>, times: List<Int>, mgdl: Boolean) {
    @Suppress("UNUSED_VARIABLE")
    val redrawOn = PreferenceHints.version.intValue
    if (Locale.getDefault().language != "ru") return
    val lines = PreferenceHints.profileProvider?.invoke(section, values, times, mgdl) ?: return
    lines.forEach { Text(text = it, color = GreenLight, style = MaterialTheme.typography.bodySmall) }
}
