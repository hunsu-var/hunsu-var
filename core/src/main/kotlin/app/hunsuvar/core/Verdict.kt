package app.hunsuvar.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** [littleKnown] = the verdict card shows "이 규칙 몰랐죠?" (rules most drivers get wrong). */
enum class Claim(val label: String, val littleKnown: Boolean = false) {
    SIGNAL_LATE("깜빡이 늦었어!", littleKnown = true), // 30 m 전 = 시속 60 km면 1.8초 전
    SIGNAL_MISSING("깜빡이 안 켰잖아!"),
    SIGNAL_EARLY_OFF("벌써 껐어?"),
    RIGHT_TURN_STOP("우회전할 땐 일단 멈춰야지!", littleKnown = true), // 기획 §1: 400명 중 1명만 정확히 앎
    RED_LIGHT("신호 위반했잖아!"),
    TOO_FAST("너무 빨라!"),
    BRAKE_HARSH("브레이크 살살!"),
    LIGHTS_IN_RAIN("비 오는데 라이트!", littleKnown = true),
    SEATBELT("뒤에도 벨트 해!"),
    MISSED_GAP("아까 들어갔어야지!"),
    CUT_IN_TIGHT("차! 차!"),
    NEUTRAL_AT_STOP("신호 대기엔 N에 놔야지!", littleKnown = true),
    HOLD_STRAIGHT("핸들 똑바로 잡으라니까!"),
    ONE_GO("거기 못 들어가!"),
    ROUTE_LONGER("이 길로 오면 돌아가잖아!"),
}

enum class Outcome(val label: String) { HIT("훈수 적중"), REJECTED("훈수 기각"), UNDECIDABLE("판독 불가") }
enum class Confidence(val label: String) { HIGH("높음"), MEDIUM("중"), LOW("낮음") }

/** Verdict card: [headline] is the humor layer (top), [law] + [tip] the function layer (bottom). */
data class Verdict(
    val outcome: Outcome,
    val confidence: Confidence,
    val headline: String,
    val evidence: String,
    val law: String,
    val tip: String,
)

/** Headline safe to post: speed readings (km) are dropped for a generic line; times/laws stay. */
val Verdict.shareHeadline get() = if ("km" in headline) "판독 결과… ${outcome.label}!" else headline

/** Claims that make sense for a candidate — what the UI offers. */
fun claimsFor(kind: CandidateKind): List<Claim> = when (kind) {
    CandidateKind.TURN -> listOf(Claim.SIGNAL_LATE, Claim.SIGNAL_MISSING, Claim.SIGNAL_EARLY_OFF, Claim.RIGHT_TURN_STOP, Claim.RED_LIGHT)
    CandidateKind.HARSH_BRAKE, CandidateKind.BRAKE -> listOf(Claim.BRAKE_HARSH)
    CandidateKind.OVERSPEED -> listOf(Claim.TOO_FAST)
    CandidateKind.RAIN_NO_LIGHTS -> listOf(Claim.LIGHTS_IN_RAIN)
    CandidateKind.UNBELTED -> listOf(Claim.SEATBELT)
    CandidateKind.LANE_CHANGE_ATTEMPT -> listOf(Claim.MISSED_GAP, Claim.CUT_IN_TIGHT)
    CandidateKind.STOP_IN_D -> listOf(Claim.NEUTRAL_AT_STOP)
    CandidateKind.PARKING -> listOf(Claim.HOLD_STRAIGHT, Claim.ONE_GO)
    CandidateKind.ROUTE_CHANGE -> listOf(Claim.ROUTE_LONGER)
}

/** Solo mode: the car's own 훈수 for a candidate. Deliberately the first (most generic) claim. */
fun carClaimFor(kind: CandidateKind): Claim = claimsFor(kind).first()

