package app.hunsuvar.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Season record (기획 §8): 4-week seasons, weekly signal habit, best verdict of the week, 100-day graduation report.
// Everything is derived from two device-only logs; no counters to reset.

const val DAY_MS = 86_400_000L
const val SEASON_DAYS = 28
const val GRADUATION_DAYS = 100
private const val WEEK_MS = 7 * DAY_MS

/**
 * One finished 판정. [headline] is already share-safe (no speed readings), so the 명판정 card can be posted as-is.
 * [excuse] (이의 있음) and [agreed] (둘 다 인정) are what the people said afterwards; they never change [outcome].
 * [scene] = the judged scene's start (candidate.startMs); null for records saved before it existed.
 */
@Serializable
data class Judged(
    val tMs: Long, val solo: Boolean, val claim: Claim, val outcome: Outcome,
    val confidence: Confidence, val headline: String, val law: String, val tip: String,
    val excuse: Excuse? = null, val agreed: Boolean = false, val scene: Long? = null,
)

/** 이의 있음: the losing side's circumstance, from a fixed list (giving people a say raises perceived fairness). */
enum class Excuse(val label: String) {
    CAR_AHEAD_STOPPED("앞차가 갑자기 섰어요"),
    SIDE_CAR("옆 차로에 차가 있었어요"),
    PEDESTRIAN("보행자·자전거가 있었어요"),
    WRONG_WAY("길을 잘못 들었어요"),
    JUST_UNFAIR("그냥 억울해요"),
}

/** Who lost and may object. 적중 = the driver; 기각 = the 훈수꾼 (in solo mode that is the car, which has no say). */
fun loserSeat(o: Outcome, solo: Boolean): String? = when (o) {
    Outcome.HIT -> "운전석"
    Outcome.REJECTED -> if (solo) null else "조수석"
    Outcome.UNDECIDABLE -> null
}

/** "운전석 사정: 앞차가 갑자기 섰어요" — shown under the verdict, never part of it. */
val Judged.excuseLine: String? get() = excuse?.let { e -> loserSeat(outcome, solo)?.let { "$it 사정: ${e.label}" } }

fun Verdict.toJudged(tMs: Long, claim: Claim, solo: Boolean, scene: Long? = null) =
    Judged(tMs, solo, claim, outcome, confidence, shareHeadline, law, tip, scene = scene)
fun Judged.toVerdict() = Verdict(outcome, confidence, headline, evidence = "", law = law, tip = tip)

/**
 * Habit sample logged at P: a turn's signal lead (ms before the wheel, negative = after), one harsh brake, or a rule slip.
 * [slip] = a rule people often miss was missed (late/no signal, speeding, no lights in rain, unbelted) → the season's rule note.
 */
@Serializable
data class Habit(val tMs: Long, val kind: CandidateKind, val leadMs: Long? = null, val slip: Boolean = false)

/** ponytail: whole history in one JSON string (SharedPreferences); ~100 B per entry. Move to a file if it passes ~1 MB. */
@Serializable
data class Records(val judged: List<Judged> = emptyList(), val habits: List<Habit> = emptyList()) {
    /** Judging the same scene with the same claim again replaces the earlier verdict: one argument, counted once. */
    fun withJudged(j: Judged) = copy(judged = judged.filterNot { j.scene != null && it.scene == j.scene && it.claim == j.claim } + j)
    /** Replace the verdict logged at the same time (이의 있음 / 둘 다 인정 come after the verdict is saved). */
    fun update(j: Judged) = copy(judged = judged.map { if (it.tMs == j.tMs) j else it })
    /** The same trip can be seen at more than one P (P→D→P without leaving the trip): keep each moment once. */
    fun withHabits(new: List<Habit>) = copy(habits = (habits + new).distinctBy { it.tMs to it.kind })
}

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
fun Records.toJson(): String = json.encodeToString(Records.serializer(), this)
/** Unreadable (corrupted, or written by a version with other enum values) = start empty rather than crash on every launch. */
fun recordsFromJson(s: String?): Records = s?.let { runCatching { json.decodeFromString(Records.serializer(), it) }.getOrNull() } ?: Records()

