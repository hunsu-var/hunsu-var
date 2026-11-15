package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기획 §8: 4주 시즌, 무죄율/적중률, 깜빡이 평균 시점 주차별, 주간 명판정, 100일 졸업 리포트. */
class RecordTest {
    private val now = 1_800_000_000_000L
    private fun judged(daysAgo: Double, o: Outcome, solo: Boolean = false, headline: String = "h") =
        Judged(now - (daysAgo * DAY_MS).toLong(), solo, Claim.SIGNAL_LATE, o, Confidence.HIGH, headline, "law", "tip")

    @Test fun `habits keep each turn's signal lead, each harsh brake, and rule slips`() {
        val t = SignalRecorder().also { demoScenario().replayInto(it) }.snapshots()
        val h = habitsOf(t, detectCandidates(t))
        assertEquals(listOf(5_000L, 500L), h.filter { it.kind == CandidateKind.TURN }.map { it.leadMs })
        assertEquals(1, h.count { it.kind == CandidateKind.HARSH_BRAKE }) // the gentle BRAKE is not a 급제동
        assertEquals(listOf(false, true), h.filter { it.kind == CandidateKind.TURN }.map { it.slip }) // 0.5 s = late
        assertEquals(setOf(CandidateKind.TURN, CandidateKind.OVERSPEED, CandidateKind.RAIN_NO_LIGHTS), h.filter { it.slip }.map { it.kind }.toSet())
        assertEquals(5, h.size)
    }

    @Test fun `no records - season 1, day 1, 100 days to graduation`() {
        val r = seasonReport(Records(), now)
        assertEquals(1, r.number); assertEquals(1, r.day)
        assertEquals(listOf(Week(null, 0)), r.weeks)
        assertNull(r.best); assertNull(r.graduation)
        assertEquals(GRADUATION_DAYS, r.daysToGraduation)
    }

    @Test fun `a new season starts every 28 days and only counts its own verdicts`() {
        val r = seasonReport(Records(judged = listOf(judged(30.0, Outcome.REJECTED), judged(1.0, Outcome.HIT))), now)
        assertEquals(2, r.number)
        assertEquals(3, r.day) // season 2 began 2 days ago
        assertEquals(Season(hits = 1), r.record)
    }

    @Test fun `best of the week prefers a 기각, then the latest, and skips 판독 불가`() {
        val r = seasonReport(Records(judged = listOf(
            judged(9.0, Outcome.REJECTED, headline = "old"),
            judged(3.0, Outcome.REJECTED, headline = "rejected"),
            judged(1.0, Outcome.HIT, headline = "hit"),
            judged(0.5, Outcome.UNDECIDABLE, headline = "draw"),
        )), now)
        assertEquals("rejected", r.best?.headline)
        assertEquals("hit", seasonReport(Records(judged = listOf(judged(1.0, Outcome.HIT, headline = "hit"))), now).best?.headline)
    }

    @Test fun `example season backs the deck - 무죄율 72%, 깜빡이 0_9 to 1_9 s, 차 상대 7승 3패, 졸업 리포트`() {
        val r = seasonReport(exampleRecords(now), now)
        assertEquals(4, r.number); assertEquals(28, r.day)
        assertEquals(72, (r.record.innocenceRate * 100).toInt())
        assertEquals(listOf(900L, 1_200L, 1_600L, 1_900L), r.weeks.map { it.avgLeadMs })
        assertEquals(listOf(5, 4, 2, 1), r.weeks.map { it.harshBrakes })
        assertEquals(Season(hits = 3, rejects = 7), r.vsCar)
        assertTrue(r.best!!.headline, r.best!!.headline.contains("핸들은 거의 그대로"))
        val g = requireNotNull(r.graduation)
        assertEquals(Week(300, 6), g.first)
        assertEquals(Week(1_900, 1), g.last)
        assertEquals(0, r.daysToGraduation)
    }

    @Test fun `이의 있음 names the losing seat and never changes the verdict`() {
        assertEquals("운전석", loserSeat(Outcome.HIT, solo = false))
        assertEquals("조수석", loserSeat(Outcome.REJECTED, solo = false))
        assertEquals("운전석", loserSeat(Outcome.HIT, solo = true))
        assertNull(loserSeat(Outcome.REJECTED, solo = true)) // the car lost: it has no say
        assertNull(loserSeat(Outcome.UNDECIDABLE, solo = false)) // nobody lost
        val j = judged(1.0, Outcome.HIT)
        val objected = j.copy(excuse = Excuse.CAR_AHEAD_STOPPED)
        assertEquals("운전석 사정: 앞차가 갑자기 섰어요", objected.excuseLine)
        assertNull(j.excuseLine)
        assertEquals(Records(listOf(objected)), Records(listOf(j)).update(objected))
        assertEquals(seasonReport(Records(listOf(j)), now).record, seasonReport(Records(listOf(objected)), now).record)
        assertEquals(listOf("앞차가 갑자기 섰어요", "옆 차로에 차가 있었어요", "보행자·자전거가 있었어요", "길을 잘못 들었어요", "그냥 억울해요"), Excuse.values().map { it.label })
    }

    @Test fun `둘 다 인정 is counted per season, solo verdicts aside`() {
        val r = seasonReport(Records(judged = listOf(
            judged(2.0, Outcome.HIT).copy(agreed = true),
            judged(1.5, Outcome.UNDECIDABLE).copy(agreed = true),
            judged(1.0, Outcome.REJECTED),
            judged(0.5, Outcome.HIT, solo = true).copy(agreed = true),
        )), now)
        assertEquals(2, r.agreed)
        assertEquals(Season(hits = 1, rejects = 1, undecided = 1), r.record) // agreeing changes no outcome
        assertEquals(12, seasonReport(exampleRecords(now), now).agreed)
    }

    @Test fun `records survive JSON and repeated habits are kept once`() {
        val rec = Records(judged = listOf(judged(1.0, Outcome.HIT).copy(excuse = Excuse.JUST_UNFAIR, agreed = true)),
            habits = listOf(Habit(5, CandidateKind.TURN, 1_000)))
        assertEquals(rec, recordsFromJson(rec.toJson()))
        assertEquals(Records(), recordsFromJson(null))
        assertEquals(rec, rec.withHabits(listOf(Habit(5, CandidateKind.TURN, 1_000))))
    }

    @Test fun `judging the same scene again replaces the verdict instead of counting it twice`() {
        val first = judged(1.0, Outcome.HIT).copy(scene = 42)
        val again = judged(0.5, Outcome.HIT).copy(scene = 42)
        assertEquals(listOf(again), Records().withJudged(first).withJudged(again).judged)
        val otherClaim = again.copy(claim = Claim.SIGNAL_MISSING)
        assertEquals(2, Records().withJudged(first).withJudged(otherClaim).judged.size)
        assertEquals(2, Records().withJudged(judged(1.0, Outcome.HIT)).withJudged(judged(0.5, Outcome.HIT)).judged.size) // old records: no scene
    }

    @Test fun `unreadable records start empty instead of crashing`() {
        assertEquals(Records(), recordsFromJson("{broken"))
        assertEquals(Records(), recordsFromJson("""{"judged":[{"tMs":1,"solo":false,"claim":"NO_SUCH_CLAIM","outcome":"HIT","confidence":"HIGH","headline":"h","law":"l","tip":"t"}]}"""))
    }
}
