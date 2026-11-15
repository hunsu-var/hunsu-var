package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class QuickTest {
    private fun trace(vararg events: ScenarioEvent): List<Snapshot> =
        SignalRecorder().also { Scenario("test", events.toList()).replayInto(it) }.snapshots()

    /** 60 km/h, right signal from 8 s, wheel 120° at 10.1 s (2.1 s lead ≈ 35 m > 30 m), then a red light at 20 s. */
    private val turnThenStop = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, turn = Turn.OFF),
        ScenarioEvent(8_000, turn = Turn.RIGHT),
        ScenarioEvent(10_100, steeringDeg = 120.0),
        ScenarioEvent(13_000, steeringDeg = 0.0, turn = Turn.OFF),
        ScenarioEvent(20_000, speedKmh = 0.0),
        ScenarioEvent(22_000, speedKmh = 0.0),
    )

    @Test fun `a turn is not an argument any more - no line at the stop`() {
        assertNull(quickVerdict(turnThenStop, listOf(14_000)))
    }

    @Test fun `no tap, no line`() {
        assertNull(quickVerdict(turnThenStop, emptyList()))
    }

    @Test fun `an exit wait waits for the navi outcome`() {
        val waiting = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, nextManeuverM = 233.0, maneuverSide = Turn.RIGHT, reroutes = 0),
            ScenarioEvent(5_000, turn = Turn.RIGHT, nextManeuverM = 150.0, blindSpotRight = true),
            ScenarioEvent(8_000, blindSpotRight = false),
            ScenarioEvent(12_000, turn = Turn.OFF),
            ScenarioEvent(14_000, speedKmh = 0.0),
        )
        assertNull(quickVerdict(waiting, listOf(9_000))) // no reroute yet: don't say 기각 now and 적중 at home
        val missed = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, nextManeuverM = 233.0, maneuverSide = Turn.RIGHT, reroutes = 0),
            ScenarioEvent(5_000, turn = Turn.RIGHT, nextManeuverM = 150.0, blindSpotRight = true),
            ScenarioEvent(8_000, blindSpotRight = false),
            ScenarioEvent(12_000, turn = Turn.OFF),
            ScenarioEvent(16_000, reroutes = 1, nextManeuverM = 900.0),
            ScenarioEvent(18_000, speedKmh = 0.0),
        )
        val q = quickVerdict(missed, listOf(9_000))!!
        assertEquals(Outcome.HIT, q.outcome)
        assertEquals("“아까 들어갔어야지!” → 훈수 적중 · 자세한 건 주차하면", q.line)
    }

    /** 빠질 길 missed with a gap; ends at [endKmh] after 18 s. */
    private fun missedThen(endKmh: Double?) = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, nextManeuverM = 233.0, maneuverSide = Turn.RIGHT, reroutes = 0),
        ScenarioEvent(5_000, turn = Turn.RIGHT, nextManeuverM = 150.0, blindSpotRight = true),
        ScenarioEvent(8_000, blindSpotRight = false),
        ScenarioEvent(12_000, turn = Turn.OFF),
        ScenarioEvent(16_000, reroutes = 1, nextManeuverM = 900.0),
        ScenarioEvent(18_000, speedKmh = endKmh),
    )

    @Test fun `moving again - no line`() {
        assertNull(quickVerdict(missedThen(30.0), listOf(9_000)))
    }

    @Test fun `unknown speed counts as moving - no line`() {
        assertFalse(Snapshot(0).stopped()) // no speed sample yet: the 0.0 placeholder is not "stopped"
        assertNull(quickVerdict(missedThen(0.0).map { it.copy(speedKnown = false) }, listOf(9_000)))
    }

    @Test fun `only the exit scene gets a line - a route tap waits for P`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 50.0, turn = Turn.OFF, reroutes = 0, remainSec = 1_210),
            ScenarioEvent(10_000, remainSec = 1_200),
            ScenarioEvent(30_000, reroutes = 1, remainSec = 1_400),
            ScenarioEvent(40_000, speedKmh = 0.0),
        )
        assertNull(quickVerdict(t, listOf(35_000)))
    }
}
