package app.hunsuvar.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Parking = the slow stretch that ends in P: from the end of the last ≥15 km/h run (≥3 s) to the gear going to P,
 * at most 5 min and never across the previous park. The car sees only itself (gear, speed, wheel) — never the bay,
 * the lines or the car next door — so only what the two remember differently about the *driver's own moves* is judged.
 */
internal fun parkings(trace: List<Snapshot>): List<Candidate> {
    val t = Thresholds
    val fast = trace.spans { it.speedKmh >= t.PARK_FAST_KMH }.filter { it.ms >= t.PARK_FAST_MIN_MS }
    return trace.zipWithNext().filter { (a, b) -> b.gear == Gear.P && a.gear != Gear.P }.mapNotNull { (a, b) ->
        val end = b.tMs
        val tripStart = trace.lastOrNull { it.tMs < a.tMs && it.gear == Gear.P }?.tMs ?: trace.first().tMs
        val start = maxOf(fast.lastOrNull { it.end <= end }?.end ?: tripStart, tripStart, end - t.PARK_MAX_MS)
        if (trace.between(start, end).distanceM() < t.PARK_MIN_M) null else Candidate(CandidateKind.PARKING, start, end)
    }
}

/**
 * [corrections] = extra direction changes
 * beyond the one reverse a back-in needs. [wiggleDeg] = how far the wheel moved after being straightened on the last
 * move into the bay; null = never straightened. [ms] = how long the episode took.
 */
internal data class ParkingFacts(val corrections: Int, val wiggleDeg: Double?, val ms: Long)

internal fun parkingFacts(c: Candidate, trace: List<Snapshot>): ParkingFacts {
    val t = Thresholds
    val w = trace.between(c.startMs, c.endMs)
    // Moves = rolling stretches long enough to count, merged when the gear didn't change in between.
    val moves = w.spans { it.speedKmh >= t.STOPPED_KMH }
        .map { s -> s to (w.lastOrNull { it.tMs <= s.start }?.gear ?: Gear.UNKNOWN) }
        .filter { (s, g) -> (g == Gear.D || g == Gear.R) && w.between(s.start, s.end).distanceM() >= t.MOVE_MIN_M }
        .fold(listOf<Pair<Span, Gear>>()) { acc, m -> if (acc.lastOrNull()?.second == m.second) acc.dropLast(1) + (Span(acc.last().first.start, m.first.end) to m.second) else acc + m }
    val switches = moves.zipWithNext().count { (a, b) -> a.second != b.second }
    val corrections = maxOf(0, switches - if (moves.any { it.second == Gear.R }) 1 else 0)
    val wiggle = moves.lastOrNull()?.first?.let { last ->
        val lastMove = w.between(last.start, last.end)
        val straight = lastMove.indexOfFirst { abs(it.steeringDeg) < t.CENTER_STEER_DEG }
        if (straight < 0) null else lastMove.drop(straight).maxOf { abs(it.steeringDeg) }
    }
    return ParkingFacts(corrections, wiggle, c.endMs - c.startMs)
}

private const val PARK_LAW = "주차 방법을 정한 법 조항은 없어요."

private fun deg(d: Double) = "${d.roundToInt()}°"
private fun secs(ms: Long) = "${(ms / 1000.0).roundToInt()}⁠초"

