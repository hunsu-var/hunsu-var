package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The road replay's pin must sit where the verdict says the signal came on. 60 km/h = 16.67 m/s, wheel at t=10 s. */
class SignalOnMetersTest {
    private fun metres(signalAt: Long?): Double? {
        val events = listOfNotNull(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, turn = Turn.OFF),
            signalAt?.let { ScenarioEvent(it, turn = Turn.RIGHT) },
            ScenarioEvent(10_000, steeringDeg = 120.0),
            ScenarioEvent(13_000, steeringDeg = 0.0),
            ScenarioEvent(13_500, turn = Turn.OFF),
            ScenarioEvent(15_000, gear = Gear.P),
        ).sortedBy { it.t }
        val t = SignalRecorder().also { Scenario("test", events).replayInto(it) }.snapshots()
        return signalOnMeters(t, detectCandidates(t).single { it.kind == CandidateKind.TURN })
    }

    @Test fun earlyLateAfterAndNone() {
        assertEquals(35.0, metres(7_900)!!, 0.5)   // 2.1 s before the wheel → 기각 side of 30 m
        assertEquals(8.3, metres(9_500)!!, 0.5)    // 0.5 s before → 적중 side
        assertEquals(-8.3, metres(10_500)!!, 0.5)  // after the wheel turned
        assertNull(metres(null))                    // never on
    }
}
