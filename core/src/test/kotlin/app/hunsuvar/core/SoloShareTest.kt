package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SoloShareTest {
    @Test fun `car claim is the first claim for every kind`() =
        CandidateKind.values().forEach { assertEquals(claimsFor(it).first(), carClaimFor(it)) }

    @Test fun `share headline drops speed readings but keeps clean headlines`() {
        val v = Verdict(Outcome.HIT, Confidence.MEDIUM, "제한 30 km 구간에서 시속 45 km. 훈수 적중!", "e", "l", "t")
        assertFalse("km" in v.shareHeadline)
        assertEquals("판독 결과… 훈수 적중!", v.shareHeadline)
        assertEquals("깜빡이는 늦었어요", v.copy(headline = "깜빡이는 늦었어요").shareHeadline)
    }
}