// Paraphrased summaries — TODO(law): check wording against 법제처 원문 before PT (plan 부록 B).
internal object Law {
    const val SIGNAL = "도로교통법 제38조 · 시행령 별표2: 좌·우회전, 유턴, 진로변경 때는 그 지점 30\u00A0m 전(고속도로 100\u00A0m 전)부터 그 행위를 마칠 때까지 방향지시등으로 신호해야 합니다."
    const val SPEED = "도로교통법 제17조: 도로별 제한속도를 지켜야 합니다. 제12조: 어린이보호구역은 시속 30\u00A0km 이내로 제한할 수 있습니다."
    const val BRAKE = "도로교통법 제19조 제4항: 위험을 피하려는 경우나 그 밖의 부득이한 경우가 아니면 차를 갑자기 세우거나 속도를 줄이는 급제동을 해서는 안 됩니다."
    const val LIGHTS = "도로교통법 제37조: 밤, 안개가 끼거나 비·눈이 올 때, 터널 안에서는 전조등·차폭등·미등을 켜야 합니다."
    // TODO(law): paraphrased from 제19조 제3항 — verify against 법제처 원문.
    const val LANE = "도로교통법 제19조 제3항: 진로를 바꿀 때 다른 차의 정상적인 통행에 장애를 줄 우려가 있으면 진로를 바꾸면 안 됩니다."
    const val BELT = "도로교통법 제50조: 운전자는 좌석안전띠를 매고, 모든 좌석의 동승자에게도 매도록 해야 합니다."
    const val TRAFFIC_LIGHT = "도로교통법 제5조: 운전자는 신호기가 표시하는 신호를 따라야 합니다."
    const val PEDESTRIAN = "도로교통법 제27조 제1항: 보행자가 횡단보도를 건너고 있거나 건너려고 할 때는 횡단보도 앞(정지선이 있으면 정지선)에서 일시정지해야 합니다."
    // Not a law: the owner's manual (ownersmanual.hyundai.com, 2025 투싼 '변속 위치'·'자동 정차' 쪽). TODO(manual): Pleos 탑재 차종 원문 확인.
    const val GAP = "법 기준이 아니에요. 깜빡이를 켠 동안 후측방 경고(뒤쪽 옆 차로 감지)가 3⁠초 이상 꺼져 있던 때를 들어갈 틈으로 봐요. 앞쪽 옆 차나 넓은 도로의 차는 놓칠 수 있어요."
    const val ROUTE = "길 선택은 법으로 정해진 게 아니에요. 내비가 계산한 예상 도착 시간으로만 비교해요(실제 교통 상황과 다를 수 있어요)."
    const val STOP = "현대자동차 취급설명서: 주행 중 차를 잠시 멈출 때는 브레이크 페달을 세게 밟으라고 안내합니다. 자동 정차(AUTO HOLD)는 D(주행)에서 멈춘 뒤 브레이크에서 발을 떼도 정지를 유지합니다."
}

private fun sec(ms: Long) = "%.1f".format(abs(ms) / 1000.0)
/** Cut to 0.1 s, never rounded up: a 2.96 s gap must not read "3.0초" next to the 3 s bar. */
private fun secDown(ms: Long) = sec(abs(ms) / 100 * 100)

private fun undecidable(why: String, law: String, tip: String = "차가 볼 수 없는 장면이에요. 이번 판은 무승부!") =
    Verdict(Outcome.UNDECIDABLE, Confidence.LOW, "판독 결과… 데이터로는 알 수 없습니다. 판독 불가!", why, law, tip)

/** Judges one [claim] about one [candidate]. When in doubt → 판독 불가 (a wrong verdict kills trust fastest). */
fun judge(claim: Claim, candidate: Candidate, trace: List<Snapshot>): Verdict = when (claim) {
    Claim.SIGNAL_LATE, Claim.SIGNAL_MISSING, Claim.SIGNAL_EARLY_OFF -> judgeTurn(claim, candidate, trace)
    Claim.MISSED_GAP, Claim.CUT_IN_TIGHT -> judgeLaneChange(claim, candidate, trace)
    Claim.RED_LIGHT -> undecidable("신호등 색과 정지선은 카메라가 있어야 봅니다. 깜빡이·핸들·속도 기록으로는 알 수 없어요.", Law.TRAFFIC_LIGHT)
    Claim.RIGHT_TURN_STOP -> judgeRightTurnStop(candidate, trace)
    Claim.NEUTRAL_AT_STOP -> judgeStop(candidate, trace)
    Claim.HOLD_STRAIGHT, Claim.ONE_GO -> judgeParking(claim, candidate, trace)
    Claim.ROUTE_LONGER -> judgeRoute(candidate, trace)
    else -> judgeWindow(claim, trace.between(candidate.startMs - Thresholds.CONTEXT_MS, candidate.endMs + Thresholds.CONTEXT_MS))
}

/** Average speed over the 3 s before the candidate starts. */
internal fun approachKmh(trace: List<Snapshot>, c: Candidate) = trace.between(c.startMs - 3_000, c.startMs).avgKmh()

/**
 * Timestamp of the legal signal point: walk back from [anchorMs] integrating speed until [Thresholds.SIGNAL_DISTANCE_M]
 * is covered (interpolated inside the last segment). null if the trace starts first, the approach is too slow to judge,
 * or the walk reaches a park (gear P): parked time adds no distance, so the walk must not cross into the previous trip.
 * The ONE definition of "30 m before" — both the verdict and the VAR marker use it.
 */
