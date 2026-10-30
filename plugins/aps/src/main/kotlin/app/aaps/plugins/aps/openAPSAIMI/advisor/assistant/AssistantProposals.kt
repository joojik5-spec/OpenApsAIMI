package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import app.aaps.plugins.aps.R
import kotlin.math.roundToInt

/** Simple rules. Each rule uses numbers found in the user's own log, and the reason shows them. */
object AssistantProposals {

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0

    fun build(
        lows: List<LowEpisode>,
        late: List<LateEpisode>,
        current: (AssistantSetting) -> Double,
    ): List<AssistantProposal> {
        val out = ArrayList<AssistantProposal>()
        val serial = lows.filter { it.smbCount2h >= 8 && it.smbMinutesBetween in 0.1..4.0 }

        if (serial.isNotEmpty()) {
            val s = AssistantCatalog.highBgInterval
            val cur = current(s)
            val next = (cur + 2).coerceAtMost(minOf(6.0, s.max))
            if (next > cur) {
                out.add(
                    AssistantProposal(
                        s, cur, next, R.string.aimi_as_reason_series,
                        listOf(serial.size, serial.map { it.smbCount2h }.average().roundToInt(), serial.map { it.smbMinutesBetween }.average().let(::round1), serial.minOf { it.minMmol }),
                    )
                )
            }
        }
        if (lows.size >= 2) {
            val s = AssistantCatalog.priorityExtra
            val cur = current(s)
            val next = (cur - 1.0).coerceAtLeast(s.min)
            if (next < cur) {
                out.add(
                    AssistantProposal(
                        s, cur, round1(next), R.string.aimi_as_reason_priority,
                        listOf(lows.size, lows.minOf { it.minMmol }),
                    )
                )
            }
            val r = AssistantCatalog.redCarpet
            val rCur = current(r)
            val rNext = (rCur - 0.15).coerceAtLeast(r.min)
            if (rNext < rCur) {
                out.add(
                    AssistantProposal(
                        r, rCur, round2(rNext), R.string.aimi_as_reason_red_carpet,
                        listOf(lows.size),
                    )
                )
            }
        }
        if (late.size >= 2 && lows.isEmpty()) {
            val s = AssistantCatalog.maxSmb
            val cur = current(s)
            val next = round1((cur * 1.1).coerceAtLeast(cur + 0.1)).coerceAtMost(s.max)
            if (next > cur) {
                out.add(
                    AssistantProposal(
                        s, cur, next, R.string.aimi_as_reason_late,
                        listOf(late.size, late.map { it.delayMinutes }.average().roundToInt()),
                    )
                )
            }
        }
        return out
    }
}
