package dev.ene.companion

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ene.companion.audio.AndroidPcmSinkFactory
import dev.ene.companion.audio.PcmPlayer
import dev.ene.companion.audio.speechFocusRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 현재 단계에서는 계측 APK 컴파일만 한다. 실제 장치 재생 시험은 별도 수용 절차다. */
@RunWith(AndroidJUnit4::class)
class AudioPlaybackTest {
    @Test fun nativePrefillWithoutStartDoesNotConsumeAudio() {
        val player = PcmPlayer(24000, 1, AndroidPcmSinkFactory())
        try {
            assertEquals("accepted", player.offer(ByteArray(9600)))
            val sample = player.pump()
            assertTrue(player.ready)
            assertEquals(0L, sample.playedFrames)
            assertTrue(player.writtenFrames > 0)
            player.stop()
            assertFalse(player.start())
        } finally { player.close() }
    }

    @Test fun focusRequestIsTransientSpeechAndNeverWaitsForDelayedGain() {
        val request = speechFocusRequest({}, Handler(Looper.getMainLooper()))
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.focusGain)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        assertFalse(request.acceptsDelayedFocusGain())
        assertTrue(request.willPauseWhenDucked())
    }
}