/** Turns (signal lead, late/missing = slip), harsh brakes, and rule slips. Not verdicts: nobody argued about these. */
fun habitsOf(trace: List<Snapshot>, candidates: List<Candidate>): List<Habit> = candidates.mapNotNull { c ->
    when (c.kind) {
        CandidateKind.TURN -> {
            val lead = signalLeadMs(trace, c)
            val slip = judge(Claim.SIGNAL_LATE, c, trace).outcome == Outcome.HIT
            if (lead == null && !slip) null else Habit(c.startMs, c.kind, lead, slip)
        }
        CandidateKind.HARSH_BRAKE -> Habit(c.startMs, c.kind)
        CandidateKind.OVERSPEED, CandidateKind.RAIN_NO_LIGHTS, CandidateKind.UNBELTED -> Habit(c.startMs, c.kind, slip = true)
        else -> null
    }
}

/** 깜이의 규칙 노트: one rule people often miss, how often it happened this season, the law and what to do. */
data class RuleNote(val kind: CandidateKind, val count: Int, val line: String, val law: String, val tip: String)

private fun ruleNote(kind: CandidateKind, n: Int): RuleNote? = when (kind) {
    CandidateKind.TURN -> RuleNote(kind, n, "깜빡이를 늦게 켜거나 안 켠 회전이 ${n}번 있었어요.", Law.SIGNAL, "교차로 30\u00A0m 전, 시속 60\u00A0km면 핸들보다 2초쯤 먼저 켜요.")
    CandidateKind.OVERSPEED -> RuleNote(kind, n, "제한속도보다 시속 10\u00A0km 이상 빠르게 달린 구간이 ${n}번 있었어요.", Law.SPEED, "어린이보호구역은 시속 30\u00A0km. 표지판이 보이면 발부터 떼요.")
    CandidateKind.RAIN_NO_LIGHTS -> RuleNote(kind, n, "비 오는데 전조등을 끈 채 달린 게 ${n}번 있었어요.", Law.LIGHTS, "와이퍼를 켜면 전조등도 같이 켜요.")
    CandidateKind.UNBELTED -> RuleNote(kind, n, "벨트를 안 맨 좌석이 있는 채로 달린 게 ${n}번 있었어요.", Law.BELT, "출발 전에 '벨트 확인!' 한 번 외쳐요.")
    else -> null
}

/** [avgLeadMs]: average signal lead of the week's turns (null = no turn). */
data class Week(val avgLeadMs: Long?, val harshBrakes: Int)
data class Graduation(val first: Week, val last: Week, val judged: Int, val innocenceRate: Double)
data class SeasonReport(
    val number: Int, val day: Int,          // 시즌 N, D일째 (1..28)
    val record: Season, val vsCar: Season,  // 훈수꾼 vs 운전자, 혼자 모드의 차 vs 운전자
    val agreed: Int,                        // 인정한 판정 (둘 다 인정), 혼자 모드 제외
    val weeks: List<Week>,                  // this season's weeks so far (1..4)
    val best: Judged?,                      // 이번 주 명판정
    val graduation: Graduation?,            // null until GRADUATION_DAYS
    val daysToGraduation: Int,
    val rules: List<RuleNote> = emptyList(), // this season's rule slips, most frequent first
)

