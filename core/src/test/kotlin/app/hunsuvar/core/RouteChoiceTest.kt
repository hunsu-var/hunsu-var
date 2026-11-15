package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChoiceTest {
    private fun trace(vararg events: ScenarioEvent): List<Snapshot> =
        SignalRecorder().also { Scenario("test", events.toList()).replayInto(it) }.snapshots()

    /** Navi says 20 min left at 10 s; the driver leaves the route; at 30 s the new route says [after] s left. */
    private fun detour(after: Int) = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 50.0, turn = Turn.OFF, reroutes = 0, remainSec = 1_210),
        ScenarioEvent(10_000, remainSec = 1_200),
        ScenarioEvent(30_000, reroutes = 1, remainSec = after),
        ScenarioEvent(60_000, gear = Gear.P),
    )

    private fun route(t: List<Snapshot>) = varCandidates(t).single { it.kind == CandidateKind.ROUTE_CHANGE }

    @Test fun `a detour that costs minutes is a hit`() {
        val t = detour(after = 1_300) // arrival moves from 10+1200 to 30+1300 s: +120 s
        val v = judge(Claim.ROUTE_LONGER, route(t), t)
        assertEquals(Outcome.HIT, v.outcome)
        assertTrue(v.evidence, "2분" in v.evidence)
    }

    @Test fun `a detour that is no slower is rejected`() {
        val t = detour(after = 1_170) // 10+1200 → 30+1170: -10 s
        assertEquals(Outcome.REJECTED, judge(Claim.ROUTE_LONGER, route(t), t).outcome)
    }

    @Test fun `no navi remaining time - no route scene`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 50.0, turn = Turn.OFF, reroutes = 0),
            ScenarioEvent(30_000, reroutes = 1),
            ScenarioEvent(60_000, gear = Gear.P),
        )
        assertTrue(varCandidates(t).none { it.kind == CandidateKind.ROUTE_CHANGE })
    }

    @Test fun `a missed exit is the lane-change scene, which also says the time lost`() {
        val t = SignalRecorder().also { demoScenario().replayInto(it) }.snapshots()
        val wait = varCandidates(t).single { it.kind == CandidateKind.LANE_CHANGE_ATTEMPT }
        val v = judge(Claim.MISSED_GAP, wait, t)
        assertTrue(v.evidence, "늦어졌어요" in v.evidence)
        // the exit reroute (24 s) is not also a route scene; the later detour is
        assertEquals(1, varCandidates(t).count { it.kind == CandidateKind.ROUTE_CHANGE })
    }

    @Test fun `rule slips become the season's rule note, most frequent first`() {
        val now = 10 * DAY_MS
        val r = Records(habits = listOf(
            Habit(now - DAY_MS, CandidateKind.RAIN_NO_LIGHTS, slip = true),
            Habit(now - 2 * DAY_MS, CandidateKind.RAIN_NO_LIGHTS, slip = true),
            Habit(now - 3 * DAY_MS, CandidateKind.TURN, leadMs = 400, slip = true),
            Habit(now - 4 * DAY_MS, CandidateKind.TURN, leadMs = 2_500, slip = false),
        ))
        val notes = seasonReport(r, now).rules
        assertEquals(listOf(CandidateKind.RAIN_NO_LIGHTS, CandidateKind.TURN), notes.map { it.kind })
        assertEquals(2, notes.first().count)
    }

    @Test fun `the same arrival time says so, not 0 s earlier`() {
        val t = detour(after = 1_180) // 10+1200 → 30+1180: same arrival
        val v = judge(Claim.ROUTE_LONGER, route(t), t)
        assertEquals(Outcome.REJECTED, v.outcome)
        assertTrue(v.headline, v.headline.contains("도착 시간이 같았습니다"))
        assertTrue(v.evidence, !v.evidence.contains("0⁠초"))
    }
}
