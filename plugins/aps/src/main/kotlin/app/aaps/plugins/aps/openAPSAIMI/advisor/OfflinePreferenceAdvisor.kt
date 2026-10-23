package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.ui.compose.preference.PreferenceHint
import app.aaps.core.ui.compose.preference.PreferenceHintLevel
import app.aaps.core.ui.compose.preference.PreferenceHints
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Numbers taken from the last days of glucose. Pure data, easy to test.
 * The user runs a closed loop and never enters carbs or boluses, so "meal rise" is found only
 * from the glucose curve.
 */
data class GlucoseProfileStats(
    val days: Double,
    val readings: Int,
    val meanMgdl: Double,
    val cvPercent: Double,
    val tirPercent: Double,
    val lowPercent: Double,
    val veryLowPercent: Double,
    val highPercent: Double,
    val veryHighPercent: Double,
    val nightLowPercent: Double,
    val nightHighPercent: Double,
    /** Rises of 45+ mg/dl in 45 minutes, per day. */
    val risesPerDay: Double,
    /** Average peak above the start of a rise, mg/dl. */
    val avgRisePeakMgdl: Double,
    /** Share of rises where glucose is still above 180 two hours later. */
    val slowRecoveryPercent: Double
) {

    companion object {

        private const val LOW = 70.0
        private const val HIGH = 180.0
        private const val VERY_LOW = 54.0
        private const val VERY_HIGH = 250.0
        private const val STEP_MS = 5 * 60 * 1000L
        private const val RISE_MGDL = 45.0
        private const val RISE_WINDOW_MS = 45 * 60 * 1000L
        private const val LOOKAHEAD_MS = 3 * 60 * 60 * 1000L
        private const val AFTER_MS = 2 * 60 * 60 * 1000L

        /** Needs readings sorted by time, oldest first. Returns null if there is too little data. */
        fun from(readings: List<GV>): GlucoseProfileStats? {
            val data = readings.filter { it.value > 30.0 }.sortedBy { it.timestamp }
            if (data.size < 288) return null
            val values = data.map { it.value }
            val mean = values.average()
            val sd = sqrt(values.map { (it - mean) * (it - mean) }.average())
            val n = values.size.toDouble()
            fun pct(count: Int, total: Double) = if (total <= 0.0) 0.0 else count * 100.0 / total

            val calendar = Calendar.getInstance()
            val night = data.filter {
                calendar.timeInMillis = it.timestamp
                calendar.get(Calendar.HOUR_OF_DAY) < 6
            }
            val nightN = night.size.toDouble()

            // Find meal-like rises with a simple scan over the curve.
            var rises = 0
            var peakSum = 0.0
            var slow = 0
            var lastRiseTime = 0L
            var i = 0
            while (i < data.size) {
                val start = data[i]
                val end = data.firstOrNull { it.timestamp >= start.timestamp + RISE_WINDOW_MS }
                if (end != null && end.value - start.value >= RISE_MGDL &&
                    start.timestamp - lastRiseTime > LOOKAHEAD_MS
                ) {
                    val window = data.filter { it.timestamp >= start.timestamp && it.timestamp <= start.timestamp + LOOKAHEAD_MS }
                    peakSum += (window.maxOfOrNull { it.value } ?: start.value) - start.value
                    val later = data.firstOrNull { it.timestamp >= start.timestamp + AFTER_MS }
                    if (later != null && later.value > HIGH) slow++
                    rises++
                    lastRiseTime = start.timestamp
                }
                i++
            }

            val spanDays = max((data.last().timestamp - data.first().timestamp) / (24 * 3600 * 1000.0), 0.1)
            return GlucoseProfileStats(
                days = spanDays,
                readings = data.size,
                meanMgdl = mean,
                cvPercent = if (mean > 0) sd / mean * 100.0 else 0.0,
                tirPercent = pct(values.count { it in LOW..HIGH }, n),
                lowPercent = pct(values.count { it < LOW }, n),
                veryLowPercent = pct(values.count { it < VERY_LOW }, n),
                highPercent = pct(values.count { it > HIGH }, n),
                veryHighPercent = pct(values.count { it > VERY_HIGH }, n),
                nightLowPercent = pct(night.count { it.value < LOW }, nightN),
                nightHighPercent = pct(night.count { it.value > HIGH }, nightN),
                risesPerDay = rises / spanDays,
                avgRisePeakMgdl = if (rises > 0) peakSum / rises else 0.0,
                slowRecoveryPercent = pct(slow, rises.toDouble())
            )
        }
    }
}