internal fun legalPointMs(trace: List<Snapshot>, anchorMs: Long): Long? {
    if (trace.between(anchorMs - 3_000, anchorMs).avgKmh() < Thresholds.MIN_TURN_SPEED_KMH) return null
    var left = Thresholds.SIGNAL_DISTANCE_M
    var end = anchorMs
    for (s in trace.filter { it.tMs < anchorMs }.asReversed()) {
        if (s.gear == Gear.P) return null // trip boundary
        val v = s.speedKmh / 3.6
        val d = v * (end - s.tMs) / 1000.0
        if (d >= left) return end - (left / v * 1000).roundToLong()
        left -= d
        end = s.tMs
    }
    return null
}

private fun signalUnknown(trace: List<Snapshot>, c: Candidate, legal: Long?) =
    trace.between(legal ?: c.startMs, c.endMs).any { it.turn == Turn.UNKNOWN }

/**
 * Signal spans that overlap the turn; an earlier turn's blink inside the lookback doesn't count.
 * The window must reach the legal point, else a signal already on is clipped to the window start and looks "late".
 * A span clipped to the window start therefore starts <= legal, so it can never produce a false HIT.
 */
private fun turnSignals(trace: List<Snapshot>, c: Candidate, legal: Long?): List<Span> {
    val from = minOf(legal ?: Long.MAX_VALUE, c.startMs - Thresholds.SIGNAL_LOOKBACK_MS)
    return trace.between(from, c.endMs).spans { it.turn.on }.filter { it.end > c.startMs }
}

/** How long before the wheel the signal came on (negative = after). null: no signal, no signal data, or too slow to judge. */
internal fun signalLeadMs(trace: List<Snapshot>, c: Candidate): Long? {
    if (c.kind != CandidateKind.TURN || approachKmh(trace, c) < Thresholds.MIN_TURN_SPEED_KMH) return null
    val legal = legalPointMs(trace, c.startMs)
    if (signalUnknown(trace, c, legal)) return null
    return turnSignals(trace, c, legal).firstOrNull()?.let { c.startMs - it.start }
}

/**
 * Road replay (UI): metres before the wheel where the signal came on, from the same spans the verdict uses.
 * Negative = it came on after the wheel turned. null: no signal on this turn, no signal data, or too slow to judge.
 */
fun signalOnMeters(trace: List<Snapshot>, c: Candidate): Double? {
    if (c.kind != CandidateKind.TURN || approachKmh(trace, c) < Thresholds.MIN_TURN_SPEED_KMH) return null
    val legal = legalPointMs(trace, c.startMs)
    if (signalUnknown(trace, c, legal)) return null
    val on = turnSignals(trace, c, legal).firstOrNull() ?: return null
    return if (on.start <= c.startMs) trace.between(on.start, c.startMs).distanceM()
    else -trace.between(c.startMs, on.start).distanceM()
}

