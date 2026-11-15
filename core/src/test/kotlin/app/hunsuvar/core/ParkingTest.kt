package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingTest {
    private fun trace(vararg events: ScenarioEvent): List<Snapshot> =
        SignalRecorder().also { Scenario("test", events.toList()).replayInto(it) }.snapshots()

    /** Drive in at 30, creep, stop and turn the wheel 300° standing, reverse in, straighten, park. */
    private fun park(vararg extra: ScenarioEvent) = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 30.0, steeringDeg = 0.0, turn = Turn.OFF),
        ScenarioEvent(5_000, speedKmh = 8.0),
        ScenarioEvent(10_000, speedKmh = 0.0, brake = true),
        ScenarioEvent(11_000, steeringDeg = 300.0),
        ScenarioEvent(13_000, gear = Gear.R, speedKmh = 3.0, brake = false),
        ScenarioEvent(17_000, steeringDeg = 0.0),
        *extra,
        ScenarioEvent(22_000, speedKmh = 0.0, brake = true),
        ScenarioEvent(23_000, gear = Gear.P),
    )

    private fun parking(t: List<Snapshot>) = detectCandidates(t).single { it.kind == CandidateKind.PARKING }

    @Test fun `a parking episode runs from the last fast stretch to P`() {
        val c = parking(park())
        assertEquals(5_000L, c.startMs)
        assertEquals(23_000L, c.endMs)
    }

    @Test fun `moving the wheel again after straightening is a hit for hold it straight`() {
        val t = park(ScenarioEvent(19_000, steeringDeg = 40.0), ScenarioEvent(20_000, steeringDeg = 0.0))
        val v = judge(Claim.HOLD_STRAIGHT, parking(t), t)
        assertEquals(Outcome.HIT, v.outcome)
        assertTrue(v.evidence, "40°" in v.evidence)
    }

    @Test fun `a still wheel after straightening rejects hold it straight`() {
        val t = park()
        assertEquals(Outcome.REJECTED, judge(Claim.HOLD_STRAIGHT, parking(t), t).outcome)
    }

    @Test fun `no back-and-forth rejects it does not fit`() {
        val t = park()
        assertEquals(Outcome.REJECTED, judge(Claim.ONE_GO, parking(t), t).outcome)
    }

    @Test fun `corrections are counted but whether it fit is undecidable`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 30.0, steeringDeg = 0.0, turn = Turn.OFF),
            ScenarioEvent(5_000, speedKmh = 6.0),
            ScenarioEvent(9_000, speedKmh = 0.0),
            ScenarioEvent(10_000, gear = Gear.R, speedKmh = 4.0),
            ScenarioEvent(14_000, speedKmh = 0.0),
            ScenarioEvent(15_000, gear = Gear.D, speedKmh = 3.0),
            ScenarioEvent(18_000, speedKmh = 0.0),
            ScenarioEvent(19_000, gear = Gear.R, speedKmh = 3.0),
            ScenarioEvent(24_000, speedKmh = 0.0),
            ScenarioEvent(25_000, gear = Gear.P),
        )
        val v = judge(Claim.ONE_GO, parking(t), t)
        assertEquals(Outcome.UNDECIDABLE, v.outcome)
        assertTrue(v.evidence, "2번" in v.evidence)
    }

    @Test fun `demo parking - still wheel rejected, two corrections undecidable`() {
        val t = SignalRecorder().also { demoScenario().replayInto(it) }.snapshots()
        val c = parking(t)
        assertEquals(Outcome.REJECTED, judge(Claim.HOLD_STRAIGHT, c, t).outcome)
        val one = judge(Claim.ONE_GO, c, t)
        assertEquals(Outcome.UNDECIDABLE, one.outcome)
        assertTrue(one.evidence, "2번" in one.evidence)
    }

    @Test fun `demo parking path - two corrections are numbered`() {
        val t = SignalRecorder().also { demoScenario().replayInto(it) }.snapshots()
        val p = parkingPathFor(parking(t), t)
        assertEquals(2, p.turns.size)
        assertTrue(p.points.any { it.gear == Gear.R })
    }

    @Test fun `only real arguments reach the passenger's list`() {
        val kinds = varCandidates(SignalRecorder().also { demoScenario().replayInto(it) }.snapshots()).map { it.kind }.toSet()
        assertEquals(setOf(CandidateKind.LANE_CHANGE_ATTEMPT, CandidateKind.PARKING, CandidateKind.ROUTE_CHANGE), kinds)
    }

    @Test fun `a tap during parking marks the parking`() {
        val t = park()
        assertEquals(setOf(parking(t)), markCandidates(detectCandidates(t), listOf(21_000)).candidates)
    }

    @Test fun `a lane-change argument tapped minutes later still finds its scene`() {
        val wait = Candidate(CandidateKind.LANE_CHANGE_ATTEMPT, 10_000, 14_000)
        assertEquals(setOf(wait), markCandidates(listOf(wait), listOf(14_000 + 4 * 60_000)).candidates)
    }

    @Test fun `the drawn path turns right for right (positive) steering`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 30.0, steeringDeg = 0.0, turn = Turn.OFF),
            ScenarioEvent(5_000, speedKmh = 5.0, steeringDeg = 300.0),
            ScenarioEvent(15_000, speedKmh = 0.0),
            ScenarioEvent(16_000, gear = Gear.P),
        )
        val end = parkingPathFor(parking(t), t).points.last()
        assertTrue("heading ${end.headingRad}", end.headingRad < 0) // clockwise seen from above
        assertTrue("y ${end.y}", end.y < 0)
    }
}
