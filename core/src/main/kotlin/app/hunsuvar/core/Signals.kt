package app.hunsuvar.core

/** UNKNOWN = the car hasn't reported the signal (yet). Never treated as OFF. */
enum class Turn { OFF, LEFT, RIGHT, UNKNOWN }
val Turn.on get() = this == Turn.LEFT || this == Turn.RIGHT
enum class Gear { P, R, N, D, UNKNOWN }

/**
 * Full vehicle state at [tMs]. A snapshot holds until the next one (step function).
 * `null` = the car didn't report this signal, which the verdict engine treats as "unknown", never as "off".
 */
data class Snapshot(
    val tMs: Long,
    val gear: Gear = Gear.UNKNOWN,
    val speedKmh: Double = 0.0,
    /** false = no speed sample yet (no CAR_SPEED permission, or car_service is down): [speedKmh] is a placeholder, never "stopped". */
    val speedKnown: Boolean = false,
    val steeringDeg: Double = 0.0,
    val turn: Turn = Turn.UNKNOWN,
    /** Pleos Brake SDK reports pedal pressed/not pressed only (Boolean), not a percentage. */
    val brake: Boolean = false,
    val wiperOn: Boolean? = null,
    val headlightOn: Boolean? = null,
    /** Number of occupied seats whose belt is not buckled. */
    val unbeltedSeats: Int? = null,
    val speedLimitKmh: Int? = null,
    val schoolZone: Boolean = false,
    /** LCA_LEFT / LCA_RIGHT telltale active = a car is in that side's blind spot. null = the car doesn't report it. */
    val blindSpotLeft: Boolean? = null,
    val blindSpotRight: Boolean? = null,
    /** NaviHelper TBT: metres to the next guided exit/turn. null = no route guidance. */
    val nextManeuverM: Double? = null,
    /** Side of that maneuver: LEFT/RIGHT = an exit, branch or turn to that side; OFF = not one (straight, tunnel, toll…); null = unknown. */
    val maneuverSide: Turn? = null,
    /** NaviHelper reroutes so far (onReRouteEnded count). A rise = the car left the guided route. null = no navi. */
    val reroutes: Int? = null,
    /** NaviHelper DestinationInfo.remainTime: seconds to the destination on the current route. null = no navi. TODO(verify): unit is seconds. */
    val remainSec: Int? = null,
)

/** Standing still: only with a known speed. An unknown speed counts as moving (safety). */
fun Snapshot.stopped() = speedKnown && speedKmh < Thresholds.STOPPED_KMH

/** The blind-spot warning on the [side] the car is signalling to; the other side is not this lane change's business. */
fun Snapshot.blindSpot(side: Turn): Boolean? = when (side) {
    Turn.LEFT -> blindSpotLeft
    Turn.RIGHT -> blindSpotRight
    else -> null
}

/** Effective limit: school zones cap at 30 km/h even if the navi limit is missing or higher. */
fun Snapshot.limitKmh(): Int? =
    if (schoolZone) minOf(Thresholds.SCHOOL_ZONE_LIMIT_KMH, speedLimitKmh ?: Int.MAX_VALUE) else speedLimitKmh

/**
 * In-memory ring buffer of snapshots; each signal change appends one. Nothing leaves the device.
 * An unchanged state (e.g. speed 0 at 10 Hz while parked) is kept at most once per [heartbeatMs],
 * so sample gaps stay ≤ 1 s and the time-based maths (decel window, spans) reads the same as before.
 * Only the current trip is kept: when the car leaves P, earlier trips and their taps are dropped (they are never judged again).
 * ponytail: 150k snapshots (~15 MB, ≥ 4 h of one trip at 10 Hz speed updates); downsample if single trips get longer.
 */
private val TRIP_GEARS = setOf(Gear.D, Gear.R, Gear.N)

class SignalRecorder(private val capacity: Int = 150_000, private val heartbeatMs: Long = 1_000) {
    private val buf = ArrayDeque<Snapshot>()
    private val marks = mutableListOf<Long>()

    /** false = no history, only the latest state (the parked gate still needs the gear). Follows driver consent; off also drops the marks. */
    @Volatile var recording = true
        set(on) { field = on; if (!on) synchronized(this) { while (buf.size > 1) buf.removeFirst(); marks.clear() } }

    /** 조수석 "훈수!" tap while driving: remember the moment (kept only with driver consent). See [markCandidates]. */
    @Synchronized fun mark(tMs: Long) { if (recording) marks += tMs }
    @Synchronized fun marks(): List<Long> = marks.toList()

    /** Called (on the signal thread) only when the gear changes, so the UI needn't poll. */
    @Volatile var onGear: ((Gear) -> Unit)? = null

    @Synchronized
    fun update(tMs: Long, change: (Snapshot) -> Snapshot) {
        val prev = buf.lastOrNull()
        val next = change(prev ?: Snapshot(tMs)).copy(tMs = tMs)
        if (next.gear != prev?.gear && next.gear in TRIP_GEARS) {
            // A new trip: keep only the last P sample (the trip boundary the engine looks for) and the taps after it.
            val p = buf.indexOfLast { it.gear == Gear.P }
            if (p > 0) { repeat(p) { buf.removeFirst() }; val from = buf.first().tMs; marks.removeAll { it < from } }
        }
        if (next.gear != prev?.gear) onGear?.invoke(next.gear)
        if (!recording) buf.clear()
        else if (prev != null && next == prev.copy(tMs = tMs) && tMs - prev.tMs < heartbeatMs) return
        buf.addLast(next)
        if (buf.size > capacity) buf.removeFirst()
    }

    @Synchronized fun snapshots(): List<Snapshot> = buf.toList()
    @Synchronized fun current(): Snapshot? = buf.lastOrNull()
    @Synchronized fun clear() = buf.clear()
}

data class Span(val start: Long, val end: Long) {
    val ms get() = end - start
}

/** Time spans where [cond] holds. The last snapshot is treated as ending at its own timestamp. */
fun List<Snapshot>.spans(cond: (Snapshot) -> Boolean): List<Span> {
    val out = mutableListOf<Span>()
    var start: Long? = null
    for (s in this) {
        val c = cond(s)
        if (c && start == null) start = s.tMs
        else if (!c && start != null) { out += Span(start, s.tMs); start = null }
    }
    if (start != null) out += Span(start, last().tMs)
    return out
}

/** Snapshots covering [from, to], with the state at `from` and `to` pinned as boundary samples. */
fun List<Snapshot>.between(from: Long, to: Long): List<Snapshot> {
    val head = lastOrNull { it.tMs <= from }?.copy(tMs = from)
    val tail = lastOrNull { it.tMs <= to }?.copy(tMs = to)
    return listOfNotNull(head) + filter { it.tMs > from && it.tMs < to } + listOfNotNull(tail)
}

/** Metres travelled (speed integrated over time). */
fun List<Snapshot>.distanceM(): Double =
    zipWithNext().sumOf { (a, b) -> a.speedKmh / 3.6 * (b.tMs - a.tMs) / 1000.0 }

/** Time-weighted average speed. */
fun List<Snapshot>.avgKmh(): Double {
    val ms = if (size < 2) 0 else last().tMs - first().tMs
    return if (ms <= 0) firstOrNull()?.speedKmh ?: 0.0 else distanceM() * 3.6 / (ms / 1000.0)
}