private fun judgeTurn(claim: Claim, c: Candidate, trace: List<Snapshot>): Verdict {
    if (c.kind != CandidateKind.TURN) return undecidable(
        "핸들을 크게(90° 이상) 꺾은 회전이 아니에요. 차로변경처럼 살짝 꺾을 때는 깜빡이 판정을 하지 않습니다.", Law.SIGNAL)

    val approachKmh = approachKmh(trace, c)
    if (approachKmh < Thresholds.MIN_TURN_SPEED_KMH) return undecidable(
        "회전 직전 차가 거의 서 있었어요(시속 ${approachKmh.roundToInt()}\u00A0km). 30\u00A0m 기준을 적용할 수 없습니다.", Law.SIGNAL)

    val legal = legalPointMs(trace, c.startMs) // null: trace too short to reach 30 m before the turn
    // Signal state unknown anywhere from the legal point (or the turn start) to the turn end → can't judge.
    if (signalUnknown(trace, c, legal)) return undecidable("이 구간의 방향지시등 기록이 없어요.", Law.SIGNAL)
    val on = turnSignals(trace, c, legal)
    val atStart = on.firstOrNull { it.start <= c.startMs }

    if (on.isEmpty()) return when (claim) {
        Claim.SIGNAL_EARLY_OFF -> undecidable("이번 회전에선 깜빡이를 아예 켜지 않았어요. '안 켰잖아!'로 판독해 보세요.", Law.SIGNAL)
        else -> Verdict(Outcome.HIT, Confidence.HIGH,
            "판독 결과… 깜빡이는 끝까지 꺼져 있었습니다. 훈수 적중!",
            "회전하는 동안 방향지시등 OFF.", Law.SIGNAL,
            legal?.let { "교차로 30\u00A0m 전은 핸들보다 약 ${sec(c.startMs - it)}\u2060초 전. 그때 켜기." } ?: "교차로 30\u00A0m 전에 켜기.")
    }

    return when (claim) {
        Claim.SIGNAL_MISSING -> Verdict(Outcome.REJECTED, Confidence.HIGH,
            "판독 결과… 깜빡이, 켜져 있었습니다. 훈수 기각!",
            "회전 구간에 방향지시등 ON 기록이 있어요.", Law.SIGNAL,
            "운전자 무죄. 다음엔 '딸깍' 소리부터 들어보기.")

        Claim.SIGNAL_LATE -> {
            if (atStart == null) {
                val late = on.first().start - c.startMs
                return Verdict(Outcome.HIT, Confidence.HIGH,
                    "판독 결과… 깜빡이는 핸들을 꺾고 나서 켜졌습니다. 훈수 적중!",
                    "회전 시작 ${sec(late)}\u2060초 뒤에 켰어요.", Law.SIGNAL,
                    "핸들보다 깜빡이가 먼저. 교차로 30\u00A0m 전에 켜기.")
            }
            if (legal == null) return undecidable(
                "회전 직전 기록이 30\u00A0m에 못 미쳐서 기준 지점을 알 수 없습니다.", Law.SIGNAL)
            val leadMs = c.startMs - atStart.start
            val distM = trace.between(atStart.start, c.startMs).distanceM()
            val neededMs = c.startMs - legal
            val evidence = "핸들보다 ${sec(leadMs)}\u2060초 먼저(약 ${distM.roundToInt()}\u00A0m 전) 켰어요. " +
                "30\u00A0m 전은 핸들보다 약 ${sec(neededMs)}\u2060초 전."
            if (atStart.start <= legal) Verdict(Outcome.REJECTED, Confidence.HIGH,
                "판독 결과… 깜빡이는 핸들보다 ${sec(leadMs)}\u2060초 먼저 켜졌습니다. 훈수 기각!", evidence, Law.SIGNAL,
                "운전자 무죄. 이 정도면 모범 답안이에요.")
            else Verdict(Outcome.HIT, Confidence.HIGH,
                "판독 결과… 깜빡이는 핸들보다 겨우 ${sec(leadMs)}\u2060초 먼저 켜졌습니다. 훈수 적중!", evidence, Law.SIGNAL,
                "30\u00A0m 전은 핸들보다 약 ${sec(neededMs)}\u2060초 전. 그때 켜기.")
        }

        else -> { // SIGNAL_EARLY_OFF
            if (atStart == null) return undecidable("회전을 시작할 때 깜빡이가 꺼져 있었어요. '늦었어!'로 판독해 보세요.", Law.SIGNAL)
            val early = c.endMs - atStart.end
            if (early > Thresholds.EARLY_OFF_TOLERANCE_MS) Verdict(Outcome.HIT, Confidence.MEDIUM,
                "판독 결과… 핸들이 돌아오기 ${sec(early)}\u2060초 전에 깜빡이가 꺼졌습니다. 훈수 적중!",
                "회전을 마치기 전에 OFF.", Law.SIGNAL, "회전을 마칠 때까지 켜 두기. 핸들이 돌아오면 저절로 꺼져요.")
            else Verdict(Outcome.REJECTED, Confidence.MEDIUM,
                "판독 결과… 깜빡이는 회전을 마칠 때까지 켜져 있었습니다. 훈수 기각!",
                "핸들이 돌아온 시점과 깜빡이 OFF가 거의 같아요.", Law.SIGNAL, "운전자 무죄.")
        }
    }
}

/** Whether a stop was required depends on pedestrians (camera) → always 판독 불가; the car only says if it stopped. */
private fun judgeRightTurnStop(c: Candidate, trace: List<Snapshot>): Verdict {
    val lookS = Thresholds.RIGHT_TURN_STOP_LOOKBACK_MS / 1000
    // Standing still right after P is setting off, not a stop before the turn: skip it. (A red-light wait has no P.)
    val w = trace.between(c.startMs - Thresholds.RIGHT_TURN_STOP_LOOKBACK_MS, c.startMs)
    val lastP = w.indexOfLast { it.gear == Gear.P }
    val trip = if (lastP < 0) w else w.drop(lastP + 1).dropWhile { it.speedKmh < Thresholds.STOPPED_KMH }
    val stopped = trip.any { it.speedKmh < Thresholds.STOPPED_KMH }
    return undecidable("회전 전 ${lookS}\u2060초 안에 완전히 멈춘 기록이 ${if (stopped) "있어요" else "없어요"}. " +
        "하지만 꼭 멈춰야 했는지(횡단보도의 보행자)는 카메라가 있어야 봅니다.", Law.PEDESTRIAN,
        "횡단보도에 사람이 있거나 건너려 하면 우회전 전에 일단 멈춤.")
}

