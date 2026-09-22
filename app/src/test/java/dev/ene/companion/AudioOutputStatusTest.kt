package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import dev.ene.companion.ui.audioOutputDescription
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioOutputStatusTest {
    @Test fun oldPcDoesNotImplyPcPlayback() = runTest {
        val f = AudioFixture(this)
        try {
            f.activate()
            f.extension.receive(AudioStatus(1, audioId(1), audioId(2), "auto", "ready"))
            assertNull(f.outputs.last().output)
            assertFalse(audioOutputDescription(f.outputs.last()).contains("PC 재생 중"))
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun localFailureSurvivesUnchangedAvailabilityAndShutdownWins() = runTest {
        val f = AudioFixture(this)
        try {
            f.platform.deny = true
            f.activate(); f.extension.receive(f.offer()); runCurrent()
            f.media.single().chunks.send(ByteArray(9600)); runCurrent()
            assertEquals("focus_denied", f.outputs.last().reason)
            f.extension.baseState(audioId(1), audioId(3), true)
            assertEquals("focus_denied", f.outputs.last().reason)
            f.extension.shutdown()
            assertEquals(AudioOutputStatus(output = "none", state = "stopped", reason = "connection_closed"), f.outputs.last())
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun falseAvailabilityStillReportsDifferentReason() = runTest {
        val f = AudioFixture(this)
        try {
            f.extension.receive(ExtensionsReady(1, audioId(1), audioId(2), listOf("audio_pcm_v1")))
            f.extension.resumed(true)
            assertEquals(listOf("inactive", "syncing"), f.sent.filterIsInstance<AudioAvailability>().map { it.reason })
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun nextPcPreferenceDoesNotCancelPendingPreparedOrPlayingAudio() = runTest {
        for (stage in listOf("pending", "prepared", "playing")) {
            val f = AudioFixture(this)
            try {
                f.activate(); f.visible = stage != "pending"
                f.extension.receive(f.offer()); runCurrent()
                if (stage != "pending") {
                    f.media.single().chunks.send(ByteArray(9600)); runCurrent()
                    if (stage == "playing") f.extension.receive(f.start())
                }
                f.extension.receive(AudioStatus(1, audioId(1), audioId(2), "auto", "preparing", "pc", "none", "preparing"))
                if (stage == "pending") {
                    f.visible = true; f.extension.baseState(audioId(1), audioId(3), true); runCurrent()
                    f.media.single().chunks.send(ByteArray(9600)); runCurrent()
                }
                if (stage != "playing") f.extension.receive(f.start())
                assertEquals(1, f.platform.sinks.single().starts)
                assertEquals(0, f.platform.sinks.single().stops)
                assertEquals("pc", f.outputs.last().preference)
                assertEquals("phone", f.outputs.last().output)
                f.media.single().chunks.close(); f.extension.receive(f.end(4800)); runCurrent()
                f.platform.sinks.single().head = 4800; advanceTimeBy(50); runCurrent()
                assertEquals(1, f.sent.filterIsInstance<AudioFinished>().size)
                assertEquals(1, f.platform.sinks.single().releases)
            } finally { f.extension.closeAndJoin() }
        }
    }

    @Test fun manualPhoneStopDescribesReasonWithoutClaimingPcPlayback() {
        val text = audioOutputDescription(AudioOutputStatus("phone", "none", "stopped", "prepare_timeout"))
        assertTrue(text.contains("휴대폰")); assertTrue(text.contains("준비 시간"))
        assertFalse(text.contains("PC 재생 중"))
    }

    @Test fun pcFallbackStatusAfterCancelDescribesActualPcOutput() = runTest {
        val f = AudioFixture(this)
        try {
            f.activate(); f.extension.receive(f.offer()); runCurrent()
            f.extension.receive(AudioCancel(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6), "pc_fallback"))
            f.extension.receive(AudioStatus(1, audioId(1), audioId(2), "auto", "prepare_timeout", "auto", "pc", "playing"))
            assertEquals(AudioOutputStatus("auto", "pc", "playing", "prepare_timeout"), f.outputs.last())
            assertEquals(0, f.platform.sinks.single().starts)
        } finally { f.extension.closeAndJoin() }
    }
}
