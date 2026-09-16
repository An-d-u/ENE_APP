package dev.ene.companion

import dev.ene.companion.audio.PlaybackClock
import org.junit.Assert.*
import org.junit.Test

class PlaybackClockTest {
    @Test fun actualHeadCannotExceedWrittenFramesOrMoveBackwards() {
        val clock = PlaybackClock("player_a")
        assertEquals(100L, clock.sample("player_a", 100, 200))
        assertNull(clock.sample("player_a", 99, 200))
        assertNull(clock.sample("player_a", 201, 200))
        assertEquals(150L, clock.sample("player_a", 150, 200))
    }

    @Test fun unsignedWrapExpandsToLongWithoutAcceptingAnOrdinaryReset() {
        val clock = PlaybackClock("player_a")
        assertEquals(4294967294L, clock.sample("player_a", -2, 4294967300L))
        assertEquals(4294967298L, clock.sample("player_a", 2, 4294967300L))
        assertNull(clock.sample("player_a", 1, 4294967300L))
    }

    @Test fun newUtteranceResetsClockAndOldPlayerCannotAdvanceIt() {
        val clock = PlaybackClock("player_a")
        clock.sample("player_a", 100, 100)
        clock.reset("player_b")
        assertNull(clock.sample("player_a", 100, 100))
        assertEquals(0L, clock.sample("player_b", 0, 0))
        assertEquals(10L, clock.sample("player_b", 10, 10))
    }
}