/** B3 상식 깨기: standing still in D is what the manual describes for a short stop → 기각. */
private fun judgeStop(c: Candidate, trace: List<Snapshot>): Verdict {
    if (c.kind != CandidateKind.STOP_IN_D) return undecidable("D에 두고 멈춘 장면이 아니에요.", Law.STOP)
    return Verdict(Outcome.REJECTED, Confidence.HIGH,
        "판독 결과… D에 두고도 차는 얌전히 서 있었습니다. 훈수 기각!",
        "D(주행)에 둔 채 ${sec(c.endMs - c.startMs)}\u2060초 동안 멈춰 있었고, 차는 밀리지 않았어요.", Law.STOP,
        "짧은 신호 대기는 D에서 브레이크를 꾹. 오토홀드가 있으면 켜 두기.")
}

private fun judgeLaneChange(claim: Claim, c: Candidate, trace: List<Snapshot>): Verdict {
    if (claim == Claim.MISSED_GAP) return judgeGap(c, trace)
    val side = laneSide(c, trace)
    val tight = trace.between(c.startMs, c.endMs + Thresholds.CUT_IN_AFTER_MS)
        .any { it.blindSpot(side) == true && abs(it.steeringDeg) >= Thresholds.CUT_IN_STEER_DEG }
    return if (tight) Verdict(Outcome.HIT, Confidence.MEDIUM,
        "판독 결과… 옆 차가 있는데 핸들을 꺾었습니다. 훈수 적중!",
        "사각지대 경고(LCA)가 켜진 채로 핸들을 ${Thresholds.CUT_IN_STEER_DEG.roundToInt()}° 이상 꺾었어요.", Law.LANE,
        "경고등이 켜져 있으면 핸들은 잠깐 참기.")
    else undecidable("핸들을 꺾는 동안 옆 차로에 차가 있었다는 경고 기록이 없어요. 옆 차가 얼마나 가까웠는지는 차가 몰라요.", Law.LANE)
}

/** The side the car signalled to during a lane-change wait: the only blind spot that matters. */
internal fun laneSide(c: Candidate, trace: List<Snapshot>): Turn =
    trace.between(c.startMs, c.endMs).firstOrNull { it.turn.on }?.turn ?: Turn.OFF

/**
 * When the car started moving over: in the last [Thresholds.MERGE_WINDOW_MS] before the blinker went off, the wheel left
 * where it was at the start of that stretch by [Thresholds.LANE_STEER_DEG]. Else the wait's end. Only the last stretch,
 * so steering through a curve earlier in the wait is not taken for a merge.
 */
internal fun mergeAtMs(c: Candidate, trace: List<Snapshot>): Long {
    val w = trace.between(maxOf(c.startMs, c.endMs - Thresholds.MERGE_WINDOW_MS), c.endMs)
    val at = w.firstOrNull()?.steeringDeg ?: return c.endMs
    return w.firstOrNull { abs(it.steeringDeg - at) >= Thresholds.LANE_STEER_DEG }?.tMs ?: c.endMs
}

/**
 * Rear-side quiet stretches while waiting: the signalled side's LCA reported "no car" and the car was fast enough for the
 * radar. Only up to [mergeAtMs]: the time spent moving over is the merge itself, not a gap the driver let go by.
 */
internal fun laneGaps(c: Candidate, trace: List<Snapshot>): List<Span> {
    val side = laneSide(c, trace)
    return trace.between(c.startMs, mergeAtMs(c, trace)).spans { it.blindSpot(side) == false && it.speedKmh >= Thresholds.BSD_MIN_KMH }
}

/**
 * "아까 들어갔어야지!" vs "옆에 차가 있어서 못 들어갔다니까!": both know the exit was missed; what they don't know is
 * whether the next lane ever opened. The rear-side warning answers it: quiet ≥ [Thresholds.GAP_MIN_MS] before moving over = 적중,
 * never that long = 기각 (waiting was right, or the driver went in at the first chance). Made the exit anyway = 기각.
 * No warning ever / too slow for the radar = 판독 불가.
 */
