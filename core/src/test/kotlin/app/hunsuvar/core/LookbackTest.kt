package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The signal lookback must reach the legal 30 m point, however long that took; if it can't be reached → 판독 불가, never a false HIT. */
class LookbackTest {

    private fun trace(vararg events: ScenarioEvent): List<Snapshot> =
        SignalRecorder().also { Scenario("test", events.toList()).replayInto(it) }.snapshots()

    private fun late(t: List<Snapshot>) =
        judge(Claim.SIGNAL_LATE, detectCandidates(t).single { it.kind == CandidateKind.TURN }, t).outcome

    @Test fun `signal on before a red light, creeping 20 s after - 30 m point is 18 s back, still rejected`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 30.0, turn = Turn.RIGHT), // signalled early
            ScenarioEvent(5_000, speedKmh = 0.0),                                // red light, signal still on
            ScenarioEvent(45_000, speedKmh = 6.0),                               // green: creep 20 s = 33 m
            ScenarioEvent(65_000, steeringDeg = 120.0),
            ScenarioEvent(68_000, steeringDeg = 0.0),
            ScenarioEvent(68_500, turn = Turn.OFF),
            ScenarioEvent(70_000, gear = Gear.P),
        )
        assertEquals(Outcome.REJECTED, late(t))
    }

    @Test fun `trace starts with the signal already on and 30 m is not reachable - undecidable, not hit`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 60.0, turn = Turn.RIGHT),
            ScenarioEvent(1_500, steeringDeg = 120.0), // only 25 m of history
            ScenarioEvent(4_500, steeringDeg = 0.0, turn = Turn.OFF),
            ScenarioEvent(5_500, gear = Gear.P),
        )
        assertEquals(Outcome.UNDECIDABLE, late(t))
    }

    @Test fun `parked since the previous trip - lookback stops at the park, not into the old trip, undecidable not hit`() {
        val t = trace(
            ScenarioEvent(0, gear = Gear.D, speedKmh = 30.0, turn = Turn.OFF),  // previous trip, signal off
            ScenarioEvent(10_000, speedKmh = 0.0),                                // stop
            ScenarioEvent(10_500, gear = Gear.P),                                 // parked 20 s (no distance)
            ScenarioEvent(30_500, gear = Gear.D, turn = Turn.RIGHT),              // pull out, signal right after
            ScenarioEvent(31_000, speedKmh = 15.0),                               // 3 s creep = 12.5 m < 30 m
            ScenarioEvent(34_000, steeringDeg = 120.0),
            ScenarioEvent(36_000, steeringDeg = 0.0),
            ScenarioEvent(36_500, turn = Turn.OFF),
            ScenarioEvent(38_000, gear = Gear.P),
        )
        assertEquals(Outcome.UNDECIDABLE, late(t))
    }
}
