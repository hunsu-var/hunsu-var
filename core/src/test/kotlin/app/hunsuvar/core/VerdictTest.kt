package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {

    private fun trace(vararg events: ScenarioEvent): List<Snapshot> =
        SignalRecorder().also { Scenario("test", events.toList()).replayInto(it) }.snapshots()

    /** Constant-speed drive with a signal [leadMs] before a 120° turn at t=10 s. */
    private fun turnAt60(leadMs: Long?) = trace(
        *listOfNotNull(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, turn = Turn.OFF),
            leadMs?.let { ScenarioEvent(10_000 - it, turn = Turn.RIGHT) },
            ScenarioEvent(10_000, steeringDeg = 120.0),
            ScenarioEvent(13_000, steeringDeg = 0.0),
            ScenarioEvent(13_500, turn = Turn.OFF),
            ScenarioEvent(15_000, gear = Gear.P),
        ).toTypedArray()
    )

    private fun onlyTurn(t: List<Snapshot>) = detectCandidates(t).single { it.kind == CandidateKind.TURN }

    @Test fun `signal 2_1 s before turn at 60 kmh is rejected`() {
        val t = turnAt60(2_100)
        val v = judge(Claim.SIGNAL_LATE, onlyTurn(t), t)
        assertEquals(Outcome.REJECTED, v.outcome)
        assertTrue(v.headline, v.headline.contains("2.1\u2060초"))
        assertTrue(v.evidence, v.evidence.contains("1.8\u2060초")) // 30 m / 16.7 m/s
    }

    @Test fun `signal 0_5 s before turn at 60 kmh is a hit`() {
        val t = turnAt60(500)
        assertEquals(Outcome.HIT, judge(Claim.SIGNAL_LATE, onlyTurn(t), t).outcome)
    }

    @Test fun `no signal at all - missing and late are hits, early-off undecidable`() {
        val t = turnAt60(null)
        val c = onlyTurn(t)
        assertEquals(Outcome.HIT, judge(Claim.SIGNAL_MISSING, c, t).outcome)
        assertEquals(Outcome.HIT, judge(Claim.SIGNAL_LATE, c, t).outcome)
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.SIGNAL_EARLY_OFF, c, t).outcome)
    }

    @Test fun `no big steering - no turn candidate, and a turn claim is undecidable`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 80.0),
            ScenarioEvent(5_000, steeringDeg = 4.0, brake = true), // lane-change-sized steering
            ScenarioEvent(6_000, steeringDeg = 0.0, brake = false),
            ScenarioEvent(8_000, gear = Gear.P),
        )
        assertTrue(detectCandidates(t).none { it.kind == CandidateKind.TURN })
        val notATurn = Candidate(CandidateKind.HARSH_BRAKE, 5_000, 6_000)
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.SIGNAL_LATE, notATurn, t).outcome)
    }

    @Test fun `turn while nearly stopped is undecidable`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 0.0),
            ScenarioEvent(1_000, turn = Turn.LEFT),
            ScenarioEvent(1_500, steeringDeg = -200.0),
            ScenarioEvent(4_000, steeringDeg = 0.0, turn = Turn.OFF),
        )
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.SIGNAL_LATE, onlyTurn(t), t).outcome)
    }

    @Test fun `overspeed needs 10 kmh over for 3 s`() {
        fun over(ms: Long) = detectCandidates(trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 50.0, speedLimitKmh = 50),
            ScenarioEvent(1_000, speedKmh = 62.0),
            ScenarioEvent(1_000 + ms, speedKmh = 50.0),
            ScenarioEvent(10_000, gear = Gear.P),
        )).filter { it.kind == CandidateKind.OVERSPEED }
        assertTrue(over(2_500).isEmpty())
        assertEquals(1, over(4_000).size)
    }

    @Test fun `demo scenario yields the PT story`() {
        val t = SignalRecorder().also { demoScenario().replayInto(it) }.snapshots()
        val cs = detectCandidates(t)
        assertEquals(
            listOf(CandidateKind.TURN, CandidateKind.LANE_CHANGE_ATTEMPT, CandidateKind.TURN, CandidateKind.OVERSPEED,
                CandidateKind.HARSH_BRAKE, CandidateKind.RAIN_NO_LIGHTS, CandidateKind.ROUTE_CHANGE, CandidateKind.BRAKE, CandidateKind.STOP_IN_D, CandidateKind.PARKING),
            cs.map { it.kind })
        assertEquals(Outcome.REJECTED, judge(Claim.SIGNAL_LATE, cs[0], t).outcome) // 5 s → 기각
        val exit = judge(Claim.MISSED_GAP, cs[1], t)                                // 아까 들어갔어야지! → 3.5초 빈 틈
        assertEquals(Outcome.HIT, exit.outcome)
        assertTrue(exit.evidence, exit.evidence.contains("1.5\u2060초 뒤부터 3.5\u2060초 동안") && exit.evidence.contains("약 3분 늦어졌어요"))
        assertEquals(Outcome.HIT, judge(Claim.SIGNAL_LATE, cs[2], t).outcome)      // 0.5 s → 적중
        val speeding = judge(Claim.TOO_FAST, cs[3], t)                              // 스쿨존 45 km/h
        assertEquals(Outcome.HIT, speeding.outcome)
        assertTrue(speeding.headline, speeding.headline.contains("제한 30\u00A0km 구간에서 시속 45\u00A0km"))
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.BRAKE_HARSH, cs[4], t).outcome) // 정당성 모름
        assertEquals(Outcome.HIT, judge(Claim.LIGHTS_IN_RAIN, cs[5], t).outcome)
        val gentle = judge(Claim.BRAKE_HARSH, cs[7], t)                              // 오프닝: 브레이크 살살!
        assertEquals(Outcome.REJECTED, gentle.outcome)
        assertTrue(gentle.evidence, gentle.evidence.contains("1.9\u00A0m/s²"))
        assertEquals(Outcome.REJECTED, judge(Claim.NEUTRAL_AT_STOP, cs[8], t).outcome) // 신호 대기엔 N? 기각
    }

    @Test fun `turn-signal data missing - missing and late claims are undecidable, never hits`() {
        val t = trace( // no turn events at all: the car never reported the signal
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0),
            ScenarioEvent(10_000, steeringDeg = 120.0),
            ScenarioEvent(13_000, steeringDeg = 0.0),
            ScenarioEvent(15_000, gear = Gear.P),
        )
        val c = onlyTurn(t)
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.SIGNAL_MISSING, c, t).outcome)
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.SIGNAL_LATE, c, t).outcome)
    }

    @Test fun `demo scenario events are in time order`() {
        val ts = demoScenario().events.map { it.t }
        assertEquals(ts.sorted(), ts)
    }

    @Test fun `demo lane-change attempt cut-in claim is undecidable`() {
        val t = SignalRecorder().also { demoScenario().replayInto(it) }.snapshots()
        val attempt = detectCandidates(t).single { it.kind == CandidateKind.LANE_CHANGE_ATTEMPT }
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.CUT_IN_TIGHT, attempt, t).outcome) // 조향 없음 → 판독 불가
    }

    @Test fun `ring buffer drops oldest`() {
        val r = SignalRecorder(capacity = 3)
        (1..5).forEach { i -> r.update(i.toLong()) { it.copy(speedKmh = i.toDouble()) } }
        assertEquals(listOf(3L, 4L, 5L), r.snapshots().map { it.tMs })
    }

    @Test fun `season rates ignore undecidable`() {
        val s = Season().plus(Outcome.REJECTED).plus(Outcome.REJECTED).plus(Outcome.HIT).plus(Outcome.UNDECIDABLE)
        assertEquals(2.0 / 3, s.innocenceRate, 1e-9)
        assertEquals(1.0 / 3, s.hitRate, 1e-9)
    }
}
