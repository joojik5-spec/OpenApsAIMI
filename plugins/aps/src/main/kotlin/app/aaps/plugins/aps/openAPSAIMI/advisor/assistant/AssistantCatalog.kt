package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.R

/**
 * Settings that the assistant may change. Only settings that were checked in the code
 * and really change the delivery are here. "Max minutes of basal" is not here: AIMI does not use it.
 */
object AssistantCatalog {

    val highBgInterval = AssistantSetting(
        englishName = "High BG SMB interval (min)",
        kind = AssistantSetting.Kind.INT,
        prefKey = IntKey.OApsAIMIHighBGinterval.key,
        default = IntKey.OApsAIMIHighBGinterval.defaultValue.toDouble(),
        min = IntKey.OApsAIMIHighBGinterval.min.toDouble(),
        max = IntKey.OApsAIMIHighBGinterval.max.toDouble(),
        step = 1.0,
        whatItDoes = R.string.aimi_as_what_high_bg_interval,
    )
    val priorityExtra = AssistantSetting(
        englishName = "Priority MaxIOB extra (U)",
        kind = AssistantSetting.Kind.DOUBLE,
        prefKey = DoubleKey.OApsAIMIPriorityMaxIobExtraU.key,
        default = DoubleKey.OApsAIMIPriorityMaxIobExtraU.defaultValue,
        min = DoubleKey.OApsAIMIPriorityMaxIobExtraU.min,
        max = DoubleKey.OApsAIMIPriorityMaxIobExtraU.max,
        step = 0.5,
        whatItDoes = R.string.aimi_as_what_priority_extra,
    )
    val highBgMaxSmb = AssistantSetting(
        englishName = "High BG Max SMB (U)",
        kind = AssistantSetting.Kind.DOUBLE,
        prefKey = DoubleKey.OApsAIMIHighBGMaxSMB.key,
        default = DoubleKey.OApsAIMIHighBGMaxSMB.defaultValue,
        min = DoubleKey.OApsAIMIHighBGMaxSMB.min,
        max = DoubleKey.OApsAIMIHighBGMaxSMB.max,
        step = 0.1,
        whatItDoes = R.string.aimi_as_what_high_bg_max_smb,
    )
    val maxSmb = AssistantSetting(
        englishName = "Max SMB (U)",
        kind = AssistantSetting.Kind.DOUBLE,
        prefKey = DoubleKey.OApsAIMIMaxSMB.key,
        default = DoubleKey.OApsAIMIMaxSMB.defaultValue,
        min = DoubleKey.OApsAIMIMaxSMB.min,
        max = DoubleKey.OApsAIMIMaxSMB.max,
        step = 0.1,
        whatItDoes = R.string.aimi_as_what_max_smb,
    )
    val redCarpet = AssistantSetting(
        englishName = "Red Carpet restore threshold",
        kind = AssistantSetting.Kind.DOUBLE,
        prefKey = DoubleKey.OApsAIMIRedCarpetRestoreThreshold.key,
        default = DoubleKey.OApsAIMIRedCarpetRestoreThreshold.defaultValue,
        min = DoubleKey.OApsAIMIRedCarpetRestoreThreshold.min,
        max = DoubleKey.OApsAIMIRedCarpetRestoreThreshold.max,
        step = 0.05,
        whatItDoes = R.string.aimi_as_what_red_carpet,
    )

    val all: List<AssistantSetting> = listOf(highBgInterval, priorityExtra, highBgMaxSmb, maxSmb, redCarpet)

    fun byKey(prefKey: String): AssistantSetting? = all.firstOrNull { it.prefKey == prefKey }
}
