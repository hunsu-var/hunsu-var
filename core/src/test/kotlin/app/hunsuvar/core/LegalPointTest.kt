package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VAR marker (replayFor) and the verdict (judge) must share ONE legal point. */
class LegalPointTest {

    private fun trace(vararg events: ScenarioEvent): List<Snapshot> =
        SignalRecorder().also { Scenario("test", events.toList()).replayInto(it) }.snapshots()

    private fun onlyTurn(t: List<Snapshot>) = detectCandidates(t).single { it.kind == CandidateKind.TURN }

    private fun late(t: List<Snapshot>): Pair<Verdict, ReplaySeries> {
        val c = onlyTurn(t)
        return judge(Claim.SIGNAL_LATE, c, t) to replayFor(c, t)
    }

    /** Turn at t=10 s, signal on at [signalAt]; speed steps 60→50→40→30 km/h over the 4 s before the turn, or constant 60. */
    private fun turn(signalAt: Long, decel: Boolean) = trace(
        *listOf(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, turn = Turn.OFF),
            ScenarioEvent(signalAt, turn = Turn.RIGHT),
        ).plus(if (decel) listOf(
            ScenarioEvent(7_000, speedKmh = 50.0), ScenarioEvent(8_000, speedKmh = 40.0), ScenarioEvent(9_000, speedKmh = 30.0),
        ) else emptyList()).plus(listOf(
            ScenarioEvent(10_000, steeringDeg = 120.0),
            ScenarioEvent(13_000, steeringDeg = 0.0),
            ScenarioEvent(13_500, turn = Turn.OFF),
            ScenarioEvent(15_000, gear = Gear.P),
        )).sortedBy { it.t }.toTypedArray()
    )

    @Test fun `constant 60 kmh - legal lead 1_8 s, verdicts unchanged, same number in the text`() {
        val (rejected, r) = late(turn(7_900, decel = false)) // 2.1 s early
        assertTrue(r.legalLeadMs.toString(), r.legalLeadMs!! in 1790..1810)
        assertEquals(Outcome.REJECTED, rejected.outcome)
        assertTrue(rejected.evidence, rejected.evidence.contains("30\u00A0m 전은 핸들보다 약 1.8\u2060초"))
        assertEquals(Outcome.HIT, late(turn(9_500, decel = false)).first.outcome)
    }

    @Test fun `decelerating 60 to 30 kmh - marker lead equals the verdict lead, outcome follows the marker`() {
        // legal point = 7 240 ms (30 km/h 1 s + 40 km/h 1 s = 19.4 m, last 10.6 m at 50 km/h = 0.76 s) → lead 2 760 ms
        val (before, r) = late(turn(7_200, decel = true)) // 40 ms left of the marker
        assertTrue(r.legalLeadMs.toString(), r.legalLeadMs!! in 2750..2770)
        assertEquals(Outcome.REJECTED, before.outcome)
        assertTrue(before.evidence, before.evidence.contains("30\u00A0m 전은 핸들보다 약 2.8\u2060초"))
        assertTrue(before.evidence, !before.evidence.contains("시속"))

        val (after, r2) = late(turn(7_300, decel = true)) // 60 ms right of the marker
        assertEquals(r.legalLeadMs, r2.legalLeadMs)
        assertEquals(Outcome.HIT, after.outcome)
        assertTrue(after.tip, after.tip.contains("약 2.8\u2060초"))
    }

    @Test fun `trace too short to reach 30 m - no legal point, late claim undecidable`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, turn = Turn.RIGHT),
            ScenarioEvent(1_000, steeringDeg = 120.0),
            ScenarioEvent(4_000, steeringDeg = 0.0, turn = Turn.OFF),
            ScenarioEvent(5_000, gear = Gear.P),
        ) // only 16.7 m of history before the turn
        val (v, r) = late(t)
        assertNull(r.legalLeadMs)
        assertEquals(Outcome.UNDECIDABLE, v.outcome)
    }
}
