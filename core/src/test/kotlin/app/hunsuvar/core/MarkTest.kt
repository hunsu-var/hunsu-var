package app.hunsuvar.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkTest {
    private val turn = Candidate(CandidateKind.TURN, 10_000, 14_000)
    private val brake = Candidate(CandidateKind.BRAKE, 16_000, 18_000)

    @Test fun tapAfterTheMomentPointsAtIt() {
        assertEquals(Marked(setOf(turn)), markCandidates(listOf(turn), listOf(30_000)))
    }

    @Test fun tapDuringTheMomentCounts() {
        assertEquals(Marked(setOf(turn)), markCandidates(listOf(turn), listOf(12_000)))
    }

    @Test fun latestEndingCandidateWins() {
        assertEquals(Marked(setOf(brake)), markCandidates(listOf(turn, brake), listOf(20_000)))
    }

    @Test fun tooLateOrTooEarlyIsAStray() {
        assertEquals(Marked(strays = listOf(5_000, 40_000)), markCandidates(listOf(turn), listOf(5_000, 40_000)))
    }

    @Test fun marksNeedConsentAndGoWithIt() {
        val r = SignalRecorder()
        r.mark(1_000)
        assertEquals(listOf(1_000L), r.marks())
        r.recording = false
        assertEquals(emptyList<Long>(), r.marks())
        r.mark(2_000)
        assertEquals(emptyList<Long>(), r.marks())
    }
}
