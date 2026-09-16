package dev.ene.companion

import dev.ene.companion.character.CharacterPlaybackClock
import dev.ene.companion.protocol.CharacterPlayback
import org.junit.Assert.*
import org.junit.Test

class CharacterPlaybackTest {
    private fun update(output: String = "pc", position: Long = 120, mouth: Double = .6, active: Boolean = true) =
        CharacterPlayback(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(6), output, position, mouth, active)

    @Test fun pcPositionIsNotAdvancedByWallClockAndMouthExpiresAfter750ms() {
        val clock = CharacterPlaybackClock()
        clock.remote(update(), 1000)
        assertEquals(120L, clock.current(1500).playedMs)
        assertEquals(.6, clock.current(1749).mouthOpen, .0001)
        assertEquals(0.0, clock.current(1750).mouthOpen, .0001)
        assertFalse(clock.current(1750).active)
    }

    @Test fun phoneUsesLocalConsumedPcmNotEchoedProgress() {
        val clock = CharacterPlaybackClock()
        clock.remote(update("phone"), 1000)
        assertEquals(0.0, clock.current(1000).mouthOpen, .0001)
        clock.local(update("phone", 200, .3), 1000)
        clock.remote(update("phone", 100, .9), 1100)
        clock.remote(update("pc", 100, .9), 1100)
        assertEquals(200L, clock.current(1100).playedMs)
        assertEquals(.3, clock.current(1100).mouthOpen, .0001)
        clock.local(update("phone", 200, 0.0, false), 1200)
        assertFalse(clock.current(1200).active)
        clock.remote(update("pc", 250, .9), 1250)
        assertEquals(0.0, clock.current(1250).mouthOpen, .0001)
    }

    @Test fun sameUtteranceRegressionAndEndCannotReopenMouth() {
        val clock = CharacterPlaybackClock()
        clock.remote(update(position = 300), 1000)
        clock.remote(update(position = 100, mouth = 1.0), 1100)
        assertEquals(300L, clock.current(1100).playedMs)
        clock.remote(update(position = 300, active = false), 1200)
        clock.remote(update(position = 400), 1300)
        assertFalse(clock.current(1300).active)
        clock.reset()
        assertEquals(0.0, clock.current(1300).mouthOpen, .0001)
    }

    @Test fun olderUtteranceEndCannotCloseNewPlaybackAndIdleAnnouncementDoesNotRetireFutureStart() {
        val clock = CharacterPlaybackClock()
        clock.remote(update(output = "none", active = false), 10)
        clock.remote(update(), 20)
        assertTrue(clock.current(20).active)
        clock.remote(update().copy(utterance_id = audioId(7)), 30)
        clock.remote(update(active = false), 40)
        assertTrue(clock.current(40).active)
        assertEquals(audioId(7), clock.current(40).utteranceId)
    }

    @Test fun lateLocalCompletionCannotCloseDifferentCurrentOutput() {
        val clock = CharacterPlaybackClock()
        clock.local(update("phone").copy(utterance_id = audioId(7)), 10)
        clock.local(update("phone", active = false), 20)
        assertEquals(audioId(7), clock.current(20).utteranceId)
        assertTrue(clock.current(20).active)
        clock.reset()
        clock.remote(update().copy(utterance_id = audioId(7)), 30)
        clock.local(update("phone", active = false), 40)
        assertEquals(audioId(7), clock.current(40).utteranceId)
    }
}
