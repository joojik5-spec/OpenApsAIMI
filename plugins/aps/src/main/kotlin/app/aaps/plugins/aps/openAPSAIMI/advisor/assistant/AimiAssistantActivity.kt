package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiProfileAdvisorActivity
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** New settings assistant: finds problem episodes, explains why, and changes a setting only after a tap. */
class AimiAssistantActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var aimiStorageHelper: AimiStorageHelper

    private lateinit var root: LinearLayout
    private lateinit var history: AssistantHistory
    private var samples: List<AssistantSample> = emptyList()
    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = AssistantHistory(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            addView(root)
        })
        render(loading = true)
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            samples = withContext(Dispatchers.IO) {
                AssistantAnalyzer.readSamples(aimiStorageHelper.getAimiFile("AIMI_Decisions.jsonl"), now)
            }
            render(loading = false)
        }
    }

    // ---- values ----

    private fun readValue(s: AssistantSetting): Double = when (s.kind) {
        AssistantSetting.Kind.DOUBLE -> preferences.get(DoubleKey.entries.first { it.key == s.prefKey })
        AssistantSetting.Kind.INT -> preferences.get(IntKey.entries.first { it.key == s.prefKey }).toDouble()
    }

    private fun writeValue(s: AssistantSetting, value: Double) {
        val v = value.coerceIn(s.min, s.max)
        when (s.kind) {
            AssistantSetting.Kind.DOUBLE -> preferences.put(DoubleKey.entries.first { it.key == s.prefKey }, v)
            AssistantSetting.Kind.INT -> preferences.put(IntKey.entries.first { it.key == s.prefKey }, v.toInt())
        }
    }

    private fun fmt(s: AssistantSetting, v: Double): String =
        if (s.kind == AssistantSetting.Kind.INT) v.toInt().toString() else String.format(Locale.US, "%.2f", v)

    private fun applyChange(s: AssistantSetting, to: Double) {
        val now = System.currentTimeMillis()
        val from = readValue(s)
        val before = AssistantAnalyzer.stats(samples, now - 3 * 24L * 3600_000L, now)
        writeValue(s, to)
        history.add(ChangeRecord(s.prefKey, from, to, now, before))
    }

    // ---- screen ----

    private fun render(loading: Boolean) {
        root.removeAllViews()
        root.addView(title(getString(R.string.aimi_as_title)))
        root.addView(Button(this).apply {
            text = "AIMI Insight: резюме, дашборд, live"
            setOnClickListener { startActivity(Intent(this@AimiAssistantActivity, AimiInsightActivity::class.java)) }
        })
        root.addView(trainingCard())
        root.addView(postHypoCard())
        if (loading) {
            root.addView(text(getString(R.string.aimi_as_loading)))
            return
        }
        val now = System.currentTimeMillis()
        val lows = AssistantAnalyzer.lowEpisodes(samples)
        val late = AssistantAnalyzer.lateEpisodes(samples)
        val stats = AssistantAnalyzer.stats(samples, now - 3 * 24L * 3600_000L, now)

        root.addView(section(getString(R.string.aimi_as_overview)))
        root.addView(
            text(
                if (stats == null) getString(R.string.aimi_as_no_data)
                else getString(R.string.aimi_as_stats, stats.tirPercent, stats.lowPercent, stats.meanMmol, lows.size, late.size)
            )
        )
        lows.forEach {
            root.addView(
                text(getString(R.string.aimi_as_low_line, dateFormat.format(Date(it.timeMs)), it.minMmol, it.smbCount2h, it.smbMinutesBetween, it.iobStart))
            )
        }

        root.addView(section(getString(R.string.aimi_as_proposals)))
        // One change at a time: after a change we wait one day, so the result can be seen.
        val last = history.all().maxByOrNull { it.timeMs }
        val waitMs = 24L * 3600_000L
        if (last != null && now - last.timeMs < waitMs) {
            val hoursLeft = ((waitMs - (now - last.timeMs)) / 3600_000L).toInt() + 1
            val name = AssistantCatalog.byKey(last.prefKey)?.englishName ?: last.prefKey
            root.addView(text(getString(R.string.aimi_as_waiting, name, dateFormat.format(Date(last.timeMs)), hoursLeft)))
        } else {
            val proposals = AssistantProposals.build(lows, late) { readValue(it) }
            if (proposals.isEmpty()) {
                root.addView(text(getString(R.string.aimi_as_nothing)))
            } else {
                root.addView(proposalCard(proposals.first()))
                if (proposals.size > 1) root.addView(text(getString(R.string.aimi_as_more, proposals.drop(1).joinToString { it.setting.englishName })))
            }
        }

        root.addView(section(getString(R.string.aimi_as_settings)))
        AssistantCatalog.all.forEach { root.addView(settingRow(it)) }

        root.addView(Button(this).apply {
            text = getString(R.string.aimi_as_open_old)
            setOnClickListener { startActivity(Intent(this@AimiAssistantActivity, AimiProfileAdvisorActivity::class.java)) }
        })
    }

    private fun trainingCard(): LinearLayout {
        val card = box()
        card.addView(text(getString(R.string.aimi_adv_train_q_title), 16f, bold = true))
        card.addView(text(getString(R.string.aimi_adv_train_q_desc), 13f))
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.aimi_adv_train_q_hint)
            setTextColor(Color.WHITE)
            setText(String.format(Locale.US, "%.2f", preferences.get(DoubleKey.OApsAIMISmbTrainingMinQuality)))
        }
        card.addView(input)
        val relaxed = SwitchCompat(this).apply {
            text = getString(R.string.aimi_adv_train_f_title)
            setTextColor(Color.WHITE)
            isChecked = preferences.get(BooleanKey.OApsAIMISmbTrainingRelaxedFilter)
        }
        card.addView(text(getString(R.string.aimi_adv_train_f_desc), 13f))
        card.addView(relaxed)
        val status = text("", 13f)
        card.addView(Button(this).apply {
            text = getString(R.string.aimi_adv_train_q_apply)
            setOnClickListener {
                val value = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (value == null || value !in 0.0..1.0) {
                    status.text = getString(R.string.aimi_adv_train_q_invalid)
                } else {
                    preferences.put(DoubleKey.OApsAIMISmbTrainingMinQuality, value)
                    preferences.put(BooleanKey.OApsAIMISmbTrainingRelaxedFilter, relaxed.isChecked)
                    status.text = getString(R.string.aimi_adv_train_q_saved)
                }
            }
        })
        card.addView(status)
        return card
    }

    private fun postHypoCard(): LinearLayout {
        val card = box()
        card.addView(text(getString(R.string.aimi_as_ph_title), 16f, bold = true))
        card.addView(text(getString(R.string.aimi_as_ph_desc), 13f))
        val marginInput = numberInput(preferences.get(DoubleKey.OApsAIMIPostHypoReleaseMarginMgdl))
        val deltaInput = numberInput(preferences.get(DoubleKey.OApsAIMIPostHypoReleaseDeltaMgdl))
        card.addView(text(getString(R.string.aimi_as_ph_margin_label), 13f))
        card.addView(marginInput)
        card.addView(text(getString(R.string.aimi_as_ph_delta_label), 13f))
        card.addView(deltaInput)
        val status = text("", 13f)
        card.addView(Button(this).apply {
            text = getString(R.string.aimi_as_ph_apply)
            setOnClickListener {
                val margin = marginInput.text.toString().replace(',', '.').toDoubleOrNull()
                val delta = deltaInput.text.toString().replace(',', '.').toDoubleOrNull()
                val marginKey = DoubleKey.OApsAIMIPostHypoReleaseMarginMgdl
                val deltaKey = DoubleKey.OApsAIMIPostHypoReleaseDeltaMgdl
                if (margin == null || delta == null || margin !in marginKey.min..marginKey.max || delta !in deltaKey.min..deltaKey.max) {
                    status.text = getString(R.string.aimi_as_ph_invalid)
                } else {
                    preferences.put(marginKey, margin)
                    preferences.put(deltaKey, delta)
                    status.text = getString(R.string.aimi_as_ph_saved)
                }
            }
        })
        card.addView(status)
        return card
    }

    private fun numberInput(value: Double) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setTextColor(Color.WHITE)
        setText(String.format(Locale.US, "%.1f", value))
    }

    private fun proposalCard(p: AssistantProposal): LinearLayout {
        val card = box()
        val s = p.setting
        card.addView(text("${s.englishName}: ${fmt(s, p.current)} → ${fmt(s, p.proposed)}", 16f, bold = true))
        card.addView(text(getString(s.whatItDoes), 13f))
        card.addView(text(getString(p.reasonRes, *p.reasonArgs.toTypedArray()), 14f))
        card.addView(text(getString(s.related), 13f))
        if (s.tpoWritable) card.addView(text(getString(R.string.aimi_as_tpo_note), 13f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = getString(R.string.aimi_as_apply)
            setOnClickListener { applyChange(s, p.proposed); render(loading = false) }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.aimi_as_history_icon)
            setOnClickListener { showHistory(s) }
        })
        card.addView(row)
        return card
    }

    private fun settingRow(s: AssistantSetting): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }
        row.addView(text("${s.englishName}: ${fmt(s, readValue(s))}", 14f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = getString(R.string.aimi_as_history_icon)
            setOnClickListener { showHistory(s) }
        })
        return row
    }

    private fun showHistory(s: AssistantSetting) {
        val now = System.currentTimeMillis()
        val records = history.forKey(s.prefKey)
        val sb = StringBuilder()
        sb.append(getString(R.string.aimi_as_hist_default, fmt(s, s.default))).append("\n")
        sb.append(getString(R.string.aimi_as_hist_now, fmt(s, readValue(s)))).append("\n\n")
        if (records.isEmpty()) sb.append(getString(R.string.aimi_as_hist_none))
        records.forEach { r ->
            sb.append(getString(R.string.aimi_as_hist_line, dateFormat.format(Date(r.timeMs)), fmt(s, r.from), fmt(s, r.to))).append("\n")
            val after = AssistantAnalyzer.stats(samples, r.timeMs, now)
            if (r.before != null && after != null) {
                sb.append(
                    getString(
                        R.string.aimi_as_hist_result,
                        r.before.tirPercent, r.before.lowPercent, r.before.meanMmol,
                        after.tirPercent, after.lowPercent, after.meanMmol,
                    )
                ).append("\n")
            }
            sb.append("\n")
        }
        AlertDialog.Builder(this)
            .setTitle(s.englishName)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.aimi_as_restore_default) { _, _ ->
                applyChange(s, s.default)
                render(loading = false)
            }
            .setNegativeButton(R.string.aimi_as_close, null)
            .show()
    }

    // ---- small view helpers ----

    private fun title(t: String) = text(t, 22f, bold = true)
    private fun section(t: String) = text(t, 18f, bold = true).apply { setPadding(0, 40, 0, 8) }

    private fun text(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(if (bold) Color.WHITE else Color.parseColor("#CBD5E1"))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 8, 0, 8)
    }

    private fun box() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 24, 24, 24)
        setBackgroundColor(Color.parseColor("#1E293B"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 24
        }
    }
}
