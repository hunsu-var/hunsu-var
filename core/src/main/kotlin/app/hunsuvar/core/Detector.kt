package app.hunsuvar.core

import kotlin.math.abs

/** Calibration knobs. Defaults follow the plan's rule table (§7); tune on a real car. */
object Thresholds {
    const val TURN_STEER_DEG = 90.0          // only big intersection turns are judged; lane changes are 2–5°
    const val CENTER_STEER_DEG = 15.0        // wheel counts as "returned" below this
    const val HARSH_DECEL_MS2 = 3.0          // ~0.3 g
    const val BRAKE_NOTICE_MS2 = 1.5         // below this a brake isn't worth a 판독 (normal gentle stop)
    const val DECEL_WINDOW_MS = 1_000L       // decel per second; also smooths the emulator's stepwise speed at 10 Hz
    const val OVERSPEED_MARGIN_KMH = 10
    const val OVERSPEED_MIN_MS = 3_000L
    const val SCHOOL_ZONE_LIMIT_KMH = 30
    const val RAIN_NO_LIGHT_MIN_MS = 30_000L
    const val UNBELTED_MIN_MS = 3_000L
    const val SIGNAL_LOOKBACK_MS = 15_000L
    const val SIGNAL_DISTANCE_M = 30.0       // 시행령 별표2 (고속도로 100m — big turns don't happen there)
    const val MIN_TURN_SPEED_KMH = 5.0       // below this the 30 m rule is meaningless → 판독 불가
    const val EARLY_OFF_TOLERANCE_MS = 1_000L
    const val CONTEXT_MS = 5_000L            // extra time around non-turn candidates
    const val LANE_CHANGE_MIN_MS = 3_000L    // signal on this long without a big turn = waiting for a gap
    const val LANE_CHANGE_MIN_KMH = 10.0
    const val CUT_IN_STEER_DEG = 5.0         // lane-change-sized steering
    const val LANE_STEER_DEG = 3.0           // wheel moved this far from where it was at the signal = the car is moving over (lane changes are 2–5°)
    const val MERGE_WINDOW_MS = 4_000L       // the merge happens just before the blinker goes off: look for it only in this last stretch (a curve earlier is not a merge)
    const val CUT_IN_AFTER_MS = 3_000L
    const val EXIT_NEAR_M = 1_000.0          // a lane-change wait counts as "for this exit" only if the navi maneuver is this close
    const val EXIT_OUTCOME_MS = 60_000L      // look this long after the wait for a reroute (missed) or the next maneuver (made it)
    const val GAP_MIN_MS = 3_000L            // rear-side lane quiet this long while signalling = a gap (app's own bar, not law)
    const val BSD_MIN_KMH = 20.0             // below this the rear-side radar is off (owner's manual) → 판독 불가
    const val MANEUVER_PASSED_JUMP_M = 50.0  // TBT distance jumping up this much = the guided exit/turn is behind us
    const val STOPPED_KMH = 1.0              // below this the car counts as standing still
    const val STOP_MIN_MS = 10_000L          // a stop in D this long = 신호 대기 (B3)
    const val RIGHT_TURN_STOP_LOOKBACK_MS = 10_000L
    const val MARK_LOOKBACK_MS = 20_000L     // a "훈수!" tap points at a candidate running in the 20 s before it
    const val LANE_LATE_TAP_MS = 300_000L    // a lane-change argument often flares minutes later (after the missed exit)
    const val PARK_FAST_KMH = 15.0           // parking starts after the last run at this speed…
    const val PARK_FAST_MIN_MS = 3_000L      // …lasting this long
    const val PARK_MAX_MS = 300_000L         // a parking episode is at most 5 min
    const val PARK_MIN_M = 1.0               // and the car actually moved
    const val MOVE_MIN_M = 0.3               // a forward/back move shorter than this is a nudge, not a correction
    const val STRAIGHT_WIGGLE_DEG = 30.0     // after straightening on the last move, the wheel moved this far again
    const val ROUTE_COST_MIN_S = 60          // a detour that costs at least this much (navi ETA) = "돌아가잖아" was right
    const val ROUTE_SETTLE_MS = 30_000L      // after a reroute, wait this long for the navi's new remaining time
    const val ROUTE_LATE_TAP_MS = 600_000L   // route arguments run on; a tap up to 10 min later still points here
}

/** 조수석 "훈수!" taps → [candidates] they point at, and [strays]: taps with no candidate nearby. */
data class Marked(val candidates: Set<Candidate> = emptySet(), val strays: List<Long> = emptyList())

