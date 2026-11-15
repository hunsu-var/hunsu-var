package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Story opening ("브레이크 살살!" → 1.9 m/s² → 기각) and B3 ("신호 대기엔 N!" → 기각). */
class BrakeStopTest {

    private fun trace(vararg events: ScenarioEvent): List<Snapshot> =
        SignalRecorder().also { Scenario("test", events.toList()).replayInto(it) }.snapshots()

    /** Brake on at [t0]; speed falls [step] km/h every [everyMs] from 60 to 0. Optional 10 Hz repeats (live listener). */
    private fun braking(t0: Long, step: Double, everyMs: Long = 1_000, hz10: Boolean = false): List<ScenarioEvent> {
        val out = mutableListOf(ScenarioEvent(t0, brake = true))
        var v = 60.0; var t = t0
        while (v > 0) {
            t += everyMs; v = maxOf(0.0, v - step)
            out += ScenarioEvent(t, speedKmh = v)
        }
        out += ScenarioEvent(t + 500, brake = false)
        if (!hz10) return out
        // The live speed callback repeats the current value at 10 Hz: add a sample every 100 ms.
        val end = out.last().t
        val repeats = (t0..end step 100).map { ScenarioEvent(it) }
        return (out + repeats).sortedBy { it.t }
    }

    private fun drive(vararg brakes: List<ScenarioEvent>) = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, turn = Turn.OFF),
        *brakes.flatMap { it }.toTypedArray(),
    ).let { it + it.last().copy(tMs = it.last().tMs + 1_000, gear = Gear.P) }

    @Test fun `gentle braking 7 kmh per second is a brake candidate and the claim is rejected at 1_9`() {
        val t = drive(braking(5_000, 7.0))
        val c = detectCandidates(t).single { it.kind != CandidateKind.PARKING }
        assertEquals(CandidateKind.BRAKE, c.kind)
        val v = judge(Claim.BRAKE_HARSH, c, t)
        assertEquals(Outcome.REJECTED, v.outcome)
        assertTrue(v.evidence, v.evidence.contains("1.9 m/s²"))
    }

    @Test fun `1 s steps sampled at 10 Hz still read 1_9, not a harsh brake`() {
        val t = drive(braking(5_000, 7.0, hz10 = true))
        val c = detectCandidates(t).single { it.kind != CandidateKind.PARKING }
        assertEquals(CandidateKind.BRAKE, c.kind)
        assertTrue(judge(Claim.BRAKE_HARSH, c, t).evidence.contains("1.9 m/s²"))
    }

    @Test fun `only the strongest gentle brake is a candidate, every harsh one is`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0),
            *braking(2_000, 6.0).toTypedArray(),            // 1.7 m/s²
            ScenarioEvent(20_000, speedKmh = 60.0),
            *braking(21_000, 7.0).toTypedArray(),           // 1.9 m/s² ← strongest gentle
            ScenarioEvent(40_000, speedKmh = 60.0),
            *braking(41_000, 20.0).toTypedArray(),          // 5.6 m/s² harsh
            ScenarioEvent(50_000, speedKmh = 60.0),
            *braking(51_000, 3.0).toTypedArray(),           // 0.8 m/s²: not noticeable
            ScenarioEvent(80_000, gear = Gear.P),
        )
        val brakes = detectCandidates(t).filter { it.kind == CandidateKind.BRAKE || it.kind == CandidateKind.HARSH_BRAKE }
        assertEquals(listOf(CandidateKind.BRAKE to 21_000L, CandidateKind.HARSH_BRAKE to 41_000L), brakes.map { it.kind to it.startMs })
        assertEquals(listOf(Claim.BRAKE_HARSH), claimsFor(CandidateKind.BRAKE))
    }

    /** Drives, stops in D for [stopMs] (brake held), drives on, parks. */
    private fun stopInD(stopMs: Long, gear: Gear = Gear.D) = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 40.0, turn = Turn.OFF),
        ScenarioEvent(5_000, speedKmh = 0.0, brake = true, gear = gear),
        ScenarioEvent(5_000 + stopMs, brake = false, gear = Gear.D, speedKmh = 30.0),
        ScenarioEvent(10_000 + stopMs, speedKmh = 0.0),
        ScenarioEvent(11_000 + stopMs, gear = Gear.P),
    )

    @Test fun `a 12 s stop in D is a candidate and 'N에 놔야지' is rejected, a little-known rule`() {
        val t = stopInD(12_000)
        val c = detectCandidates(t).single { it.kind == CandidateKind.STOP_IN_D }
        assertEquals(5_000L, c.startMs)
        assertEquals(listOf(Claim.NEUTRAL_AT_STOP), claimsFor(CandidateKind.STOP_IN_D))
        val v = judge(Claim.NEUTRAL_AT_STOP, c, t)
        assertEquals(Outcome.REJECTED, v.outcome)
        assertEquals(Confidence.HIGH, v.confidence)
        assertTrue(v.evidence, v.evidence.contains("12.0⁠초"))
        assertTrue(Claim.NEUTRAL_AT_STOP.littleKnown)
    }

    @Test fun `short stops and stops in N are not D-stop candidates, and only the longest D stop shows`() {
        assertTrue(detectCandidates(stopInD(8_000)).none { it.kind == CandidateKind.STOP_IN_D })
        assertTrue(detectCandidates(stopInD(12_000, gear = Gear.N)).none { it.kind == CandidateKind.STOP_IN_D })
        val two = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 40.0),
            ScenarioEvent(5_000, speedKmh = 0.0), ScenarioEvent(17_000, speedKmh = 40.0),   // 12 s
            ScenarioEvent(25_000, speedKmh = 0.0), ScenarioEvent(45_000, speedKmh = 40.0),  // 20 s ← longest
            ScenarioEvent(50_000, speedKmh = 0.0), ScenarioEvent(51_000, gear = Gear.P),
        )
        assertEquals(listOf(25_000L), detectCandidates(two).filter { it.kind == CandidateKind.STOP_IN_D }.map { it.startMs })
    }

    private fun turnAt60() = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, turn = Turn.OFF),
        ScenarioEvent(8_000, turn = Turn.RIGHT),
        ScenarioEvent(10_000, steeringDeg = 120.0, speedKmh = 20.0),
        ScenarioEvent(13_000, steeringDeg = 0.0, turn = Turn.OFF),
        ScenarioEvent(15_000, gear = Gear.P),
    )

    @Test fun `red-light and right-turn-stop claims are always undecidable, the stop claim says whether the car stopped`() {
        val t = turnAt60()
        val c = detectCandidates(t).single { it.kind == CandidateKind.TURN }
        assertTrue(claimsFor(CandidateKind.TURN).containsAll(listOf(Claim.RED_LIGHT, Claim.RIGHT_TURN_STOP)))
        assertEquals(Outcome.UNDECIDABLE, judge(Claim.RED_LIGHT, c, t).outcome)
        val noStop = judge(Claim.RIGHT_TURN_STOP, c, t)
        assertEquals(Outcome.UNDECIDABLE, noStop.outcome)
        assertTrue(noStop.evidence, noStop.evidence.contains("멈춘 기록이 없어요"))

        val stopped = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 30.0, turn = Turn.OFF),
            ScenarioEvent(4_000, speedKmh = 0.0), ScenarioEvent(6_000, speedKmh = 15.0, turn = Turn.RIGHT),
            ScenarioEvent(10_000, steeringDeg = 120.0), ScenarioEvent(13_000, steeringDeg = 0.0, turn = Turn.OFF),
            ScenarioEvent(15_000, gear = Gear.P),
        )
        val v = judge(Claim.RIGHT_TURN_STOP, detectCandidates(stopped).single { it.kind == CandidateKind.TURN }, stopped)
        assertEquals(Outcome.UNDECIDABLE, v.outcome)
        assertTrue(v.evidence, v.evidence.contains("완전히 멈춘 기록이 있어요"))
    }

    @Test fun `standing in P before setting off is not a stop before the turn`() {
        val t = trace( // live golden path: parked, D, 60 km/h, turn 6 s later
            ScenarioEvent(0, gear = Gear.P, speedKmh = 0.0, turn = Turn.OFF),
            ScenarioEvent(4_000, gear = Gear.D), ScenarioEvent(4_500, speedKmh = 60.0),
            ScenarioEvent(8_000, turn = Turn.RIGHT), ScenarioEvent(10_000, steeringDeg = 120.0),
            ScenarioEvent(13_000, steeringDeg = 0.0, turn = Turn.OFF), ScenarioEvent(15_000, gear = Gear.P),
        )
        val v = judge(Claim.RIGHT_TURN_STOP, detectCandidates(t).single { it.kind == CandidateKind.TURN }, t)
        assertTrue(v.evidence, v.evidence.contains("멈춘 기록이 없어요"))
    }

    @Test fun `little-known rules carry the 이 규칙 몰랐죠 marker`() {
        assertEquals(
            setOf(Claim.SIGNAL_LATE, Claim.LIGHTS_IN_RAIN, Claim.NEUTRAL_AT_STOP, Claim.RIGHT_TURN_STOP),
            Claim.values().filter { it.littleKnown }.toSet())
    }
}