private fun judgeGap(c: Candidate, trace: List<Snapshot>): Verdict {
    val o = exitOutcome(c, trace)
    if (o != null && !o.missed) return Verdict(Outcome.REJECTED, Confidence.MEDIUM,
        "판독 결과… 늦게 들어가도 빠질 길로 잘 빠졌습니다. 훈수 기각!",
        "깜빡이를 켰을 때 빠질 길까지 ${exitAheadM(trace, c.startMs, c.endMs)!!.roundToInt()} m 남아 있었고, 내비 경로를 벗어나지 않았어요.",
        Law.LANE, "운전자 무죄. 그래도 미리 옮겨 두면 둘 다 마음이 편해요.")
    val side = laneSide(c, trace)
    val w = trace.between(c.startMs, c.endMs)
    val missed = if (o?.missed == true) " 결국 빠질 길을 지나쳐 내비가 경로를 다시 찾았어요." +
        (rerouteCostSec(trace, o.atMs)?.takeIf { it >= 30 }?.let { " 내비 기준 도착이 ${duration(it)} 늦어졌어요." } ?: "") else ""
    // Undecidable still says what the car does know (the missed exit, the time lost).
    if (w.all { it.blindSpot(side) == null }) return undecidable(
        "이 차에서는 후측방 경고 기록을 받을 수 없어서 옆 차로가 비었는지 알 수 없어요.$missed", Law.GAP)
    if (w.none { it.blindSpot(side) == true }) return undecidable(
        "기다리는 동안 후측방 경고가 한 번도 켜지지 않았어요. 경고 기능이 꺼져 있었을 수도 있어서 옆 차로가 비었는지 알 수 없어요.$missed", Law.GAP)
    val slowMs = w.spans { it.speedKmh < Thresholds.BSD_MIN_KMH }.sumOf { it.ms }
    if (slowMs * 2 > c.endMs - c.startMs) return undecidable(
        "기다린 시간 대부분이 시속 20 km 아래였어요. 이 속도에서는 후측방 경고가 작동하지 않아요.$missed", Law.GAP)
    val gap = laneGaps(c, trace).maxByOrNull { it.ms }
    val mergeAt = mergeAtMs(c, trace)
    return if (gap != null && gap.ms >= Thresholds.GAP_MIN_MS) {
        val from = if (gap.start <= c.startMs) "깜빡이를 켠 순간부터" else "깜빡이를 켜고 ${secDown(gap.start - c.startMs)}⁠초 뒤부터"
        Verdict(Outcome.HIT, Confidence.MEDIUM,
            "판독 결과… 옆 차로에 들어갈 틈이 있었습니다. 훈수 적중!",
            "$from ${secDown(gap.ms)}⁠초 동안 옆 차로 뒤쪽이 비어 있었어요.$missed", Law.GAP,
            "미러 경고등이 꺼지면 눈으로 한 번 더 보고 들어가요.")
    } else if (mergeAt < c.endMs && o?.missed != true && w.lastOrNull { it.tMs <= mergeAt }?.blindSpot(side) == false) Verdict(Outcome.REJECTED, Confidence.MEDIUM,
        "판독 결과… 틈이 나자 바로 들어갔습니다. 훈수 기각!",
        "깜빡이를 켜고 ${secDown(mergeAt - c.startMs)}⁠초 만에 핸들을 꺾어 들어가기 시작했어요. 그 전에는 옆 차로 뒤쪽이 3⁠초 이상 빈 적이 없었어요.", Law.GAP,
        "운전자 무죄. 틈이 나면 바로 들어가는 게 모범 답안이에요.")
    else Verdict(Outcome.REJECTED, Confidence.MEDIUM,
        "판독 결과… 옆 차로가 계속 막혀 있었습니다. 훈수 기각!",
        "깜빡이를 켜고 기다린 ${secDown(mergeAt - c.startMs)}⁠초 동안 옆 차로 뒤쪽이 가장 길게 빈 게 ${secDown(gap?.ms ?: 0)}⁠초였어요.$missed", Law.GAP,
        "틈이 없으면 무리하지 않고 다음 길로 가는 게 맞아요.")
}

/** [missed] = rerouted at [atMs]; else the navi moved on to its next maneuver at [atMs] (the car made the exit). */
internal data class ExitOutcome(val missed: Boolean, val atMs: Long)

