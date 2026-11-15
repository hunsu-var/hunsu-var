package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneChangeTest {

    private fun trace(vararg events: ScenarioEvent): List<Snapshot> =
        SignalRecorder().also { Scenario("test", events.toList()).replayInto(it) }.snapshots()

    private fun attempts(t: List<Snapshot>) = detectCandidates(t).filter { it.kind == CandidateKind.LANE_CHANGE_ATTEMPT }

    /** 60 km/h, signal LEFT from 5 s for [signalMs]; LCA telltale [lca] while the signal is on; optional 10° steer at +1 s. */
    private fun attempt(signalMs: Long, lca: Boolean?, steer: Double = 0.0) = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0),
        ScenarioEvent(5_000, turn = Turn.LEFT, blindSpotLeft = lca),
        ScenarioEvent(6_000, steeringDeg = steer),
        ScenarioEvent(7_000, steeringDeg = 0.0),
        ScenarioEvent(5_000 + signalMs, turn = Turn.OFF, blindSpotLeft = lca?.let { false }),
        ScenarioEvent(15_000, gear = Gear.P),
    )

    @Test fun `4 s signal without steering at 60 kmh is one attempt`() {
        val c = attempts(attempt(4_000, false)).single()
        assertEquals(5_000L, c.startMs)
        assertEquals(9_000L, c.endMs)
    }

    @Test fun `attempt without any blind-spot data is hidden`() {
        assertTrue(attempts(attempt(4_000, null)).isEmpty()) // real cars: could only be 판독 불가, and the label accuses
    }

    @Test fun `2 s signal is not an attempt`() {
        assertTrue(attempts(attempt(2_000, false)).isEmpty())
    }

    @Test fun `signal before a real turn is not an attempt`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, blindSpotRight = false),
            ScenarioEvent(2_000, turn = Turn.RIGHT),
            ScenarioEvent(8_000, steeringDeg = 120.0),
            ScenarioEvent(11_000, steeringDeg = 0.0),
            ScenarioEvent(11_500, turn = Turn.OFF),
        )
        assertTrue(attempts(t).isEmpty())
    }

    @Test fun `gap claim with the next lane busy the whole time is rejected`() {
        val t = attempt(4_000, true)
        val v = judge(Claim.MISSED_GAP, attempts(t).single(), t)
        assertEquals(Outcome.REJECTED, v.outcome)
        assertTrue(v.headline, v.headline.contains("옆 차로가 계속 막혀 있었습니다"))
        assertTrue(v.evidence, v.evidence.contains("가장 길게 빈 게 0.0⁠초"))
    }

    @Test fun `gap claim with only short gaps is rejected and says the longest`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0),
            ScenarioEvent(5_000, turn = Turn.LEFT, blindSpotLeft = true),
            ScenarioEvent(7_000, blindSpotLeft = false),
            ScenarioEvent(8_500, blindSpotLeft = true),
            ScenarioEvent(12_000, turn = Turn.OFF, blindSpotLeft = false),
            ScenarioEvent(20_000, gear = Gear.P),
        )
        val v = judge(Claim.MISSED_GAP, attempts(t).single(), t)
        assertEquals(Outcome.REJECTED, v.outcome)
        assertTrue(v.evidence, v.evidence.contains("7.0⁠초 동안") && v.evidence.contains("가장 길게 빈 게 1.5⁠초"))
    }

    @Test fun `gap claim with a 3 s quiet lane is a hit and says when`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0),
            ScenarioEvent(5_000, turn = Turn.LEFT, blindSpotLeft = true),
            ScenarioEvent(6_500, blindSpotLeft = false),
            ScenarioEvent(10_000, turn = Turn.OFF),
            ScenarioEvent(20_000, gear = Gear.P),
        )
        val v = judge(Claim.MISSED_GAP, attempts(t).single(), t)
        assertEquals(Outcome.HIT, v.outcome)
        assertEquals(Confidence.MEDIUM, v.confidence)
        assertTrue(v.evidence, v.evidence.contains("1.5⁠초 뒤부터 3.5⁠초 동안 옆 차로 뒤쪽이 비어 있었어요"))
    }

    @Test fun `gap claim when the warning never came on is undecidable`() {
        val t = attempt(4_000, false) // quiet all along: the feature may be off
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.MISSED_GAP, attempts(t).single(), t).outcome)
    }

    @Test fun `gap claim in slow traffic is undecidable - the radar is off below 20 kmh`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 15.0),
            ScenarioEvent(5_000, turn = Turn.LEFT, blindSpotLeft = true),
            ScenarioEvent(6_000, blindSpotLeft = false),
            ScenarioEvent(11_000, turn = Turn.OFF),
            ScenarioEvent(20_000, gear = Gear.P),
        )
        val v = judge(Claim.MISSED_GAP, attempts(t).single(), t)
        assertEquals(Outcome.UNDECIDABLE, v.outcome)
        assertTrue(v.evidence, v.evidence.contains("시속 20 km 아래"))
    }

    @Test fun `gap claim with LCA unknown is undecidable`() {
        val t = attempt(4_000, null) // hidden from the list, but judge() must still be safe
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.MISSED_GAP, Candidate(CandidateKind.LANE_CHANGE_ATTEMPT, 5_000, 9_000), t).outcome)
    }

    @Test fun `cut-in claim with LCA on during a 10 deg steer is a hit`() {
        val t = attempt(4_000, true, steer = 10.0)
        val v = judge(Claim.CUT_IN_TIGHT, attempts(t).single(), t)
        assertEquals(Outcome.HIT, v.outcome)
        assertEquals(Confidence.MEDIUM, v.confidence)
    }

    @Test fun `cut-in claim without steering or LCA is undecidable`() {
        val noSteer = attempt(4_000, true)
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.CUT_IN_TIGHT, attempts(noSteer).single(), noSteer).outcome)
        val noLca = attempt(4_000, null, steer = 10.0)
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.CUT_IN_TIGHT, Candidate(CandidateKind.LANE_CHANGE_ATTEMPT, 5_000, 9_000), noLca).outcome)
    }

    @Test fun `attempt offers both gap claims`() {
        assertEquals(listOf(Claim.MISSED_GAP, Claim.CUT_IN_TIGHT), claimsFor(CandidateKind.LANE_CHANGE_ATTEMPT))
    }

    // --- 내비: "아까 들어갔어야지!" vs "더 늦게 들어가도 됐어" ---

    /** 60 km/h, signal RIGHT 5–12 s; navi says the next exit/turn is [exitM] ahead at 5 s; then [after] events. */
    private fun waitNearExit(exitM: Double, lca: Boolean?, vararg after: ScenarioEvent) = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, nextManeuverM = exitM + 83.0, maneuverSide = Turn.RIGHT, reroutes = 0),
        ScenarioEvent(5_000, turn = Turn.RIGHT, nextManeuverM = exitM, blindSpotRight = lca),
        ScenarioEvent(8_000, blindSpotRight = lca?.let { false }),
        ScenarioEvent(12_000, turn = Turn.OFF),
        *after,
        ScenarioEvent(60_000, gear = Gear.P),
    )

    @Test fun `attempt near a navi exit is shown even without blind-spot data`() {
        assertEquals(1, attempts(waitNearExit(150.0, null)).size)
    }

    @Test fun `attempt with the next navi maneuver far away and no LCA stays hidden`() {
        assertTrue(attempts(waitNearExit(3_000.0, null)).isEmpty())
    }

    @Test fun `missed exit with a gap - hit, and the miss and time lost come along`() {
        val t = waitNearExit(150.0, true, ScenarioEvent(20_000, reroutes = 1, nextManeuverM = 900.0))
        val v = judge(Claim.MISSED_GAP, attempts(t).single(), t)
        assertEquals(Outcome.HIT, v.outcome)
        assertTrue(v.headline, v.headline.contains("들어갈 틈이 있었습니다"))
        assertTrue(v.evidence, v.evidence.contains("3.0⁠초 뒤부터 4.0⁠초 동안"))
        assertTrue(v.evidence, v.evidence.contains("내비가 경로를 다시 찾았어요"))
    }

    @Test fun `exit made late without reroute is rejected`() {
        val t = waitNearExit(150.0, null, ScenarioEvent(15_000, nextManeuverM = 2_400.0)) // passed it, next target
        val v = judge(Claim.MISSED_GAP, attempts(t).single(), t)
        assertEquals(Outcome.REJECTED, v.outcome)
        assertTrue(v.headline, v.headline.contains("잘 빠졌습니다"))
    }

    @Test fun `without a navi outcome the gap still decides`() {
        val t = waitNearExit(150.0, true) // trip ends with no reroute and no passed maneuver
        assertEquals(Outcome.HIT, judge(Claim.MISSED_GAP, attempts(t).single(), t).outcome)
        val noLca = waitNearExit(150.0, null)
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.MISSED_GAP, attempts(noLca).single(), noLca).outcome)
    }

    @Test fun `no blind-spot data and a missed exit - undecidable, but the miss is still told`() {
        val t = waitNearExit(150.0, null, ScenarioEvent(20_000, reroutes = 1, nextManeuverM = 900.0))
        val v = judge(Claim.MISSED_GAP, attempts(t).single(), t)
        assertEquals(Outcome.UNDECIDABLE, v.outcome)
        assertTrue(v.evidence, v.evidence.contains("후측방 경고 기록을 받을 수 없어서") && v.evidence.contains("경로를 다시 찾았어요"))
    }

    // --- 끼어들기 수정: 합류 동작은 틈이 아님, 반대쪽 경고 무시, 표시값은 내림, 출구 방향 ---

    @Test fun `signal then an immediate normal merge is not a hit`() {
        val t = trace( // the car beside clears 0.5 s after the signal; 1.5 s later the driver steers over and finishes at 9.5 s
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0),
            ScenarioEvent(5_000, turn = Turn.LEFT, blindSpotLeft = true),
            ScenarioEvent(5_500, blindSpotLeft = false),
            ScenarioEvent(6_500, steeringDeg = -3.0),
            ScenarioEvent(9_500, turn = Turn.OFF, steeringDeg = 0.0),
            ScenarioEvent(20_000, gear = Gear.P),
        )
        val v = judge(Claim.MISSED_GAP, attempts(t).single(), t)
        assertEquals(Outcome.REJECTED, v.outcome)
        assertTrue(v.headline, v.headline.contains("틈이 나자 바로 들어갔습니다"))
        assertTrue(v.evidence, v.evidence.contains("1.5⁠초 만에"))
    }

    @Test fun `steering through a curve early in the wait is not a merge`() {
        val t = trace( // wheel 2° → 6° in a curve at 6 s; the left lane is clear 7–13 s; the driver never moves over
            ScenarioEvent(0, gear = Gear.D, speedKmh = 70.0, steeringDeg = 2.0),
            ScenarioEvent(5_000, turn = Turn.LEFT, blindSpotLeft = true),
            ScenarioEvent(6_000, steeringDeg = 6.0),
            ScenarioEvent(7_000, blindSpotLeft = false),
            ScenarioEvent(13_000, turn = Turn.OFF),
            ScenarioEvent(20_000, gear = Gear.P),
        )
        assertEquals(Outcome.HIT, judge(Claim.MISSED_GAP, attempts(t).single(), t).outcome)
    }

    @Test fun `forcing in while the warning is still on is not "went in at the first gap"`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0),
            ScenarioEvent(5_000, turn = Turn.LEFT, blindSpotLeft = true),
            ScenarioEvent(6_500, steeringDeg = -4.0),
            ScenarioEvent(9_000, turn = Turn.OFF, steeringDeg = 0.0),
            ScenarioEvent(9_500, blindSpotLeft = false),
            ScenarioEvent(20_000, gear = Gear.P),
        )
        val v = judge(Claim.MISSED_GAP, attempts(t).single(), t)
        assertEquals(Outcome.REJECTED, v.outcome)
        assertTrue(v.headline, v.headline.contains("계속 막혀 있었습니다"))
    }

    @Test fun `a car in the other side's blind spot does not block this lane change`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, blindSpotRight = true), // right lane busy all along
            ScenarioEvent(5_000, turn = Turn.LEFT, blindSpotLeft = true),
            ScenarioEvent(6_500, blindSpotLeft = false),
            ScenarioEvent(10_000, turn = Turn.OFF),
            ScenarioEvent(20_000, gear = Gear.P),
        )
        assertEquals(Outcome.HIT, judge(Claim.MISSED_GAP, attempts(t).single(), t).outcome)
    }

    @Test fun `a 2_96 s gap is shown as 2_9 s, never as the 3 s bar it missed`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0),
            ScenarioEvent(5_000, turn = Turn.LEFT, blindSpotLeft = true),
            ScenarioEvent(6_000, blindSpotLeft = false),
            ScenarioEvent(8_960, blindSpotLeft = true),
            ScenarioEvent(12_000, turn = Turn.OFF, blindSpotLeft = false),
            ScenarioEvent(20_000, gear = Gear.P),
        )
        val v = judge(Claim.MISSED_GAP, attempts(t).single(), t)
        assertEquals(Outcome.REJECTED, v.outcome)
        assertTrue(v.evidence, v.evidence.contains("가장 길게 빈 게 2.9⁠초"))
    }

    /** Signal RIGHT 5–12 s, no LCA; the navi's next guidance is [side] at 150 m. */
    private fun waitBefore(side: Turn) = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 80.0, nextManeuverM = 300.0, maneuverSide = side, reroutes = 0),
        ScenarioEvent(5_000, turn = Turn.RIGHT, nextManeuverM = 150.0),
        ScenarioEvent(12_000, turn = Turn.OFF),
        ScenarioEvent(60_000, gear = Gear.P),
    )

    @Test fun `a tunnel, toll gate or go-straight ahead is no exit to wait for`() {
        assertTrue(attempts(waitBefore(Turn.OFF)).isEmpty())
    }

    @Test fun `an exit on the other side is not this signal's exit`() {
        assertTrue(attempts(waitBefore(Turn.LEFT)).isEmpty())
        assertEquals(1, attempts(waitBefore(Turn.RIGHT)).size)
    }
}
