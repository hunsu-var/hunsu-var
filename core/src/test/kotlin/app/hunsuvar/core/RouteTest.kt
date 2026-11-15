package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    private fun trace(vararg events: ScenarioEvent): List<Snapshot> =
        SignalRecorder().also { Scenario("test", events.toList()).replayInto(it) }.snapshots()

    /** 60 km/h (16.67 m/s); signal RIGHT 5–12 s; navi exit 150 m ahead at 5 s; a car in the next lane 5–8 s. */
    private fun exitTrip(vararg after: ScenarioEvent) = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, nextManeuverM = 233.0, maneuverSide = Turn.RIGHT, reroutes = 0),
        ScenarioEvent(5_000, turn = Turn.RIGHT, nextManeuverM = 150.0, blindSpotRight = true),
        ScenarioEvent(8_000, blindSpotRight = false),
        ScenarioEvent(12_000, turn = Turn.OFF),
        *after,
        ScenarioEvent(60_000, gear = Gear.P),
    )

    private fun waitOf(t: List<Snapshot>) = detectCandidates(t).single { it.kind == CandidateKind.LANE_CHANGE_ATTEMPT }

    @Test fun `missed exit is laid out in metres - signal, next-lane car, exit, reroute`() {
        val t = exitTrip(ScenarioEvent(20_000, reroutes = 1, nextManeuverM = 900.0))
        val r = routeReplayFor(waitOf(t), t)
        assertEquals(Turn.RIGHT, r.side)
        assertEquals(50.0, r.startM, 0.5)                      // 3 s of context at 16.67 m/s
        assertEquals(200.0, r.exitM!!, 0.5)                    // 150 m past the signal
        assertEquals(300.0, r.rerouteM!!, 0.5)                 // rerouted at 20 s
        assertFalse(r.tookExit)
        val side = r.stripes.getValue(Stripe.SIDE_CAR).single()
        assertEquals(50.0, side.fromM, 0.5); assertEquals(100.0, side.toM, 0.5)
        val signal = r.stripes.getValue(Stripe.SIGNAL).single()
        assertEquals(166.7, signal.toM, 0.5)
        assertEquals(r.rerouteM!!, r.mAt(20_000), 0.5)       // the moving car meets the marker at the same metre
        assertEquals(100.0, r.gap!!.fromM, 0.5); assertEquals(166.7, r.gap!!.toM, 0.5) // next lane empty 8–12 s
        assertEquals(4_000L, r.gapMs)
    }

    @Test fun `exit made - the car takes the ramp and there is no reroute marker`() {
        val t = exitTrip(ScenarioEvent(15_000, nextManeuverM = 2_400.0))
        val r = routeReplayFor(waitOf(t), t)
        assertTrue(r.tookExit)
        assertNull(r.rerouteM)
    }

    @Test fun `a brake is a single-lane replay with the brake stripe`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 50.0),
            ScenarioEvent(5_000, brake = true, speedKmh = 40.0),
            ScenarioEvent(6_000, speedKmh = 30.0),
            ScenarioEvent(7_000, speedKmh = 20.0),
            ScenarioEvent(8_000, speedKmh = 0.0, brake = false),
            ScenarioEvent(15_000, gear = Gear.P),
        )
        val c = detectCandidates(t).single { it.kind == CandidateKind.BRAKE || it.kind == CandidateKind.HARSH_BRAKE }
        val r = routeReplayFor(c, t)
        assertEquals(Turn.OFF, r.side)
        assertNull(r.exitM)
        assertTrue(r.stripes.getValue(Stripe.BRAKE).single().toM > r.startM)
        assertEquals(40.0 to 0.0, r.brakeKmh)
    }

    @Test fun `a leftover navi maneuver is not drawn on a brake replay`() {
        val t = SignalRecorder().also { demoScenario().replayInto(it) }.snapshots() // navi says 900 m after the exit scene
        val c = detectCandidates(t).first { it.kind == CandidateKind.BRAKE }
        assertNull(routeReplayFor(c, t).exitM)
    }

    @Test fun `demo exit scene - exit 100 m after the signal, then a reroute`() {
        val t = SignalRecorder().also { demoScenario().replayInto(it) }.snapshots()
        val r = routeReplayFor(waitOf(t), t)
        assertEquals(100.0, r.exitM!! - r.startM, 0.5)
        assertEquals(r.startM, r.stripes.getValue(Stripe.SIGNAL).first().fromM, 0.5) // the earlier turn's blinker is not drawn
        assertTrue(r.rerouteM!! > r.exitM!!)
        assertTrue(r.stripes.containsKey(Stripe.SIDE_CAR))
    }
}