/** The ONE definition of "made it / missed it" — the verdict and the road replay both use it. null = no navi outcome. */
internal fun exitOutcome(c: Candidate, trace: List<Snapshot>): ExitOutcome? {
    if (exitAheadM(trace, c.startMs, c.endMs) == null) return null
    val after = trace.between(c.startMs, c.endMs + Thresholds.EXIT_OUTCOME_MS)
    val base = after.first().reroutes ?: 0
    val rerouteAt = after.firstOrNull { (it.reroutes ?: 0) > base }?.tMs
    val passedAt = after.zipWithNext().firstOrNull { (a, b) ->
        val d0 = a.nextManeuverM; val d1 = b.nextManeuverM
        d0 != null && d1 != null && d1 - d0 >= Thresholds.MANEUVER_PASSED_JUMP_M
    }?.second?.tMs
    return when {
        rerouteAt != null && (passedAt == null || rerouteAt <= passedAt) -> ExitOutcome(true, rerouteAt)
        passedAt != null -> ExitOutcome(false, passedAt)
        else -> null
    }
}

/** "약 3분" / "40초": how long, for people. */
internal fun duration(sec: Double): String = abs(sec).let { if (it >= 60) "약 ${(it / 60).roundToInt()}분" else "${it.roundToInt()}\u2060초" }

/** "이 길로 오면 돌아가잖아!": the navi's own arrival estimate before vs after the driver left the route. */
private fun judgeRoute(c: Candidate, trace: List<Snapshot>): Verdict {
    val cost = (if (c.kind == CandidateKind.ROUTE_CHANGE) rerouteCostSec(trace, c.endMs) else null)
        ?: return undecidable("내비 안내 중이 아니어서 예상 도착 시간을 비교할 수 없어요.", Law.ROUTE)
    val shown = cost.roundToInt() // the unit [duration] shows: under half a second either way reads as "the same"
    return if (cost >= Thresholds.ROUTE_COST_MIN_S) Verdict(Outcome.HIT, Confidence.MEDIUM,
        "판독 결과… 내비 기준 도착이 ${duration(cost)} 늦어졌습니다. 훈수 적중!",
        "경로를 벗어나기 전 예상 도착 시각보다 ${duration(cost)} 늦어졌어요.", Law.ROUTE,
        "다른 길로 가고 싶을 땐 먼저 내비에서 그 길의 도착 시간을 확인해 봐요.")
    else Verdict(Outcome.REJECTED, Confidence.MEDIUM,
        when {
            shown < 0 -> "판독 결과… 내비 기준 오히려 ${duration(cost)} 빨라졌습니다. 훈수 기각!"
            shown == 0 -> "판독 결과… 내비 기준 도착 시간이 같았습니다. 훈수 기각!"
            else -> "판독 결과… 내비 기준 1분도 늦지 않았습니다. 훈수 기각!"
        },
        when {
            shown < 0 -> "경로를 벗어난 뒤 예상 도착 시각이 ${duration(cost)} 앞당겨졌어요."
            shown == 0 -> "경로를 벗어나기 전과 뒤의 예상 도착 시각이 같았어요."
            else -> "경로를 벗어난 뒤 예상 도착 시각이 ${duration(cost)}만 늦어졌어요."
        }, Law.ROUTE,
        "운전자 무죄. 돌아가는 것 같아도 내비 기준으로는\u00A0비슷했어요.")
}

