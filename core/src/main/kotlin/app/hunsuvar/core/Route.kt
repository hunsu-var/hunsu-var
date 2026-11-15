package app.hunsuvar.core

/**
 * Road replay for everything except big turns: the car's run seen from above, laid out by distance travelled
 * (speed integrated over time), with what happened along it — where the signal was on, where a car sat in the
 * next lane, where the navi exit was and where the navi re-routed, where the brake was down.
 * Positions are metres from the start of the window. Lateral moves aren't drawn: lane-change steering is 2–5°.
 */
data class RouteSpan(val fromM: Double, val toM: Double)

enum class Stripe(val label: String) {
    SIGNAL("깜빡이"), SIDE_CAR("옆 차로에 차"), BRAKE("브레이크"),
    OVERSPEED("제한속도 초과"), DARK_IN_RAIN("와이퍼 켜고 전조등 꺼짐"), UNBELTED("벨트 안 맨 좌석"),
}

data class RouteReplay(
    val lengthM: Double,
    /** Side of the next lane / exit (the blinker side); OFF = draw a single lane. */
    val side: Turn,
    val stripes: Map<Stripe, List<RouteSpan>>,
    /** Where the candidate starts (signal on, brake down, …). */
    val startM: Double,
    /** The guided exit/turn the navi was pointing at, if any. */
    val exitM: Double? = null,
    /** The car took that exit (the navi moved on without a reroute). */
    val tookExit: Boolean = false,
    val rerouteM: Double? = null,
    /** Speed when the brake went down and when it came up (the first brake of the moment), km/h. */
    val brakeKmh: Pair<Double, Double>? = null,
    /** The longest stretch the next lane sat empty while signalling (≥ the gap bar), and how long it lasted. */
    val gap: RouteSpan? = null,
    val gapMs: Long? = null,
    /** (tMs, metres) samples for moving the car along the replay. */
    val positions: List<Pair<Long, Double>>,
) {
    fun mAt(tMs: Long): Double {
        val i = positions.indexOfLast { it.first <= tMs }
        if (i < 0) return 0.0
        val (t0, m0) = positions[i]
        val (t1, m1) = positions.getOrNull(i + 1) ?: return m0
        return m0 + (m1 - m0) * (tMs - t0).toDouble() / (t1 - t0).coerceAtLeast(1)
    }
    val fromMs get() = positions.first().first
    val toMs get() = positions.last().first
}

private const val PAD_MS = 3_000L
private const val AFTER_OUTCOME_MS = 1_500L

fun routeReplayFor(c: Candidate, trace: List<Snapshot>): RouteReplay {
    val t = Thresholds
    val outcome = if (c.kind == CandidateKind.LANE_CHANGE_ATTEMPT) exitOutcome(c, trace) else null
    val from = maxOf(c.startMs - PAD_MS, trace.first().tMs)
    val to = minOf(maxOf(c.endMs + PAD_MS, (outcome?.atMs ?: 0L) + AFTER_OUTCOME_MS), trace.last().tMs)
    val w = trace.between(from, to)
    val positions = mutableListOf(w.first().tMs to 0.0)
    w.zipWithNext().forEach { (a, b) -> positions += b.tMs to positions.last().second + a.speedKmh / 3.6 * (b.tMs - a.tMs) / 1000.0 }
    val r0 = RouteReplay(positions.last().second, Turn.OFF, emptyMap(), 0.0, positions = positions)
    val startM = r0.mAt(c.startMs)
    // Only what overlaps the moment itself: a blinker left over from the turn before would read as "signal on here".
    fun spans(cond: (Snapshot) -> Boolean) = w.spans(cond).map { RouteSpan(r0.mAt(it.start), r0.mAt(it.end)) }
        .filter { it.toM > it.fromM && it.toM > startM }

    val lane = c.kind == CandidateKind.LANE_CHANGE_ATTEMPT
    val side = if (lane) laneSide(c, trace) else Turn.OFF
    val stripes = buildMap {
        put(Stripe.SIGNAL, spans { it.turn.on })
        put(Stripe.SIDE_CAR, spans { it.blindSpot(side) == true }) // only the lane the car signalled to
        put(Stripe.BRAKE, spans { it.brake })
        put(Stripe.OVERSPEED, spans { s -> s.limitKmh()?.let { s.speedKmh - it >= t.OVERSPEED_MARGIN_KMH } == true })
        put(Stripe.DARK_IN_RAIN, spans { it.wiperOn == true && it.headlightOn == false })
        put(Stripe.UNBELTED, spans { (it.unbeltedSeats ?: 0) > 0 })
    }.filterValues { it.isNotEmpty() && c.kind != CandidateKind.ROUTE_CHANGE } // a route argument is about the way, not the pedals
    val brake = w.spans { it.brake }.firstOrNull { it.end > c.startMs }
    val gap = if (lane) laneGaps(c, trace).maxByOrNull { it.ms }?.takeIf { it.ms >= t.GAP_MIN_MS } else null
    fun kmhAt(ms: Long) = w.lastOrNull { it.tMs <= ms }?.speedKmh ?: 0.0
    return r0.copy(
        side = side, stripes = stripes, startM = startM,
        exitM = if (lane) exitAheadM(trace, c.startMs, c.endMs)?.let { startM + it } else null,
        brakeKmh = brake?.takeIf { c.kind != CandidateKind.ROUTE_CHANGE }?.let { kmhAt(it.start) to kmhAt(it.end) },
        tookExit = outcome?.missed == false,
        gap = gap?.let { RouteSpan(r0.mAt(it.start), r0.mAt(it.end)) }, gapMs = gap?.ms,
        rerouteM = outcome?.takeIf { it.missed }?.let { r0.mAt(it.atMs) } ?: if (c.kind == CandidateKind.ROUTE_CHANGE) r0.mAt(c.endMs) else null,
    )
}