/** How late a tap may still point at this scene: arguments about a missed gap flare minutes later; parking taps come while parking. */
private fun Candidate.tapUntil() = when (kind) {
    CandidateKind.LANE_CHANGE_ATTEMPT -> endMs + Thresholds.LANE_LATE_TAP_MS
    CandidateKind.PARKING -> endMs
    CandidateKind.ROUTE_CHANGE -> endMs + Thresholds.ROUTE_LATE_TAP_MS
    else -> endMs + Thresholds.MARK_LOOKBACK_MS
}

/** Each tap picks the latest-ending candidate it can still point at ([tapUntil]); the 훈수 comes after the moment. */
fun markCandidates(candidates: List<Candidate>, marks: List<Long>): Marked {
    val hit = mutableSetOf<Candidate>()
    val strays = mutableListOf<Long>()
    for (m in marks) {
        candidates.filter { it.startMs <= m && m <= it.tapUntil() }.maxByOrNull { it.endMs }
            ?.let { hit += it } ?: strays.add(m)
    }
    return Marked(hit, strays)
}

enum class CandidateKind(val label: String) {
    TURN("크게 꺾은 회전"),
    HARSH_BRAKE("급제동"),
    BRAKE("브레이크"),                       // the strongest non-harsh brake: "살살 밟아!" → usually 기각
    OVERSPEED("과속"),
    RAIN_NO_LIGHTS("비 오는데 전조등 꺼짐"),
    UNBELTED("안전벨트 미착용 주행"),
    LANE_CHANGE_ATTEMPT("차로 바꾸려고 기다림"),
    STOP_IN_D("D에 두고 정차"),
    PARKING("주차"),
    ROUTE_CHANGE("내비 경로를 벗어남"),
}

data class Candidate(val kind: CandidateKind, val startMs: Long, val endMs: Long)

/** Picks the "판독 후보" (ambiguous moments) from a recorded trip. Run when the car is in P. */
fun detectCandidates(trace: List<Snapshot>): List<Candidate> {
    if (trace.isEmpty()) return emptyList()
    val t = Thresholds
    fun Span.c(kind: CandidateKind) = Candidate(kind, start, end)

    val overspeed = trace.spans { s -> s.limitKmh()?.let { s.speedKmh - it >= t.OVERSPEED_MARGIN_KMH } == true }
        .filter { it.ms >= t.OVERSPEED_MIN_MS }.map { it.c(CandidateKind.OVERSPEED) }
    val rain = trace.spans { it.wiperOn == true && it.headlightOn == false && it.speedKmh > 0 }
        .filter { it.ms >= t.RAIN_NO_LIGHT_MIN_MS }.map { it.c(CandidateKind.RAIN_NO_LIGHTS) }
    val belts = trace.spans { (it.unbeltedSeats ?: 0) > 0 && it.speedKmh > 0 }
        .filter { it.ms >= t.UNBELTED_MIN_MS }.map { it.c(CandidateKind.UNBELTED) }
    val braking = trace.spans { it.brake }.map { it to maxDecelMs2(trace.between(it.start, it.end)) }
    val brakes = braking.filter { it.second >= t.HARSH_DECEL_MS2 }.map { it.first.c(CandidateKind.HARSH_BRAKE) }
    // ponytail: only the single strongest gentle brake (every normal stop would flood the list); per trace, not per trip.
    val gentle = listOfNotNull(braking.filter { it.second >= t.BRAKE_NOTICE_MS2 && it.second < t.HARSH_DECEL_MS2 }
        .maxByOrNull { it.second }?.first?.c(CandidateKind.BRAKE))
    // B3: same idea, only the longest stop in D (a 신호 대기 is the usual reason to stand still in D).
    val stops = listOfNotNull(trace.spans { it.gear == Gear.D && it.speedKmh < t.STOPPED_KMH }
        .filter { it.ms >= t.STOP_MIN_MS }.maxByOrNull { it.ms }?.c(CandidateKind.STOP_IN_D))

    val parks = parkings(trace)
    // Wheel work with reverse in it is parking, not an intersection turn.
    val turns = turns(trace).filter { tc -> trace.between(tc.startMs, tc.endMs).none { it.gear == Gear.R } }
    // Touching a TURN counts as overlapping: the signal before/after a real turn is the turn's, not a lane change.
    // Shown only when something can answer it: blind-spot (LCA) data, or a navi exit/turn close ahead. Otherwise 판독 불가 only.
    val laneChanges = trace.spans { it.turn.on && abs(it.steeringDeg) < t.TURN_STEER_DEG && it.speedKmh >= t.LANE_CHANGE_MIN_KMH }
        .filter { s -> s.ms >= t.LANE_CHANGE_MIN_MS && turns.none { it.startMs <= s.end && s.start <= it.endMs } }
        .filter { s -> trace.between(s.start, s.end).any { it.blindSpot(it.turn) != null } || exitAheadM(trace, s.start, s.end) != null }
        .map { it.c(CandidateKind.LANE_CHANGE_ATTEMPT) }

    // A reroute right after a lane-change wait is that scene's missed exit; any other reroute is the driver's own way.
    val exits = laneChanges.mapNotNull { c -> exitOutcome(c, trace)?.takeIf { it.missed }?.atMs }.toSet()
    val routes = trace.zipWithNext().filter { (a, b) -> (b.reroutes ?: 0) > (a.reroutes ?: 0) }.map { it.second.tMs }
        .filter { it !in exits && rerouteCostSec(trace, it) != null }
        .map { at -> Candidate(CandidateKind.ROUTE_CHANGE, maxOf(trace.first().tMs, at - 15_000), at) }

    return (turns + overspeed + rain + belts + brakes + gentle + stops + laneChanges + parks + routes).sortedBy { it.startMs }
}

