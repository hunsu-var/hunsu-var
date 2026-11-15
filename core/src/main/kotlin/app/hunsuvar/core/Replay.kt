package app.hunsuvar.core

/** Data for the VAR timeline of one candidate. Times are absolute; the UI plots them relative to [anchorMs]. */
data class ReplaySeries(
    val fromMs: Long, val toMs: Long, val anchorMs: Long,   // anchor = candidate.startMs (t=0 on the axis)
    val turnOn: List<Span>,                                  // spans where the signal is LEFT/RIGHT
    val steering: List<Pair<Long, Double>>,                  // (tMs, deg) step samples
    val brakeOn: List<Span>,
    val speed: List<Pair<Long, Double>>,
    val legalLeadMs: Long?,                                  // anchor − legalPointMs (30 m back along the trace); null if unknown or < MIN_TURN_SPEED
)

fun replayFor(candidate: Candidate, trace: List<Snapshot>, padMs: Long = 5_000): ReplaySeries {
    val legal = legalPointMs(trace, candidate.startMs)
    // The window must show the 30 m marker (slow approaches put it further back than the pad), with 1 s of context.
    val from = maxOf(minOf(candidate.startMs - padMs, (legal ?: Long.MAX_VALUE) - 1_000), trace.first().tMs)
    val to = minOf(candidate.endMs + padMs, trace.last().tMs)
    val w = trace.between(from, to)
    return ReplaySeries(
        fromMs = from, toMs = to, anchorMs = candidate.startMs,
        turnOn = w.spans { it.turn.on },
        steering = w.map { it.tMs to it.steeringDeg },
        brakeOn = w.spans { it.brake },
        speed = w.map { it.tMs to it.speedKmh },
        legalLeadMs = legal?.let { candidate.startMs - it },
    )
}
