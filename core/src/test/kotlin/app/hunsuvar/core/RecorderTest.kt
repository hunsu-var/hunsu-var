package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderTest {
    @Test fun repeatsAreThinnedToOnePerSecond() {
        val r = SignalRecorder()
        for (t in 0L..3_000L step 100) r.update(t) { it.copy(gear = Gear.P) } // 10 Hz, nothing changes
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), r.snapshots().map { it.tMs })
    }

    @Test fun changesAreAlwaysKept() {
        val r = SignalRecorder()
        for (t in 0L..500L step 100) r.update(t) { it.copy(speedKmh = t / 10.0) }
        assertEquals(6, r.snapshots().size)
    }

    @Test fun noHistoryWithoutConsent() {
        val r = SignalRecorder()
        r.update(0) { it.copy(gear = Gear.D) }
        r.update(5_000) { it.copy(speedKmh = 40.0) }
        r.recording = false // revoked: history goes, the current state stays for the parked gate
        assertEquals(listOf(5_000L), r.snapshots().map { it.tMs })
        r.update(6_000) { it.copy(gear = Gear.P) }
        assertEquals(Gear.P, r.current()?.gear)
        assertEquals(1, r.snapshots().size)
    }

    @Test fun gearListenerFiresOnChangeOnly() {
        val r = SignalRecorder()
        val seen = mutableListOf<Gear>()
        r.onGear = { seen += it }
        r.update(0) { it.copy(gear = Gear.D) }
        for (t in 100L..900L step 100) r.update(t) { it.copy(speedKmh = t / 10.0) }
        r.update(2_000) { it.copy(gear = Gear.P) }
        assertEquals(listOf(Gear.D, Gear.P), seen)
    }

    @Test fun leavingParkStartsANewTripAndDropsTheOldOne() {
        val r = SignalRecorder()
        r.update(0) { it.copy(gear = Gear.D, speedKmh = 30.0) }
        r.mark(1_000)                                   // yesterday's tap
        r.update(2_000) { it.copy(gear = Gear.P, speedKmh = 0.0) }
        r.update(60_000) { it.copy(gear = Gear.D) }     // next trip
        r.mark(61_000)
        assertEquals(listOf(2_000L, 60_000L), r.snapshots().map { it.tMs }) // the P before the trip stays as its boundary
        assertEquals(listOf(61_000L), r.marks())
    }
}