/**
 * Offline advisor. For every AIMI on/off setting it says in Russian whether to enable it and why.
 * The text is built from the user's own glucose, so it changes when the glucose behavior changes.
 * Shown in the settings screen for Russian language only (see [PreferenceHints]).
 */
@Singleton
class OfflinePreferenceAdvisor @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val aapsLogger: AAPSLogger
) {

    @Volatile private var hints: Map<String, PreferenceHint> = emptyMap()
    @Volatile private var lastRefreshMs = 0L
    @Volatile private var stats: GlucoseProfileStats? = null
    private val numberNames = HashMap<String, String>()
    private val numberKinds: Map<String, NumberKind> by lazy { buildNumberKinds() }

    fun install() {
        PreferenceHints.provider = { key -> hints[key] }
        PreferenceHints.profileProvider = { section, values, times, mgdl -> adviseProfile(section, values, times, mgdl) }
        PreferenceHints.numberProvider = { key, value, min, max, isInt -> adviseNumber(key, value, min, max, isInt) }
    }

    /** Reads the last [DAYS] days and rebuilds all advice. */
    suspend fun refresh() {
        try {
            val now = System.currentTimeMillis()
            val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - DAYS * DAY_MS, now, ascending = true)
            stats = GlucoseProfileStats.from(readings)
            hints = buildHints(stats)
            lastRefreshMs = now
            PreferenceHints.bump()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Offline advisor refresh failed", e)
        }
    }

    suspend fun refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshMs > STALE_MS) refresh()
    }

    internal fun buildHints(s: GlucoseProfileStats?): Map<String, PreferenceHint> {
        val result = HashMap<String, PreferenceHint>()
        for (key in BooleanKey.entries) {
            result[key.key] = adviseFor(key.name, s)
        }
        return result
    }

    internal fun adviseFor(name: String, s: GlucoseProfileStats?): PreferenceHint {
        if (!isAimi(name)) return generalAdvice(name, s)
        // Groups that do not depend on glucose.
        when {
            name in DIAGNOSTIC -> return skip("служебная функция, на сахар не влияет")
            name in NEEDS_INTERNET -> return skip("нужен интернет и облачный ИИ, офлайн не работает")
            name in NEEDS_WATCH -> return skip("нужны данные часов или датчиков, без них пользы нет")
            name == "OApsAIMIMealAdvisorTrigger" -> return skip("вы не вводите еду, запросы про еду вам не нужны")
            name == "OApsAIMIpregnancy" -> return skip("только при беременности, по сахару это не определить")
            name in PERSONAL -> return skip("включайте только если это про вас, по сахару не определить")
        }
        if (name == "OApsAIMIUndeclaredCobEnabled") {
            return enable("вы не вводите углеводы, функция находит еду по росту сахара")
        }
        if (s == null) return caution("мало данных, нужно хотя бы 3 дня сахаров")

        val tir = s.tirPercent.roundToInt()
        val low = fmt(s.lowPercent)
        val high = s.highPercent.roundToInt()
        return when {
            name in NIGHT -> when {
                s.nightHighPercent >= 25 -> enable("ночью выше 180 в ${s.nightHighPercent.roundToInt()}% времени")
                s.nightLowPercent >= 3 -> caution("ночью ниже 70 в ${fmt(s.nightLowPercent)}% времени, не усиливайте")
                else -> skip("ночью сахар в норме (выше 180 только ${s.nightHighPercent.roundToInt()}%)")
            }

            name in SAFETY -> when {
                s.lowPercent >= 2 || s.nightLowPercent >= 3 -> enable("низкие есть: $low% времени ниже 70, страховка нужна")
                s.highPercent > 35 && s.lowPercent < 1 -> caution("высоких $high%, низких $low%, защита может лишний раз сдерживать инсулин")
                else -> enable("безопасная страховка, низких мало ($low%)")
            }

            name in LEARNING -> when {
                s.days >= 7 -> enable("данных хватает: ${s.days.roundToInt()} дн., модель успеет подстроиться под вас")
                else -> caution("пока мало данных (${s.days.roundToInt()} дн.), нужно хотя бы неделя")
            }

            name in MEAL_BOOST -> when {
                s.lowPercent >= 4 -> caution("частые низкие ($low% ниже 70), усиление опасно")
                s.slowRecoveryPercent >= 30 || s.avgRisePeakMgdl >= 70 || s.highPercent >= 25 ->
                    enable("после еды сахар долго высокий: подъём в среднем ${s.avgRisePeakMgdl.roundToInt()} мг/дл, через 2 ч выше 180 в ${s.slowRecoveryPercent.roundToInt()}%")
                s.tirPercent >= 80 -> skip("и так хорошо: в диапазоне $tir% времени")
                else -> skip("подъёмы после еды невысокие (в среднем ${s.avgRisePeakMgdl.roundToInt()} мг/дл)")
            }

            else -> when {
                s.lowPercent >= 4 -> caution("частые низкие ($low% ниже 70), сначала снизьте агрессивность")
                s.tirPercent >= 80 -> skip("результат уже хороший: в диапазоне $tir% времени, лучше не менять")
                s.highPercent >= 30 -> enable("высоких много ($high% выше 180), функция может помочь")
                else -> skip("заметного эффекта по вашим сахарам не ожидается (в диапазоне $tir%)")
            }
        }
    }

    private enum class NumberKind { MORE_INSULIN, MORE_INSULIN_MULTIPLIER, INTERVAL, OTHER }

    private fun buildNumberKinds(): Map<String, NumberKind> {
        val map = HashMap<String, NumberKind>()
        for (k in DoubleKey.entries) {
            val n = k.name
            if (n.contains("State") || n.startsWith("OApsAIMILast")) continue
            numberNames[k.key] = n
            map[k.key] = when (n) {
                in INSULIN_MULTIPLIERS -> NumberKind.MORE_INSULIN_MULTIPLIER
                in INSULIN_AMOUNTS -> NumberKind.MORE_INSULIN
                else -> NumberKind.OTHER
            }
        }
        for (k in IntKey.entries) {
            val n = k.name
            numberNames[k.key] = n
            map[k.key] = if (n.endsWith("interval") || n == "ApsMaxSmbFrequency") NumberKind.INTERVAL
            else if (n in INSULIN_AMOUNTS) NumberKind.MORE_INSULIN else NumberKind.OTHER
        }
        return map
    }

    /** Says which value is better now for a number setting, based on the same glucose stats. */
    internal fun adviseNumber(key: String, value: Double, min: Double, max: Double, isInt: Boolean): PreferenceHint? {
        val kind = numberKinds[key] ?: return null
        val s = stats ?: return PreferenceHint(PreferenceHintLevel.ENABLE, "(Лучше: пока оставить ${show(value, isInt)}, мало данных, нужно хотя бы 3 дня сахаров)")
        val tir = s.tirPercent.roundToInt()
        // Direction: -1 = less insulin, +1 = more insulin, 0 = keep.
        val direction: Int
        val reason: String
        when {
            s.lowPercent >= 4 -> { direction = -1; reason = "частые низкие: ${fmt(s.lowPercent)}% времени ниже 70" }
            s.lowPercent >= 2 -> { direction = -1; reason = "низкие бывают: ${fmt(s.lowPercent)}% времени ниже 70" }
            s.highPercent >= 30 || s.slowRecoveryPercent >= 40 ->
                { direction = 1; reason = "сахар часто высокий: выше 180 в ${s.highPercent.roundToInt()}%, после подъёма через 2 ч всё ещё выше 180 в ${s.slowRecoveryPercent.roundToInt()}%" }
            else -> { direction = 0; reason = "сахар в норме, в диапазоне $tir% времени" }
        }
        val target = when (kind) {
            NumberKind.OTHER -> value
            NumberKind.INTERVAL -> value - direction // more insulin = shorter pause between SMB
            NumberKind.MORE_INSULIN -> value * (1.0 + 0.15 * direction)
            NumberKind.MORE_INSULIN_MULTIPLIER -> 1.0 + (value - 1.0) * (1.0 + 0.3 * direction)
        }.coerceIn(min, max)
        val recommended = if (isInt) target.roundToInt().toDouble() else Math.round(target * 100.0) / 100.0
        val text = when {
            kind == NumberKind.OTHER && UI_PREFIXES.any { key.isNotEmpty() && numberNames[key]?.startsWith(it) == true } ->
                "(Лучше: оставить ${show(value, isInt)}, на сахар не влияет)"

            kind == NumberKind.OTHER ->
                if (s.tirPercent >= 80) "(Лучше: оставить ${show(value, isInt)}, в диапазоне $tir% времени, менять не нужно)"
                else "(Лучше: оставить ${show(value, isInt)}, сначала настройте лимиты SMB и интервалы, это влияет сильнее)"

            recommended == value || direction == 0 ->
                "(Лучше: оставить ${show(value, isInt)}, $reason)"

            else -> "(Лучше: ${show(recommended, isInt)}, сейчас ${show(value, isInt)}: $reason)"
        }
        return PreferenceHint(PreferenceHintLevel.ENABLE, text)
    }

    private fun show(v: Double, isInt: Boolean) =
        if (isInt) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun isAimi(name: String) = name.startsWith("OApsAIMI") || name.startsWith("Aimi")

    /** Advice for the normal AAPS settings (not AIMI-only). */
    private fun generalAdvice(name: String, s: GlucoseProfileStats?): PreferenceHint {
        when (name) {
            "ApsUseSmb" -> return enable("SMB это основа вашей петли без ввода углеводов")
            "ApsUseSmbAlways" -> return enable("вы не вводите углеводы, SMB должен работать всегда, а не только при COB")
            "ApsUseSmbWithCob", "ApsUseSmbAfterCarbs" -> return skip("вы не вводите углеводы, COB не бывает, условие не сработает")
            "ApsUseSmbWithLowTt", "ApsUseSmbWithHighTt" -> return skip("временные цели вы не используете, SMB и так работает всегда")
            "ApsUseUam" -> return enable("еда не вводится, UAM находит её по росту сахара")
            "ApsUseAutosens", "ApsUseDynamicSensitivity", "ApsDynIsfAdjustSensitivity" ->
                return skip("чувствительность считает сам AIMI")
            "AutotuneAutoSwitchProfile" -> return caution("авто-переключение профиля сбивает настройки AIMI, делайте вручную")
            "AlertHypo" -> return enable("защита от низких, это безопасность")
            "AlertRapidFall" -> return enable("быстрое падение сахара опасно при петле")
            "AlertMissedBgReading" -> return enable("без данных датчика петля не работает")
            "AlertPumpUnreachable" -> return enable("если помпа не на связи, инсулин не подаётся")
            "AlertHyper" -> return if (s != null && s.highPercent >= 20) enable("высоких много (${s.highPercent.roundToInt()}% выше 180)") else skip("высоких мало, шум не нужен")
            "AlertCarbsRequired", "ApsAlwaysUseShortDeltas" -> return skip("вы не вводите углеводы, функция вам не нужна")
            "NsClientUploadData" -> return enable("данные уходят в ваш Nightscout, оттуда графики и анализ")
            "SmsAllowRemoteCommands" -> return caution("удалённые команды рискованны, включайте только если реально нужны")
            "PumpBtWatchdog" -> return enable("помогает вернуть связь с помпой по Bluetooth")
            "OverviewUseBolusAdvisor", "OverviewUseBolusReminder", "OverviewUseSuperBolus" ->
                return skip("вы не вводите болюсы и углеводы сами, функция не нужна")
        }
        val noEffect = UI_PREFIXES.any { name.startsWith(it) }
        return when {
            noEffect -> skip("оформление, связь или уведомления, на сахар не влияет")
            s == null -> caution("мало данных, нужно хотя бы 3 дня сахаров")
            s.lowPercent >= 4 -> caution("частые низкие (${fmt(s.lowPercent)}% ниже 70), не усиливайте")
            s.tirPercent >= 80 -> skip("результат уже хороший: в диапазоне ${s.tirPercent.roundToInt()}% времени, лучше не менять")
            else -> skip("заметного эффекта по вашим сахарам не ожидается")
        }
    }

    /** Profile advice: [section] is basal, isf, ic or target. Values are per time slot. */
    internal fun adviseProfile(section: String, values: List<Double>, times: List<Int>, mgdl: Boolean): List<String> {
        val s = stats ?: return listOf("(Лучше: пока оставить, мало данных, нужно хотя бы 3 дня сахаров)")
        val lowSoon = s.lowPercent >= 2
        val highLong = s.highPercent >= 30 && s.lowPercent < 2
        val lines = ArrayList<String>()
        when (section) {
            "ic" -> lines += "(Не нужно менять: вы не вводите углеводы, на вашу петлю не влияет)"
            "basal" -> {
                val total = dailyTotal(values, times)
                when {
                    lowSoon -> lines += "(Лучше: около ${show(total * 0.9, false)} ЕД в сутки вместо ${show(total, false)}, это на 10% меньше: низкие ${fmt(s.lowPercent)}% времени)"
                    highLong -> lines += "(Лучше: около ${show(total * 1.1, false)} ЕД в сутки вместо ${show(total, false)}, это на 10% больше: выше 180 в ${s.highPercent.roundToInt()}% времени)"
                    else -> lines += "(Лучше: оставить ${show(total, false)} ЕД в сутки, сахар в норме, в диапазоне ${s.tirPercent.roundToInt()}% времени)"
                }
                if (s.nightLowPercent >= 3) lines += "(Ночью 00:00–06:00 базал лучше снизить на 10–15%: ночью ниже 70 в ${fmt(s.nightLowPercent)}% времени)"
                else if (s.nightHighPercent >= 25 && s.nightLowPercent < 2) lines += "(Ночью 00:00–06:00 базал лучше поднять на 10%: ночью выше 180 в ${s.nightHighPercent.roundToInt()}% времени)"
            }
            "isf" -> {
                val avg = values.average()
                when {
                    lowSoon -> lines += "(Лучше: около ${show(avg * 1.1, false)} вместо ${show(avg, false)}, чувствительность на 10% слабее: низкие ${fmt(s.lowPercent)}% времени)"
                    highLong -> lines += "(Лучше: около ${show(avg * 0.9, false)} вместо ${show(avg, false)}, чувствительность на 10% сильнее: выше 180 в ${s.highPercent.roundToInt()}% времени)"
                    else -> lines += "(Лучше: оставить ${show(avg, false)}, сахар в норме, в диапазоне ${s.tirPercent.roundToInt()}% времени)"
                }
                lines += "(AIMI меняет ISF динамически, в профиле держите базовое значение)"
            }
            "target" -> {
                val avg = values.average()
                val step = if (mgdl) 10.0 else 0.5
                when {
                    lowSoon -> lines += "(Лучше: цель около ${show(avg + step, false)} вместо ${show(avg, false)}: низкие ${fmt(s.lowPercent)}% времени)"
                    highLong -> lines += "(Лучше: цель около ${show(avg - step, false)} вместо ${show(avg, false)}: выше 180 в ${s.highPercent.roundToInt()}% времени)"
                    else -> lines += "(Лучше: оставить ${show(avg, false)}, сахар в норме, в диапазоне ${s.tirPercent.roundToInt()}% времени)"
                }
            }
        }
        return lines
    }

    private fun dailyTotal(values: List<Double>, times: List<Int>): Double {
        if (values.isEmpty() || values.size != times.size) return 0.0
        var sum = 0.0
        for (i in values.indices) {
            val end = if (i + 1 < times.size) times[i + 1] else 24 * 3600
            sum += values[i] * (end - times[i]) / 3600.0
        }
        return sum
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

    private fun enable(reason: String) = PreferenceHint(PreferenceHintLevel.ENABLE, "(Включить: $reason)")
    private fun skip(reason: String) = PreferenceHint(PreferenceHintLevel.SKIP, "(Не нужно: $reason)")
    private fun caution(reason: String) = PreferenceHint(PreferenceHintLevel.CAUTION, "(Осторожно: $reason)")

    companion object {

        const val DAYS = 14L
        private const val DAY_MS = 24 * 3600 * 1000L
        private const val STALE_MS = 10 * 60 * 1000L

        private val INSULIN_AMOUNTS = setOf(
            "OApsAIMIMaxSMB", "OApsAIMIHighBGMaxSMB", "OApsAIMIFCLFactor", "OApsAIMIMealFactor", "OApsAIMIBFFactor",
            "OApsAIMILunchFactor", "OApsAIMIDinnerFactor", "OApsAIMIHCFactor", "OApsAIMISnackFactor", "OApsAIMIsleepFactor",
            "OApsAIMIPriorityMaxIobExtraU", "OApsAIMIAnticipBudgetU", "OApsAIMIautodrivePrebolus",
            "OApsAIMINightGrowthMaxSmbClamp", "OApsAIMINightGrowthMaxIobExtra", "ApsMaxBasal", "ApsSmbMaxIob", "ApsAmaMaxIob",
            "ApsMaxMinutesOfBasalToLimitSmb", "ApsUamMaxMinutesOfBasalToLimitSmb"
        )
        private val INSULIN_MULTIPLIERS = setOf(
            "OApsAIMIPriorityMaxIobFactor", "OApsAIMINightGrowthSmbMultiplier", "OApsAIMINightGrowthBasalMultiplier",
            "OApsAIMIAdaptiveBasalMaxScaling", "OApsAIMIActivityBasalCapFactor", "ApsMaxDailyMultiplier", "ApsMaxCurrentBasalMultiplier"
        )
        private val UI_PREFIXES = listOf(
            "General", "Overview", "Wear", "Xdrip", "Maintenance", "SiteRotation", "Eversense", "OpenHumans",
            "Sms", "VirtualPump", "NsClient", "BgSource", "OApsxdrip", "Alert", "Pump", "Safety"
        )
        private val DIAGNOSTIC = setOf(
            "OApsAIMIIntelligenceSnapshotExport", "OApsAIMIWCycleShadow", "OApsAIMIPredictionAuthorityShadow",
            "OApsAIMIRecursiveBeliefShadow", "OApsAIMIDynIsfTrajectoryShadowOnly", "OApsAIMILoopBlackboxFileEnabled",
            "OApsAIMIThyroidLogVerbosity", "AimiPhysioDebugLogs", "OApsAIMITpoNotifyOnApply",
            "OApsAIMIPkpdSetupWizardCompleted", "OApsAIMIStraightLineTubeAdvisorEnabled"
        )
        private val NEEDS_INTERNET = setOf(
            "OApsAIMIContextLLMEnabled", "AimiPhysioLLMAnalysisEnable", "OApsAIMITpoLlmConfirmEnabled",
            "OApsAIMIAdvisorLlmRichOref", "AimiAuditorEnabled", "OApsAIMIAuditorProfileFactors"
        )
        private val NEEDS_WATCH = setOf(
            "OApsAIMIEnableStepsFromWatch", "AimiPhysioAssistantEnable", "AimiPhysioSleepDataEnable",
            "AimiPhysioHRVDataEnable", "OApsAIMIContextEnabled"
        )
        private val PERSONAL = setOf(
            "OApsAIMIhoneymoon", "OApsAIMIwcycle", "OApsAIMIWCycleRequireConfirm", "OApsAIMIT3cAdaptiveBasalEnabled",
            "OApsAIMIT3cPhysioInformedEnabled", "OApsAIMIT3cBrittleMode", "OApsAIMIT3cAutodriveBasalAuthority",
            "OApsAIMIT3cHyperBasalFloor", "OApsAIMIT3cCfrdMode", "OApsAIMIT3cCfrdExacerbationMode",
            "AimiEndometriosisEnable", "AimiEndometriosisHormonalSuppression", "AimiEndometriosisPainFlare",
            "OApsAIMIThyroidEnabled", "OApsAIMITpoEnabled"
        )
        private val NIGHT = setOf("OApsAIMInight", "OApsAIMINightGrowthEnabled")
        private val SAFETY = setOf(
            "OApsAIMIforcelimits", "OApsAIMIRiseCeilingGuard", "OApsAIMIEffortActivityProtection", "OApsAIMIStressIsfFloor",
            "OApsAIMIPeakGovernorEnabled", "OApsAIMIDiaGovernorEnabled", "OApsAIMIIobSurveillanceGuard",
            "OApsAIMITrajectoryGuardEnabled", "OApsAIMIBasalSlewLimitEnabled", "OApsAIMIPkpdStackAwareGuardB",
            "OApsAIMIBasalChannelSafetyGuards", "OApsAIMIDynamicBasalStackCap", "OApsAIMIBasalStackTerminalCap",
            "OApsAIMIBasalTerminalInvariants", "OApsAIMITubeHyperClampPhysicalBound", "AimiCosineGateEnabled",
            "AimiEmergencySosEnable", "OApsAIMISensorConfidenceCgmFirst", "OApsAIMILoopExclusiveInvocationEnabled",
            "OApsAIMITubeVetoIgnoreFloorArtefact"
        )
        private val LEARNING = setOf(
            "OApsAIMIMLtraining", "OApsAIMIPkpdEnabled", "OApsAIMIIntelligenceSingleLearnPath",
            "OApsAIMIIntelligenceKineticsProfiler", "OApsAIMIDynIsfTrajectoryTuningEnabled", "OApsAIMIUnifiedReactivityEnabled",
            "OApsAIMIPkpdCurveLearningGate", "OApsAIMIPkpdPredictionKinetics", "OApsAIMIPredictionAuthorityEnabled",
            "OApsAIMIRecursiveBeliefAuthority", "OApsAIMIRecursiveBeliefWavelet", "OApsAIMIAimiSmbComparatorEnabled",
            "OApsAIMIAdvisorPersonalOrefMl"
        )
        private val MEAL_BOOST = setOf(
            "OApsAIMIEnableBasal", "OApsAIMIAutodriveV3EnhancedGater", "OApsAIMIautoDriveActive",
            "OApsAIMIautodriveAggressiveSmbFloor", "OApsAIMIAnticipBasalFloor", "OApsAIMIAnticipMealEvidence",
            "OApsAIMIautoDriveAuthoritative", "OApsAIMIMealHyperBypassEnabled", "OApsAIMITreeMealRiseFrontLoad",
            "OApsAIMIMealConfirmedEarlyRelease", "OApsAIMIHyperDroppingExemptEnabled", "OApsAIMIPkpdPragmaticReliefEnabled",
            "OApsAIMIEffectiveIobReleaseEnabled", "OApsAIMIHyperTrajectoryRelease", "OApsAIMIHyperTrajectoryReleaseAggressive",
            "OApsAIMIPkpdEndogenousReversion", "OApsAIMIPkpdHyperReversion", "OApsAIMIBasalProjectedError",
            "OApsAIMIForcedTbrRealPredDelta", "OApsAIMIEngineHyperRateBound", "OApsAIMITrajBridgeBasalSurvives"
        )
    }
}
