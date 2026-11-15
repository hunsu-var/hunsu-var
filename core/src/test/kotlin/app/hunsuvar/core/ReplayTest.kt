package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {

    private fun trace(vararg events: ScenarioEvent): List<Snapshot> =
        SignalRecorder().also { Scenario("test", events.toList()).replayInto(it) }.snapshots()

    /** Constant-speed drive with the signal [leadMs] before a 120° turn at t=10 s. */
    private fun turnAt(speedKmh: Double, leadMs: Long) = trace(
        ScenarioEvent(0, gear = Gear.D, speedKmh = speedKmh),
        ScenarioEvent(10_000 - leadMs, turn = Turn.RIGHT),
        ScenarioEvent(10_000, steeringDeg = 120.0),
        ScenarioEvent(13_000, steeringDeg = 0.0, brake = true),
        ScenarioEvent(13_500, turn = Turn.OFF, brake = false),
        ScenarioEvent(15_000, gear = Gear.P),
    )

    private fun onlyTurn(t: List<Snapshot>) = detectCandidates(t).single { it.kind == CandidateKind.TURN }

    @Test fun `signal 2_1 s early at 60 kmh - turnOn starts at anchor minus 2100, legal lead about 1_8 s`() {
        val t = turnAt(60.0, 2_100)
        val c = onlyTurn(t)
        val r = replayFor(c, t)
        assertEquals(c.startMs, r.anchorMs)
        assertEquals(r.anchorMs - 2_100, r.turnOn.first().start)
        assertTrue(r.legalLeadMs.toString(), r.legalLeadMs!! in 1790..1810)
        assertEquals(listOf(Span(13_000, 13_500)), r.brakeOn)
        assertTrue(r.steering.contains(10_000L to 120.0))
        assertTrue(r.speed.all { it.second == 60.0 })
    }

    @Test fun `approach speed 3 kmh - no legal lead`() {
        val t = turnAt(3.0, 2_100)
        assertNull(replayFor(onlyTurn(t), t).legalLeadMs)
    }

    @Test fun `window is clamped to trace bounds`() {
        val t = turnAt(60.0, 2_100)
        val r = replayFor(onlyTurn(t), t, padMs = 60_000)
        assertEquals(0L, r.fromMs)
        assertEquals(15_000L, r.toMs)
        assertNotNull(r.speed.firstOrNull())
        assertTrue(r.speed.all { it.first in 0..15_000 })
    }

    @Test fun `15 kmh approach - window reaches the legal point`() {
        val t = turnAt(15.0, 2_100) // 30 m at 15 km/h = 7.2 s, more than the 5 s pad
        val r = replayFor(onlyTurn(t), t)
        assertTrue("from=${r.fromMs} lead=${r.legalLeadMs}", r.fromMs <= r.anchorMs - r.legalLeadMs!!)
    }

    @Test fun `default pad - window extended to the legal point is still clamped to the trace start`() {
        val t = turnAt(11.0, 2_100) // 30 m at 11 km/h = 9.8 s → legal point 0.2 s after trace start
        val r = replayFor(onlyTurn(t), t)
        assertEquals(0L, r.fromMs)
        assertTrue(r.fromMs <= r.anchorMs - r.legalLeadMs!!)
    }
}
