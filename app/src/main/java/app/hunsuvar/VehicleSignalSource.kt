package app.hunsuvar

import android.os.SystemClock
import app.hunsuvar.core.Scenario
import app.hunsuvar.core.SignalRecorder
import app.hunsuvar.core.demoScenario
import app.hunsuvar.core.replayInto

/**
 * Timestamps for live signals and 훈수! taps: the wall clock read once, then advanced by the monotonic clock, so a time
 * sync mid-drive (GNSS/network) can't make intervals negative. ponytail: if the clock was wrong when the process started,
 * shown times stay off by that much until restart.
 */
private val wallAtBoot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
fun nowMs() = wallAtBoot + SystemClock.elapsedRealtime()

/** Feeds vehicle signal changes into a [SignalRecorder]. */
interface VehicleSignalSource {
    fun start(recorder: SignalRecorder)
    fun stop()
}

/** Demo mode: replays the bundled demo trip (made up, see [demoScenario]) for scenes the emulator can't produce (navi, rear-side warning). */
class FakeSignalSource(private val scenario: Scenario = demoScenario()) : VehicleSignalSource {
    override fun start(recorder: SignalRecorder) =
        scenario.replayInto(recorder, t0 = System.currentTimeMillis() - (scenario.events.lastOrNull()?.t ?: 0))

    override fun stop() {}
}
