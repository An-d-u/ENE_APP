package dev.ene.companion

import dev.ene.companion.character.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CharacterSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ready = Ready(audioId(9), audioId(1), audioId(3), 1, listOf("audio_pcm_v1", "character_v1"))
    private val payload = "{}".toByteArray()
    private fun snapshot() = CharacterSnapshot.parse(CharacterFixtures.manifest(payload))
    private fun result(model: CharacterSnapshot = snapshot()): CharacterLoad {
        val cache = CharacterCache(temporary.newFolder())
        val mount = cache.begin("1".repeat(64), model).use { ticket ->
            ticket.open(model.entryAssetId!!).use { it.write(payload) }
            ticket.commit()
        }
        return CharacterLoad(model, mount)
    }
    private class Media : CharacterMedia {
        var closed = false
        override suspend fun manifest(): ByteArray = error("합성 로더가 사용하는 시험 전송")
        override suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit) = error("합성 로더")
        override fun close() { closed = true }
    }
    private open class Renderer : CharacterRenderer {
        val snapshots = mutableListOf<CharacterSnapshot>()
        val commands = mutableListOf<Pair<String, JsonObject>>()
        override fun show(snapshot: CharacterSnapshot, character: CharacterCache.CachedCharacter?) { snapshots += snapshot }
        override fun post(type: String, value: JsonObject) { commands += type to value }
    }
    private inner class Fixture(test: TestScope, controls: Boolean = false, loader: suspend () -> CharacterLoad) {
        private val sessionReady = if (controls) ready.copy(capabilities = ready.capabilities + "character_controls_v1") else ready
        val states = mutableListOf<CharacterViewState>()
        val media = mutableListOf<Media>()
        val sent = mutableListOf<WireMessage>()
        val renderer = Renderer()
        val session = CharacterSession(sessionReady, "1".repeat(64), test.backgroundScope,
            CharacterPlatform(true) { _, _, _ -> loader() },
            mediaFactory = { _, _ -> Media().also(media::add) }, send = { sent += it; true },
            nowMillis = { test.testScheduler.currentTime }, isPublicAssistant = { it == audioId(4) }, onState = states::add)
        fun activate() {
            session.attach(renderer)
            session.resumed(true)
            session.baseState(audioId(1), audioId(3), true)
            session.receive(ExtensionsReady(1, audioId(1), audioId(2), sessionReady.capabilities))
        }
        fun rendered() = session.event(renderer, CharacterEvent("ready", snapshot().modelVersion))
        fun action(number: Long) = CharacterAction(1, audioId(1), audioId(2), snapshot().modelVersion!!, number, "gesture", "nod", 0)
    }

    @Test fun settingsPreviewUsesCurrentRendererAndSaveWaitsForServerSnapshot() = runTest {
        var model = snapshot()
        val f = Fixture(this, controls = true) { result(model) }
        f.activate(); advanceTimeBy(300); runCurrent(); f.rendered()
        f.session.openSettings()
        repeat(10) { f.session.previewSettings("head_pat_strength", JsonPrimitive(1.0 + it / 10.0), false) }
        assertEquals(0, f.sent.filterIsInstance<CharacterSettingsPatch>().size)
        assertEquals(1, f.renderer.snapshots.size)
        assertEquals(10, f.renderer.commands.count { it.first == "preview" })
        f.session.submitSettings()
        val patch = f.sent.filterIsInstance<CharacterSettingsPatch>().single()
        assertTrue(f.states.last().settings.busy)
        model = CharacterSnapshot.parse(JsonObject(model.json + mapOf("state_revision" to JsonPrimitive(2),
            "settings_revision" to JsonPrimitive(2))).toString())
        f.session.receive(CharacterSettingsResult(1, audioId(1), audioId(2), patch.command_id, "conflict", 2, "revision_conflict"))
        advanceTimeBy(300); runCurrent(); f.rendered()
        assertEquals("conflict", f.states.last().settings.result)
        assertFalse(f.states.last().settings.busy)
        f.session.resumed(false)
        assertFalse(f.states.last().settings.open)
        f.session.submitSettings()
        assertEquals(1, f.sent.filterIsInstance<CharacterSettingsPatch>().size)
        f.session.closeAndJoin()
    }

    @Test fun settingsTimeoutDuringRotationRefreshesOnResumeWithoutResending() = runTest {
        var loads = 0
        val f = Fixture(this, controls = true) { loads++; result() }
        f.activate(); advanceTimeBy(300); runCurrent(); f.rendered()
        f.session.openSettings(); f.session.previewSettings("head_pat_strength", JsonPrimitive(1.8), false); f.session.submitSettings()
        f.session.resumed(false, changingConfigurations = true)
        advanceTimeBy(10_100); runCurrent()
        f.session.resumed(true); advanceTimeBy(300); runCurrent(); f.rendered()
        assertEquals(2, loads)
        assertFalse(f.states.last().settings.busy)
        assertEquals(1, f.sent.filterIsInstance<CharacterSettingsPatch>().size)
        f.session.closeAndJoin()
    }

    @Test fun collapsedCharacterStillAllowsConfirmedSettingsWithoutWebView() = runTest {
        val f = Fixture(this, controls = true) { result() }
        f.activate(); f.session.detach(f.renderer)
        advanceTimeBy(300); runCurrent()
        assertTrue(f.states.last().settings.available)
        f.session.openSettings(); f.session.previewSettings("head_pat_strength", JsonPrimitive(1.8), false); f.session.submitSettings()
        assertEquals(1, f.sent.filterIsInstance<CharacterSettingsPatch>().size)
        f.session.closeAndJoin()
    }

    @Test fun renderingRequiresBaseSyncAndActionsAreNeverQueuedDuringLoad() = runTest {
        val answer = CompletableDeferred<CharacterLoad>()
        val f = Fixture(this) { answer.await() }
        f.activate(); runCurrent(); advanceTimeBy(200); runCurrent()
        f.session.receive(f.action(5))
        answer.complete(result()); runCurrent()
        assertEquals(1, f.renderer.snapshots.size)
        f.rendered()
        f.session.receive(f.action(5)); f.session.receive(f.action(6))
        assertEquals(1, f.renderer.commands.count { it.first == "action" })
        f.session.closeAndJoin()
        assertTrue(f.media.all { it.closed })
    }

    @Test fun latestModelRequestCancelsAndJoinsBeforeStartingNextDownload() = runTest {
        var running = 0
        var maximum = 0
        var calls = 0
        val f = Fixture(this) {
            calls++; running++; maximum = maxOf(maximum, running)
            try { awaitCancellation() } finally { running-- }
        }
        f.activate(); advanceTimeBy(200); runCurrent()
        f.session.receive(CharacterChanged(1, audioId(1), audioId(2), 2, "b".repeat(64), "model_changed"))
        f.session.receive(CharacterChanged(1, audioId(1), audioId(2), 3, "c".repeat(64), "model_changed"))
        advanceTimeBy(400); runCurrent()
        assertEquals(2, calls)
        assertEquals(1, maximum)
        f.session.closeAndJoin()
        assertEquals(0, running)
        assertTrue(f.media.all { it.closed })
    }

    @Test fun changesInsideInitialDebounceStartOnlyOneLatestDownload() = runTest {
        var calls = 0
        val f = Fixture(this) { calls++; awaitCancellation() }
        f.activate(); runCurrent(); advanceTimeBy(50)
        f.session.receive(CharacterChanged(1, audioId(1), audioId(2), 2, "b".repeat(64), "model_changed"))
        advanceTimeBy(50)
        f.session.receive(CharacterChanged(1, audioId(1), audioId(2), 3, "c".repeat(64), "model_changed"))
        advanceTimeBy(500); runCurrent()
        assertEquals(1, calls)
        assertEquals(1, f.media.size)
        f.session.closeAndJoin()
    }

    @Test fun rendererFailureNeedsExplicitRetryAndDoesNotCancelConnection() = runTest {
        var loads = 0
        val f = Fixture(this) { loads++; result() }
        f.activate(); advanceTimeBy(300); runCurrent(); f.rendered()
        f.session.rendererFailed(f.renderer, "character_renderer_gone")
        advanceTimeBy(5000); runCurrent()
        assertEquals("error", f.states.last().status)
        assertEquals(1, loads)
        assertTrue(f.sent.isEmpty())
        f.session.retry(); advanceTimeBy(300); runCurrent()
        assertEquals(2, loads)
        f.session.closeAndJoin()
    }

    @Test fun rotationReinjectsStateAndCurrentPlaybackWithoutAnAudioStart() = runTest {
        val f = Fixture(this) { result() }
        f.activate(); advanceTimeBy(200); runCurrent(); f.rendered()
        val progress = CharacterPlayback(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(6), "phone", 123, .4, true)
        f.session.localPlayback(progress)
        f.session.detach(f.renderer)
        f.session.receive(f.action(4))
        val replacement = Renderer()
        f.session.attach(replacement)
        f.session.event(replacement, CharacterEvent("ready", snapshot().modelVersion))
        assertEquals(1, replacement.snapshots.size)
        val mouth = replacement.commands.last { it.first == "playback" }.second
        assertEquals(123L, mouth.getValue("played_ms").jsonPrimitive.long)
        assertEquals(.4, mouth.getValue("mouth_open").jsonPrimitive.double, .001)
        assertTrue(replacement.commands.none { it.first == "action" })
        assertTrue(f.sent.none { it is AudioStart })
        f.session.closeAndJoin()
    }

    @Test fun wrongContextAndPrivateMessageCannotChangeCharacterOrMouth() = runTest {
        val f = Fixture(this) { result() }
        f.activate(); advanceTimeBy(200); runCurrent(); f.rendered()
        f.session.receive(f.action(4).copy(connection_generation = audioId(99)))
        f.session.receive(CharacterPlayback(1, audioId(1), audioId(2), audioId(3), audioId(90), audioId(6), "pc", 100, 1.0, true))
        advanceTimeBy(50); runCurrent()
        assertTrue(f.renderer.commands.none { it.first == "action" })
        assertTrue(f.renderer.commands.filter { it.first == "playback" }.all { it.second.getValue("mouth_open").jsonPrimitive.double == 0.0 })
        f.session.closeAndJoin()
    }

    @Test fun throwingRendererCannotEscapeIntoTheSharedConnectionCallback() = runTest {
        val f = Fixture(this) { result() }
        f.activate(); advanceTimeBy(200); runCurrent()
        val broken = object : Renderer() {
            override fun post(type: String, value: JsonObject) { throw IllegalStateException("합성 렌더러 오류") }
        }
        f.session.attach(broken)
        f.session.event(broken, CharacterEvent("ready", snapshot().modelVersion))
        assertEquals("error", f.states.last().status)
        assertTrue(f.sent.isEmpty())
        f.session.closeAndJoin()
    }

    @Test fun patInputRequiresNegotiationReadyRendererAndCancelsOnRotation() = runTest {
        val f = Fixture(this, controls = true) { result() }
        val input = HeadPatInput(snapshot().modelVersion!!, audioId(60), 0, "start", .5)
        f.activate(); advanceTimeBy(200); runCurrent()
        f.session.event(f.renderer, CharacterEvent("head_pat_input", input = input))
        assertTrue(f.sent.none { it is HeadPat })
        f.rendered()
        f.session.event(f.renderer, CharacterEvent("head_pat_input", input = input))
        assertEquals("start", (f.sent.single() as HeadPat).phase)
        f.session.resumed(false, changingConfigurations = true)
        assertEquals(listOf("start", "cancel"), f.sent.filterIsInstance<HeadPat>().map { it.phase })
        f.session.closeAndJoin()
        assertEquals(2, f.sent.size)
    }

    @Test fun unnegotiatedOrStaleRendererCannotSendPatAndRemoteEchoIsNotInput() = runTest {
        val plain = Fixture(this) { result() }
        plain.activate(); advanceTimeBy(200); runCurrent(); plain.rendered()
        val input = HeadPatInput(snapshot().modelVersion!!, audioId(61), 0, "start", .5)
        plain.session.event(plain.renderer, CharacterEvent("head_pat_input", input = input))
        assertTrue(plain.sent.isEmpty())
        plain.session.closeAndJoin()
        val f = Fixture(this, controls = true) { result() }
        f.activate(); advanceTimeBy(200); runCurrent(); f.rendered()
        f.session.event(Renderer(), CharacterEvent("head_pat_input", input = input))
        f.session.receive(HeadPatState(1, audioId(1), audioId(2), snapshot().modelVersion!!, audioId(62), 1, 0, "accepted", .4, "pc", "accepted"))
        assertTrue(f.renderer.commands.any { it.first == "head_pat" })
        assertTrue(f.sent.isEmpty())
        f.session.closeAndJoin()
    }
}