internal fun judgeParking(claim: Claim, c: Candidate, trace: List<Snapshot>): Verdict {
    if (c.kind != CandidateKind.PARKING) return Verdict(Outcome.UNDECIDABLE, Confidence.LOW,
        "판독 결과… 데이터로는 알 수 없습니다. 판독 불가!", "주차 장면이 아니에요.", PARK_LAW, "주차 장면을 골라 주세요.")
    val f = parkingFacts(c, trace)
    val t = Thresholds
    return when (claim) {
        Claim.HOLD_STRAIGHT -> when {
            f.wiggleDeg == null -> Verdict(Outcome.UNDECIDABLE, Confidence.LOW,
                "판독 결과… 데이터로는 알 수 없습니다. 판독 불가!",
                "마지막으로 들어가는 동안 핸들이 계속 꺾여 있었어요. 똑바로 들어가야 할 때였는지는 주차칸을 못 봐서 몰라요.",
                PARK_LAW, "차가 볼 수 없는 장면이에요. 이번 판은 무승부!")
            f.wiggleDeg >= t.STRAIGHT_WIGGLE_DEG -> Verdict(Outcome.HIT, Confidence.MEDIUM,
                "판독 결과… 마지막으로 들어갈 때 핸들이 ${deg(f.wiggleDeg)} 움직였습니다. 훈수 적중!",
                "핸들을 가운데로 푼 뒤에도 ${deg(f.wiggleDeg)}까지 돌렸어요.", PARK_LAW,
                "바퀴를 곧게 편 뒤에는 핸들은 그대로 두고 브레이크로만 속도를 조절해요.")
            else -> Verdict(Outcome.REJECTED, Confidence.MEDIUM,
                "판독 결과… 마지막으로 들어갈 때 핸들은 거의 그대로였습니다. 훈수 기각!",
                "가운데로 푼 뒤 핸들은 ±${deg(f.wiggleDeg)} 안에서만 움직였어요.", PARK_LAW, "운전자 무죄.")
        }

        else -> if (f.corrections == 0) Verdict(Outcome.REJECTED, Confidence.HIGH, // ONE_GO
            "판독 결과… 한 번에 들어갔습니다. 훈수 기각!",
            "앞뒤로 고쳐 댄 적 없이 주차를 마쳤어요(${secs(f.ms)}).", PARK_LAW, "운전자 무죄.")
        else Verdict(Outcome.UNDECIDABLE, Confidence.LOW,
            "판독 결과… 데이터로는 알 수 없습니다. 판독 불가!",
            "기어를 바꿔 앞뒤로 ${f.corrections}번 고쳐 댔어요(${secs(f.ms)}). 들어갈 수 있는 자리였는지는 주차칸과 옆 차를 못 봐서 몰라요.",
            PARK_LAW,
            // TODO(tip): 도로교통공단·운전 교본 원문으로 확인 전 PT 인용 금지.
            "후진하기 전에 주차칸과 나란히 서고, 뒷바퀴가 칸 입구를 지날 때 핸들을 돌리면 한 번에 들어가기 쉬워요.")
    }
}

/** One sample of the redrawn parking path, metres from the start, plus the gear it was driven in. */
data class PathPoint(val tMs: Long, val x: Double, val y: Double, val headingRad: Double, val gear: Gear)

/**
 * The parking episode seen from above, redrawn from speed and wheel angle (kinematic bicycle model). Shape only:
 * the bay, lines and other cars are not known, so nothing is drawn around it. [turns] = where each correction began.
 * ponytail: steering ratio and wheelbase are fixed guesses (calibrate per car: INFO_EXTERIOR_DIMENSIONS when available).
 */
data class ParkingPath(val points: List<PathPoint>, val turns: List<PathPoint>)

private const val STEERING_RATIO = 14.0
private const val WHEELBASE_M = 2.8

fun parkingPathFor(c: Candidate, trace: List<Snapshot>): ParkingPath {
    val w = trace.between(c.startMs, c.endMs)
    val pts = mutableListOf(PathPoint(w.first().tMs, 0.0, 0.0, 0.0, w.first().gear))
    w.zipWithNext().forEach { (a, b) ->
        val p = pts.last()
        val steps = maxOf(1, ((b.tMs - a.tMs) / 100).toInt()) // 0.1 s steps so curves stay round
        var x = p.x; var y = p.y; var h = p.headingRad
        val v = a.speedKmh / 3.6 * (if (a.gear == Gear.R) -1 else 1)
        // Positive steering = right (AAOS: left is negative) = clockwise seen from above, i.e. heading decreases (y points up).
        val yaw = -v * kotlin.math.tan(Math.toRadians(a.steeringDeg / STEERING_RATIO)) / WHEELBASE_M
        val dt = (b.tMs - a.tMs) / 1000.0 / steps
        repeat(steps) { x += v * kotlin.math.cos(h) * dt; y += v * kotlin.math.sin(h) * dt; h += yaw * dt }
        pts += PathPoint(b.tMs, x, y, h, b.gear)
    }
    val turns = pts.zipWithNext().filter { (a, b) -> a.gear != b.gear && (b.gear == Gear.R || b.gear == Gear.D) && (a.gear == Gear.R || a.gear == Gear.D) }
        .map { it.second }.drop(if (pts.any { it.gear == Gear.R }) 1 else 0) // the first switch into reverse is the plan, not a correction
    return ParkingPath(pts, turns)
}
