package app.hunsuvar.core

/**
 * While driving, at the next stop: the latest "훈수!" tap gets a one-line verdict so the argument ends there, not at home.
 * Only the 빠질 길 scene ("아까 들어갔어야지!"); parking, route choice, the evidence and the other claims wait for P.
 * null = nothing to say: not standing still with a known speed, no tap, not a 빠질 길 tap, the moment isn't decided,
 * or the navi hasn't told us whether the exit was made.
 */
data class QuickVerdict(val candidate: Candidate, val claim: Claim, val outcome: Outcome) {
    val line get() = "“${claim.label}” → ${outcome.label} · 자세한 건 주차하면"
}

fun quickVerdict(trace: List<Snapshot>, marks: List<Long>): QuickVerdict? {
    val m = marks.maxOrNull() ?: return null
    if (trace.lastOrNull()?.stopped() != true) return null
    val c = markCandidates(varCandidates(trace), listOf(m)).candidates.singleOrNull() ?: return null
    if (c.kind != CandidateKind.LANE_CHANGE_ATTEMPT) return null
    // An exit wait without its outcome yet could still flip to 기각 if the car makes the exit → wait.
    if (exitAheadM(trace, c.startMs, c.endMs) != null && exitOutcome(c, trace) == null) return null
    val claim = carClaimFor(c.kind)
    val v = judge(claim, c, trace)
    return if (v.outcome == Outcome.UNDECIDABLE) null else QuickVerdict(c, claim, v.outcome)
}