/** Seasons are 28-day blocks counted from the first record ever; weeks are 7-day blocks inside a season. */
fun seasonReport(r: Records, nowMs: Long): SeasonReport {
    val first = (r.judged.map { it.tMs } + r.habits.map { it.tMs }).minOrNull() ?: nowMs
    val seasonMs = SEASON_DAYS * DAY_MS
    val index = ((nowMs - first) / seasonMs).toInt()
    val start = first + index * seasonMs
    fun week(from: Long, to: Long): Week {
        val h = r.habits.filter { it.tMs in from until to }
        val leads = h.filter { it.kind == CandidateKind.TURN }.mapNotNull { it.leadMs }
        return Week(if (leads.isEmpty()) null else leads.average().toLong(), h.count { it.kind == CandidateKind.HARSH_BRAKE })
    }
    fun Iterable<Judged>.season() = fold(Season()) { s, j -> s.plus(j.outcome) }
    val inSeason = r.judged.filter { it.tMs in start..nowMs }
    val days = ((nowMs - first) / DAY_MS).toInt()
    val all = r.judged.filter { !it.solo }
    return SeasonReport(
        number = index + 1,
        day = ((nowMs - start) / DAY_MS).toInt() + 1,
        record = inSeason.filter { !it.solo }.season(),
        vsCar = inSeason.filter { it.solo }.season(),
        agreed = inSeason.count { !it.solo && it.agreed },
        weeks = (0..((nowMs - start) / WEEK_MS).toInt()).map { w -> week(start + w * WEEK_MS, start + (w + 1) * WEEK_MS) },
        best = r.judged.filter { it.tMs > nowMs - WEEK_MS && it.outcome != Outcome.UNDECIDABLE }
            .maxWithOrNull(compareBy({ it.outcome == Outcome.REJECTED }, { it.tMs })), // 기각 = the big laugh (§3)
        graduation = if (days < GRADUATION_DAYS) null else
            Graduation(week(first, first + WEEK_MS), week(nowMs - WEEK_MS + 1, nowMs + 1), all.size, all.season().innocenceRate),
        daysToGraduation = maxOf(0, GRADUATION_DAYS - days),
        rules = r.habits.filter { it.slip && it.tMs in start..nowMs }.groupingBy { it.kind }.eachCount()
            .entries.sortedByDescending { it.value }.mapNotNull { (k, n) -> ruleNote(k, n) },
    )
}

/**
 * Example season for the PT (labelled 예시 on screen): 111 days of use, the 4th season just ending.
 * Season weeks: signal 0.9 → 1.2 → 1.6 → 1.9 s before the wheel, harsh brakes 5 → 4 → 2 → 1.
 * Season verdicts: 13 기각 · 5 적중 · 3 판독 불가 (무죄율 72%), 12 of them 둘 다 인정; vs the car 7승 3패.
 * Verdict texts come from the demo trip.
 */
fun exampleRecords(nowMs: Long): Records {
    val first = nowMs - 111 * DAY_MS
    val habits = (0 until 16).flatMap { w ->
        val at = first + w * WEEK_MS
        val lead = if (w >= 12) listOf(900L, 1_200L, 1_600L, 1_900L)[w - 12] else 300L + 50 * w
        val brakes = if (w >= 12) listOf(5, 4, 2, 1)[w - 12] else 6
        listOf(0, 2, 4).map { d -> Habit(at + d * DAY_MS, CandidateKind.TURN, lead, slip = lead < 1_800) } + // the very first habit = day 0
            (0 until brakes).map { i -> Habit(at + DAY_MS + i * 3_600_000L, CandidateKind.HARSH_BRAKE) } +
            (if (w >= 13) listOf(Habit(at + 3 * DAY_MS, CandidateKind.RAIN_NO_LIGHTS, slip = true)) else emptyList())
    }
    val trip = SignalRecorder().also { demoScenario().replayInto(it) }.snapshots()
    val demo = varCandidates(trip).flatMap { c -> claimsFor(c.kind).map { claim -> claim to judge(claim, c, trip) } }
    fun pick(o: Outcome) = demo.filter { it.second.outcome == o }
    val signal2s = demo.first { it.first == Claim.HOLD_STRAIGHT } // 주차: "핸들 똑바로!" → 거의 그대로 → 기각 (the 명판정)
    fun log(o: Outcome, n: Int, solo: Boolean, offsetH: Int) = (0 until n).map { i ->
        val (claim, v) = pick(o)[i % pick(o).size]
        v.toJudged(nowMs - (2 + i) * DAY_MS - offsetH * 3_600_000L, claim, solo).copy(agreed = !solo && i % 2 == 0)
    }
    val judged = log(Outcome.REJECTED, 12, false, 1) + log(Outcome.HIT, 5, false, 2) + log(Outcome.UNDECIDABLE, 3, false, 3) +
        log(Outcome.REJECTED, 7, true, 4) + log(Outcome.HIT, 3, true, 5) +
        signal2s.second.toJudged(nowMs - 3_600_000, signal2s.first, solo = false).copy(agreed = true)
    return Records(judged.sortedBy { it.tMs }, habits)
}