private fun judgeWindow(claim: Claim, w: List<Snapshot>): Verdict {
    val t = Thresholds
    return when (claim) {
        Claim.TOO_FAST -> {
            val limited = w.filter { it.limitKmh() != null }
            if (limited.isEmpty()) return undecidable("이 구간의 제한속도 정보가 없어요(내비 안내 없음).", Law.SPEED)
            val over = w.spans { s -> s.limitKmh()?.let { s.speedKmh - it >= t.OVERSPEED_MARGIN_KMH } == true }
                .maxByOrNull { it.ms }
            if (over != null && over.ms >= t.OVERSPEED_MIN_MS) {
                val during = w.between(over.start, over.end)
                Verdict(Outcome.HIT, Confidence.MEDIUM,
                    "판독 결과… 제한 ${during.first().limitKmh()}\u00A0km 구간에서 시속 ${during.maxOf { it.speedKmh }.roundToInt()}\u00A0km. 훈수 적중!",
                    "제한속도보다 10\u00A0km 이상 빠르게 ${sec(over.ms)}\u2060초 동안 달렸어요.", Law.SPEED,
                    if (during.any { it.schoolZone }) "어린이보호구역은 시속 30\u00A0km. 표지판 보이면 발부터 떼기." else "제한속도 표지판 한 번 더 보기.")
            } else Verdict(Outcome.REJECTED, Confidence.MEDIUM,
                "판독 결과… 최고 시속 ${limited.maxOf { it.speedKmh }.roundToInt()}\u00A0km, 제한 ${limited.minOf { it.limitKmh()!! }}\u00A0km 안쪽입니다. 훈수 기각!",
                "제한속도보다 10\u00A0km 이상 빠르게 3\u2060초 이상 달린 구간이 없어요.", Law.SPEED, "운전자 무죄. 체감 속도와 실제 속도는 달라요.")
        }

        Claim.BRAKE_HARSH -> {
            val decel = w.spans { it.brake }.maxOfOrNull { maxDecelMs2(w.between(it.start, it.end)) } ?: 0.0
            if (decel >= t.HARSH_DECEL_MS2) undecidable(
                "급감속(최대 ${"%.1f".format(decel)}\u00A0m/s²)은 맞아요. 하지만 앞차 때문이었는지는 차가 모릅니다.", Law.BRAKE,
                "정당한 급제동이었는지는 두 분이 합의하기!")
            else Verdict(Outcome.REJECTED, Confidence.MEDIUM,
                "판독 결과… 브레이크는 부드러웠습니다. 훈수 기각!",
                "최대 감속 ${"%.1f".format(decel)}\u00A0m/s². 급제동 기준(${"%.0f".format(t.HARSH_DECEL_MS2)}\u00A0m/s²)에 못 미쳐요.", Law.BRAKE, "운전자 무죄.")
        }

        Claim.LIGHTS_IN_RAIN -> {
            if (w.none { it.wiperOn == true }) return undecidable("와이퍼가 꺼져 있어서 비가 왔는지 차가 몰라요.", Law.LIGHTS)
            if (w.any { it.wiperOn == true && it.headlightOn == null }) return undecidable("전조등 상태 기록이 없어요.", Law.LIGHTS)
            val dark = w.spans { it.wiperOn == true && it.headlightOn == false && it.speedKmh > 0 }.maxByOrNull { it.ms }
            if (dark != null && dark.ms >= t.RAIN_NO_LIGHT_MIN_MS) Verdict(Outcome.HIT, Confidence.HIGH,
                "판독 결과… 와이퍼는 열일, 전조등은 휴무. 훈수 적중!",
                "와이퍼가 도는데 전조등이 ${sec(dark.ms)}\u2060초 동안 꺼져 있었어요.", Law.LIGHTS, "와이퍼 켜면 라이트도 같이.")
            else Verdict(Outcome.REJECTED, Confidence.HIGH,
                "판독 결과… 비 올 때 전조등은 켜져 있었습니다. 훈수 기각!",
                "와이퍼 작동 중 전조등 OFF가 30\u2060초를 넘지 않았어요.", Law.LIGHTS, "운전자 무죄.")
        }

        else -> { // SEATBELT
            if (w.all { it.unbeltedSeats == null }) return undecidable("이 차는 좌석별 안전벨트 정보를 주지 않아요.", Law.BELT)
            val loose = w.spans { (it.unbeltedSeats ?: 0) > 0 && it.speedKmh > 0 }.maxByOrNull { it.ms }
            if (loose != null && loose.ms >= t.UNBELTED_MIN_MS) Verdict(Outcome.HIT, Confidence.MEDIUM,
                "판독 결과… 누군가 벨트 없이 달렸습니다. 훈수 적중!",
                "탑승한 좌석의 벨트 미착용으로 ${sec(loose.ms)}\u2060초 주행.", Law.BELT, "출발 전에 '벨트 확인!' 한 번 외치기.")
            else Verdict(Outcome.REJECTED, Confidence.MEDIUM,
                "판독 결과… 모두 벨트를 매고 있었습니다. 훈수 기각!",
                "주행 중 미착용 좌석이 없어요.", Law.BELT, "운전자 무죄.")
        }
    }
}

/** Season record. 무죄율 = 기각 / 판정, 적중률 = 적중 / 판정 (판독 불가는 빼고). */
data class Season(val hits: Int = 0, val rejects: Int = 0, val undecided: Int = 0) {
    fun plus(o: Outcome) = when (o) {
        Outcome.HIT -> copy(hits = hits + 1)
        Outcome.REJECTED -> copy(rejects = rejects + 1)
        Outcome.UNDECIDABLE -> copy(undecided = undecided + 1)
    }
    private val decided get() = hits + rejects
    val innocenceRate get() = if (decided == 0) 0.0 else rejects.toDouble() / decided
    val hitRate get() = if (decided == 0) 0.0 else hits.toDouble() / decided
}