/** Scenes people actually argue about (2026-11-04 사례 조사): merging and parking. The rest are rule reminders, not arguments. */
val VAR_KINDS = setOf(CandidateKind.LANE_CHANGE_ATTEMPT, CandidateKind.PARKING, CandidateKind.ROUTE_CHANGE)

/** The passenger's 판독 후보: only real arguments. Solo practice still uses [detectCandidates]. */
fun varCandidates(trace: List<Snapshot>): List<Candidate> = detectCandidates(trace).filter { it.kind in VAR_KINDS }

/**
 * Seconds the navi's estimated arrival moved at the reroute at [atMs] (+ = later). Arrival = when the remaining time was
 * reported + that time. Before = the last report before the reroute; after = the first new report within
 * [Thresholds.ROUTE_SETTLE_MS]. null = no navi remaining time around it.
 */
internal fun rerouteCostSec(trace: List<Snapshot>, atMs: Long): Double? {
    val i = trace.indexOfLast { it.tMs < atMs && it.remainSec != null }
    if (i < 0) return null
    val old = trace[i].remainSec!!
    var j = i
    while (j > 0 && trace[j - 1].remainSec == old) j--
    val after = trace.firstOrNull { it.tMs >= atMs && it.tMs <= atMs + Thresholds.ROUTE_SETTLE_MS && it.remainSec != null && it.remainSec != old }
        ?: return null
    return (after.tMs / 1000.0 + after.remainSec!!) - (trace[j].tMs / 1000.0 + old)
}

/**
 * Metres to the guided exit/turn when the wait began, if the navi had one within [Thresholds.EXIT_NEAR_M] on the side
 * the car signalled to. A tunnel, toll gate or "go straight" ahead, or an exit on the other side, is no 빠질 길.
 */
internal fun exitAheadM(trace: List<Snapshot>, from: Long, to: Long): Double? {
    val w = trace.between(from, to)
    val side = w.firstOrNull { it.turn.on }?.turn ?: return null
    val next = w.firstOrNull { it.nextManeuverM != null } ?: return null
    return next.nextManeuverM?.takeIf { next.maneuverSide == side && it <= Thresholds.EXIT_NEAR_M }
}

/** Turn = |steering| crosses 90°, ends when the wheel is back near centre (hysteresis). */
private fun turns(trace: List<Snapshot>): List<Candidate> {
    val out = mutableListOf<Candidate>()
    var start: Long? = null
    for (s in trace) {
        val a = abs(s.steeringDeg)
        if (start == null && a >= Thresholds.TURN_STEER_DEG) start = s.tMs
        else if (start != null && a < Thresholds.CENTER_STEER_DEG) {
            out += Candidate(CandidateKind.TURN, start, s.tMs); start = null
        }
    }
    if (start != null) out += Candidate(CandidateKind.TURN, start, trace.last().tMs)
    return out
}

/** Max deceleration in m/s², measured over ≥ [Thresholds.DECEL_WINDOW_MS] steps. */
fun maxDecelMs2(window: List<Snapshot>): Double {
    var best = 0.0
    for ((i, a) in window.withIndex()) {
        val b = window.drop(i + 1).firstOrNull { it.tMs - a.tMs >= Thresholds.DECEL_WINDOW_MS } ?: break
        best = maxOf(best, (a.speedKmh - b.speedKmh) / 3.6 / ((b.tMs - a.tMs) / 1000.0))
    }
    return best
}
