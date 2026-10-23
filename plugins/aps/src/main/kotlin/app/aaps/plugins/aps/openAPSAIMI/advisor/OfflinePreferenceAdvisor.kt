package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
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
            val h = adviseFor(key.name, s)
            result[key.key] = h.copy(text = h.text + "\n" + explainSwitch(key.name, s))
        }
        // Lists and text settings: one general advice line.
        for (key in IntKey.entries) {
            if (key.key !in result) result[key.key] = adviseChoice(key.name, s)
        }
        for (key in StringKey.entries) {
            if (key.key in result || SECRET_WORDS.any { key.name.contains(it, ignoreCase = true) }) continue
            result[key.key] = adviseChoice(key.name, s)
        }
        result[PKPD_UI_PRESET] = presetHint(s)
        return result
    }

    private fun presetHint(s: GlucoseProfileStats?): PreferenceHint {
        val text = when {
            s == null -> "(Лучше: оставить текущий пресет, мало данных, нужно хотя бы 3 дня сахаров)"
            s.lowPercent >= 2 -> "(Лучше: более «медленный» пресет, низкие бывают: ${fmt(s.lowPercent)}% времени ниже 70)"
            else -> "(Лучше: пресет, который совпадает с вашим инсулином, сейчас подгонять по сахару нет причин)"
        }
        val why = "Почему так: пресет задаёт форму действия инсулина (пик и длительность). Тип вашего инсулина по графику сахара не определить, поэтому главное правило: выбирайте пресет, соответствующий реальному инсулину. Если же низкие бывают от 2% времени, возможно инсулин действует дольше, чем в пресете, и тогда выбирайте более медленный: так петля дольше помнит про поданную дозу. Дальше PK/PD сам уточняет форму по вашим данным."
        return PreferenceHint(PreferenceHintLevel.ENABLE, text + "\n" + why + " " + dataLine(s))
    }

    private fun adviseChoice(name: String, s: GlucoseProfileStats?): PreferenceHint {
        val ui = UI_PREFIXES.any { name.startsWith(it) }
        val text = when {
            ui -> "(Лучше: оставить текущий выбор, на сахар не влияет)"
            s == null -> "(Лучше: оставить текущий выбор, мало данных, нужно хотя бы 3 дня сахаров)"
            s.lowPercent >= 4 -> "(Лучше: выбирать более осторожный вариант, ниже 70 в ${fmt(s.lowPercent)}% времени)"
            s.highPercent >= 30 -> "(Лучше: выбирать более активный вариант, выше 180 в ${s.highPercent.roundToInt()}% времени)"
            else -> "(Лучше: оставить текущий выбор, в диапазоне ${s.tirPercent.roundToInt()}% времени)"
        }
        val why = when {
            ui -> "Почему так: это настройка внешнего вида, связи или уведомлений. Она не меняет дозы инсулина, поэтому менять её по сахару незачем."
            else -> "Почему так: у этого списка нет числа, которое можно сверить с графиком, поэтому совет общий. При частых низких (от 4% времени ниже 70) выбирайте осторожнее, при частых высоких (выше 180 от 30% времени) активнее, иначе оставьте как есть."
        }
        return PreferenceHint(PreferenceHintLevel.ENABLE, text + "\n" + why + " " + dataLine(s))
    }

    private fun dataLine(s: GlucoseProfileStats?): String =
        if (s == null) "Данных пока мало (нужно хотя бы 3 дня сахаров), поэтому совет осторожный."
        else "Ваши сахара за ${s.days.roundToInt()} дн.: в диапазоне 70–180 ${s.tirPercent.roundToInt()}%, ниже 70 ${fmt(s.lowPercent)}%, " +
            "выше 180 ${s.highPercent.roundToInt()}%, ночью (00–06) ниже 70 ${fmt(s.nightLowPercent)}% и выше 180 ${s.nightHighPercent.roundToInt()}%, " +
            "подъёмов после еды ${fmt(s.risesPerDay)} в сутки (в среднем +${s.avgRisePeakMgdl.roundToInt()} мг/дл), " +
            "через 2 часа после подъёма выше 180 в ${s.slowRecoveryPercent.roundToInt()}% случаев, разброс (CV) ${s.cvPercent.roundToInt()}%."

    /** Long explanation: what the setting does, why this advice, and what the other choice would cause. */
    private fun explainSwitch(name: String, s: GlucoseProfileStats?): String {
        val why = when {
            !isAimi(name) -> when {
                name in GENERAL_SPECIFIC -> "Эта настройка важна для вашей схемы: петля работает сама, без ввода углеводов и инсулина. Выбор указан выше из-за этого, а не из-за графика."
                UI_PREFIXES.any { name.startsWith(it) } -> "Это оформление, связь или уведомления. Они не меняют дозы инсулина, поэтому на сахар не влияют, а включать их стоит лишь по удобству."
                else -> GENERAL_WHY
            }
            name in DIAGNOSTIC -> "Это журнал, отладка или теневой режим: он только записывает данные и не меняет дозы инсулина. Включать нужно лишь для поиска ошибок, постоянная запись занимает память и батарею."
            name in NEEDS_INTERNET -> "Функция отправляет данные во внешний ИИ и работает только с интернетом. Вы работаете офлайн, ответы могут задерживаться или не приходить, поэтому полагаться на них при расчёте инсулина нельзя."
            name in NEEDS_WATCH -> "Нужны шаги, пульс или сон с часов или датчика. Без них функция получает пустой вход и ничего не меняет, а включённая зря она только расходует батарею."
            name == "OApsAIMIMealAdvisorTrigger" -> "Эта функция просит указать еду. Вы углеводы не вводите, значит её запросы будут впустую."
            name == "OApsAIMIUndeclaredCobEnabled" -> "Вы не вводите углеводы, поэтому петля не знает о еде. Функция оценивает съеденное по скорости роста сахара и позволяет вовремя подать SMB. Без неё реакция на еду была бы запоздалой."
            name == "OApsAIMIpregnancy" || name in PERSONAL -> "Этот режим меняет потребность в инсулине под состояние организма (беременность, цикл, щитовидная железа и т. п.). График сам не показывает, есть ли такое состояние, поэтому включать нужно только если оно у вас есть: иначе модель будет ошибочно менять дозы."
            name in NIGHT -> "Ночью нет еды и движения, поэтому высокие или низкие значения прямо показывают, верен ли ночной базал. Порог «Включить»: ночью выше 180 не менее 25% времени. Порог «Осторожно»: ниже 70 не менее 3% времени."
            name in SAFETY -> "Это ограничители дозы. Они срабатывают в опасные моменты (падение сахара, большой запас активного инсулина) и почти не мешают в остальное время. «Включить»: низкие от 2% времени. «Осторожно»: выше 180 больше 35% и ниже 70 меньше 1%, тогда защита может держать дозы слишком маленькими."
            name in LEARNING -> "Обучающиеся модули подстраиваются под ваши реакции на инсулин и еду. Им нужна история: за меньше чем неделю данных они учат шум. Поэтому до недели советую осторожность, после недели включать."
            name in MEAL_BOOST -> "Это ускорители реакции на еду и рост сахара. Они нужны, когда после подъёма сахар долго остаётся высоким (порог: выше 180 через 2 часа в 30% подъёмов, или средний подъём 70 мг/дл и больше, или выше 180 в 25% времени). При частых низких (от 4% времени) усиление дозы опасно, поэтому тогда «Осторожно». При хорошем результате (в диапазоне 80% и больше) менять незачем."
            else -> GENERAL_WHY
        }
        return "Почему так: $why ${dataLine(s)}"
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

    private enum class NumberKind { MORE_INSULIN, MORE_INSULIN_MULTIPLIER, INTERVAL, LOW_MARK, HIGH_MARK, LGS_THRESHOLD, PKPD_DIA, PKPD_PEAK, PKPD_DAMPING, PKPD_LEVEL, OTHER }

    private fun buildNumberKinds(): Map<String, NumberKind> {
        val map = HashMap<String, NumberKind>()
        for (k in DoubleKey.entries) {
            val n = k.name
            if (n.contains("State") || n.startsWith("OApsAIMILast")) continue
            numberNames[k.key] = n
            map[k.key] = when (n) {
                in INSULIN_MULTIPLIERS -> NumberKind.MORE_INSULIN_MULTIPLIER
                in INSULIN_AMOUNTS -> NumberKind.MORE_INSULIN
                "OApsAIMIPkpdInitialDiaH", "OApsAIMIPkpdAnchorDiaH" -> NumberKind.PKPD_DIA
                "OApsAIMIPkpdInitialPeakMin", "OApsAIMIPkpdAnchorPeakMin" -> NumberKind.PKPD_PEAK
                "OApsAIMISmbTailDamping", "OApsAIMISmbExerciseDamping", "OApsAIMISmbLateFatDamping" -> NumberKind.PKPD_DAMPING
                else -> NumberKind.OTHER
            }
        }
        // Sliders on the PK/PD simple screen have no key of their own.
        map[PKPD_UI_CORRECTIONS] = NumberKind.PKPD_LEVEL
        map[PKPD_UI_TAIL] = NumberKind.PKPD_LEVEL
        for (k in UnitDoubleKey.entries) {
            numberNames[k.key] = k.name
            map[k.key] = when (k.name) {
                "OverviewLowMark" -> NumberKind.LOW_MARK
                "OverviewHighMark" -> NumberKind.HIGH_MARK
                "ApsLgsThreshold" -> NumberKind.LGS_THRESHOLD
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
        if (kind == NumberKind.LOW_MARK || kind == NumberKind.HIGH_MARK || kind == NumberKind.LGS_THRESHOLD) {
            val mmol = max <= 60.0
            val unit = if (mmol) "ммоль/л" else "мг/дл"
            fun conv(mgdl: Double) = if (mmol) Math.round(mgdl / 18.0 * 10.0) / 10.0 else mgdl
            return when (kind) {
                NumberKind.LOW_MARK -> PreferenceHint(PreferenceHintLevel.ENABLE,
                    "(Лучше: ${show(conv(70.0), false)} $unit, сейчас ${show(value, isInt)})\nПочему так: 70 мг/дл (3,9 ммоль/л) это общепринятая нижняя граница диапазона. Если поставить ниже, низкие значения перестанут считаться низкими в статистике, а если выше, статистика покажет много «низких», которых на деле нет. ${dataLine(s)}")

                NumberKind.HIGH_MARK -> PreferenceHint(PreferenceHintLevel.ENABLE,
                    "(Лучше: ${show(conv(180.0), false)} $unit, сейчас ${show(value, isInt)})\nПочему так: 180 мг/дл (10,0 ммоль/л) это общепринятая верхняя граница диапазона, по ней считают время в диапазоне и по ней сравнивают результаты. Другое значение сделает статистику несравнимой. ${dataLine(s)}")

                else -> {
                    val up = s.lowPercent >= 2
                    val target = (if (up) value + (if (mmol) 0.3 else 5.0) else value).coerceIn(min, max)
                    val shown = if (isInt) target.roundToInt().toDouble() else Math.round(target * 10.0) / 10.0
                    PreferenceHint(PreferenceHintLevel.ENABLE,
                        (if (up) "(Лучше: ${show(shown, isInt)} $unit вместо ${show(value, isInt)}: низкие бывают, ${fmt(s.lowPercent)}% времени ниже 70)" else "(Лучше: оставить ${show(value, isInt)} $unit, низких мало, ${fmt(s.lowPercent)}% времени)") +
                            "\nПочему так: порог отключения подачи при низком сахаре. Чем он выше, тем раньше петля прекращает инсулин при падении, и тем меньше риск гипо, но тем чаще инсулин урезается зря. Поднимаю на 5 мг/дл (0,3 ммоль/л), если ниже 70 бывает от 2% времени; иначе оставляю. ${dataLine(s)}")
                }
            }
        }
        if (kind == NumberKind.PKPD_DIA || kind == NumberKind.PKPD_PEAK || kind == NumberKind.PKPD_DAMPING || kind == NumberKind.PKPD_LEVEL) {
            val lows = s.lowPercent >= 2
            val highs = s.highPercent >= 30 && s.lowPercent < 2
            val step = when (kind) {
                NumberKind.PKPD_DIA -> 0.5
                NumberKind.PKPD_PEAK -> 10.0
                NumberKind.PKPD_DAMPING -> 0.05
                else -> 0.1
            }
            // For damping, a smaller number means stronger damping. For levels, left = cautious.
            val sign = when (kind) {
                NumberKind.PKPD_DIA, NumberKind.PKPD_PEAK -> if (lows) 1 else 0
                NumberKind.PKPD_DAMPING -> if (lows) -1 else if (highs) 1 else 0
                else -> if (lows) -1 else if (highs) 1 else 0
            }
            val target = (value + sign * step).coerceIn(min, max)
            val shown = Math.round(target * 100.0) / 100.0
            val reason = when {
                lows -> "низкие бывают: ${fmt(s.lowPercent)}% времени ниже 70"
                highs -> "выше 180 в ${s.highPercent.roundToInt()}% времени при редких низких"
                else -> "сахар в норме, в диапазоне $tir% времени"
            }
            val short = if (sign == 0 || shown == value) "(Лучше: оставить ${show(value, isInt)}, $reason)"
            else "(Лучше: ${show(shown, isInt)} вместо ${show(value, isInt)}: $reason)"
            val why = when (kind) {
                NumberKind.PKPD_DIA -> "DIA это время, за которое инсулин полностью заканчивает действовать. Если оно в модели короче настоящего, петля думает, что инсулина уже нет, и подаёт новый поверх старого: отсюда поздние низкие. Поэтому при низких от 2% времени удлиняю на 0,5 часа. При высоких DIA сокращать нельзя: так модель решит, что инсулин «ушёл», и накопит запас, который даст гипо позже. Сам PK/PD дальше подстраивает значение под вас в пределах границ."
                NumberKind.PKPD_PEAK -> "Пик это время наибольшего действия инсулина после подачи. Если реально инсулин действует позже, чем в модели, петля считает пик пройденным и добавляет новую дозу, и появляются низкие. Поэтому при низких от 2% времени сдвигаю пик на 10 минут позже; иначе оставляю. По графику сахара точный пик не определить, так что шаг маленький."
                NumberKind.PKPD_DAMPING -> "Это коэффициент ослабления SMB в «хвосте» действия инсулина (поздняя фаза, после нагрузки или жирной еды). Чем он меньше, тем сильнее ослабление и тем осторожнее петля. При низких от 2% времени уменьшаю на 0,05 (осторожнее); при долгих высоких и редких низких увеличиваю на 0,05 (смелее); иначе оставляю."
                else -> "Это ползунок осторожности: влево петля осторожнее и подаёт меньше, вправо смелее. При низких от 2% времени сдвигаю на 0,1 влево, при долгих высоких (выше 180 от 30% времени) и редких низких на 0,1 вправо. Шаг маленький, потому что результат виден только через несколько часов."
            }
            return PreferenceHint(PreferenceHintLevel.ENABLE, short + "\nПочему так: " + why + " " + dataLine(s))
        }
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
            NumberKind.INTERVAL -> value - direction // more insulin = shorter pause between SMB
            NumberKind.MORE_INSULIN -> value * (1.0 + 0.15 * direction)
            NumberKind.MORE_INSULIN_MULTIPLIER -> 1.0 + (value - 1.0) * (1.0 + 0.3 * direction)
            else -> value
        }.coerceIn(min, max)
        val recommended = if (isInt) target.roundToInt().toDouble() else Math.round(target * 100.0) / 100.0
        val short = when {
            kind == NumberKind.OTHER && UI_PREFIXES.any { numberNames[key]?.startsWith(it) == true } ->
                "(Лучше: оставить ${show(value, isInt)}, на сахар не влияет)"

            kind == NumberKind.OTHER ->
                if (s.tirPercent >= 80) "(Лучше: оставить ${show(value, isInt)}, в диапазоне $tir% времени, менять не нужно)"
                else "(Лучше: оставить ${show(value, isInt)}, сначала настройте лимиты SMB и интервалы, это влияет сильнее)"

            recommended == value || direction == 0 ->
                "(Лучше: оставить ${show(value, isInt)}, $reason)"

            else -> "(Лучше: ${show(recommended, isInt)}, сейчас ${show(value, isInt)}: $reason)"
        }
        val why = when (kind) {
            NumberKind.MORE_INSULIN -> "это лимит или доля инсулина. Если выше 180 держится долго, лимит слишком мал и инсулин не успевает; если низкие бывают, лимит велик. Двигаю его на 15% в нужную сторону: ниже 70 от 2% времени уменьшаю, выше 180 от 30% времени или долгое возвращение (в 40% подъёмов выше 180 через 2 часа) увеличиваю. Шаг небольшой, чтобы не получить резкое гипо или рост; после изменения смотрите 3–5 дней. Ставить сразу больше нельзя: эффект инсулина виден с задержкой, и резкий скачок даст гипо через несколько часов."
            NumberKind.MORE_INSULIN_MULTIPLIER -> "это множитель (1,0 значит без изменений). Меняю только ту часть, что выше 1,0, на 30%, поэтому значение не уходит далеко от безопасного. Правило то же: ниже 70 от 2% времени уменьшаю, выше 180 от 30% времени увеличиваю."
            NumberKind.INTERVAL -> "интервал это пауза между микроболюсами в минутах. Чем она короче, тем чаще SMB и быстрее реакция на рост сахара, но тем больше риск запаса инсулина. При высоких сокращаю на 1 минуту, при низких увеличиваю на 1, иначе оставляю."
            else -> "параметр не связан напрямую с дозой, либо его эффект нельзя оценить по графику сахара. Если результат хороший (в диапазоне 80% и больше), менять его незачем, а сначала настраивают лимиты SMB и интервалы: они дают основной эффект."
        }
        return PreferenceHint(PreferenceHintLevel.ENABLE, short + "\nПочему так: " + why + " " + dataLine(s))
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
            "ic" -> {
                lines += "(Не нужно менять: вы не вводите углеводы, на вашу петлю не влияет)"
                lines += "Почему так: IC (углеводный коэффициент) нужен только для расчёта болюса на введённые углеводы. Вы углеводы не вводите, еду петля находит по росту сахара, значит IC не участвует в дозах. ${dataLine(s)}"
            }
            "basal" -> {
                val total = dailyTotal(values, times)
                when {
                    lowSoon -> lines += "(Лучше: около ${show(total * 0.9, false)} ЕД в сутки вместо ${show(total, false)}, это на 10% меньше: низкие ${fmt(s.lowPercent)}% времени)"
                    highLong -> lines += "(Лучше: около ${show(total * 1.1, false)} ЕД в сутки вместо ${show(total, false)}, это на 10% больше: выше 180 в ${s.highPercent.roundToInt()}% времени)"
                    else -> lines += "(Лучше: оставить ${show(total, false)} ЕД в сутки, сахар в норме, в диапазоне ${s.tirPercent.roundToInt()}% времени)"
                }
                lines += "Почему так: базал это фоновая доза инсулина. Если ниже 70 бывает чаще 2% времени, фон завышен; если выше 180 больше 30% времени при редких низких, фон занижен. Шаг 10% безопасный: эффект базала виден через несколько часов, а резкий сдвиг даёт гипо. Менять лучше один раз и смотреть 3–5 дней. ${dataLine(s)}"
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
                lines += "Почему так: ISF это на сколько 1 ЕД снижает сахар. Чем он больше, тем слабее действует инсулин в расчётах. Низкие значит инсулин действует сильнее, чем в профиле, и ISF надо повысить; долго высокие значит слабее, и ISF надо понизить. Шаг 10% безопасный. ${dataLine(s)}"
            }
            "target" -> {
                val avg = values.average()
                val step = if (mgdl) 10.0 else 0.5
                when {
                    lowSoon -> lines += "(Лучше: цель около ${show(avg + step, false)} вместо ${show(avg, false)}: низкие ${fmt(s.lowPercent)}% времени)"
                    highLong -> lines += "(Лучше: цель около ${show(avg - step, false)} вместо ${show(avg, false)}: выше 180 в ${s.highPercent.roundToInt()}% времени)"
                    else -> lines += "(Лучше: оставить ${show(avg, false)}, сахар в норме, в диапазоне ${s.tirPercent.roundToInt()}% времени)"
                }
                lines += "Почему так: целевой сахар это уровень, к которому петля тянет сахар. Выше цель значит осторожнее (меньше риск низких), ниже цель значит активнее. Низкие от 2% времени: поднимаю цель на 10 мг/дл (0,5 ммоль/л); выше 180 больше 30% при редких низких: снижаю. ${dataLine(s)}"
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
        private val SECRET_WORDS = listOf("password", "pin", "secret", "token", "apikey", "api_key", "url", "uri", "email", "phone", "user", "name")
        const val PKPD_UI_CORRECTIONS = "pkpd_ui_corrections"
        const val PKPD_UI_TAIL = "pkpd_ui_tail"
        const val PKPD_UI_PRESET = "pkpd_ui_preset"
        private const val GENERAL_WHY = "Общая настройка: при частых низких (от 4% времени ниже 70) её нельзя усиливать, потому что лишний инсулин только добавит гипо. При результате в диапазоне 80% и выше менять не нужно. При выше 180 от 30% времени стоит попробовать, потому что инсулина явно не хватает."
        private val GENERAL_SPECIFIC = setOf(
            "ApsUseSmb", "ApsUseSmbAlways", "ApsUseSmbWithCob", "ApsUseSmbAfterCarbs", "ApsUseSmbWithLowTt", "ApsUseSmbWithHighTt",
            "ApsUseUam", "ApsUseAutosens", "ApsUseDynamicSensitivity", "ApsDynIsfAdjustSensitivity", "AutotuneAutoSwitchProfile",
            "AlertHypo", "AlertRapidFall", "AlertMissedBgReading", "AlertPumpUnreachable", "AlertHyper", "AlertCarbsRequired",
            "ApsAlwaysUseShortDeltas", "NsClientUploadData", "SmsAllowRemoteCommands", "PumpBtWatchdog", "OverviewUseBolusAdvisor",
            "OverviewUseBolusReminder", "OverviewUseSuperBolus"
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
