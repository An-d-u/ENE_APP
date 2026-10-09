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
    @Test fun resumedSessionDoesNotShowBeforeItsNewSnapshotIsApplied() = runTest {
        val f = Fixture(this) { result() }
        f.activate(); advanceTimeBy(300); runCurrent(); f.rendered()
        f.session.resumed(false)
        f.session.resumed(true); advanceTimeBy(300); runCurrent()
        assertTrue(f.states.last().retainRenderer)
        assertFalse(f.states.last().presentationAllowed)
        assertFalse(f.renderer.presentations.last().second)
        f.rendered()
        assertTrue(f.states.last().presentationAllowed)
        f.session.closeAndJoin()
    }
    @get:Rule val temporary = TemporaryFolder()
    private val ready = Ready(audioId(9), audioId(1), audioId(3), 1, listOf("audio_pcm_v1", "character_v1"))
    private val payload = "{}".toByteArray()
    private fun snapshot() = CharacterSnapshot.parse(CharacterFixtures.manifest(payload))
    private fun result(model: CharacterSnapshot = snapshot(), bytes: ByteArray = payload): CharacterLoad {
        val cache = CharacterCache(temporary.newFolder())
        val mount = cache.begin("1".repeat(64), model).use { ticket ->
            ticket.open(model.entryAssetId!!).use { it.write(bytes) }
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
        val presentations = mutableListOf<Pair<CharacterPlacement, Boolean>>()
        override fun present(placement: CharacterPlacement, visible: Boolean) { presentations += placement to visible }
        override fun show(snapshot: CharacterSnapshot, character: CharacterCache.CachedCharacter?) { snapshots += snapshot }
        override fun post(type: String, value: JsonObject) { commands += type to value }
    }

    @Test fun hiddenPanelRetainsModelAndLatestExpressionWithoutReplayingGestures() = runTest {
        var loads = 0
        val f = Fixture(this, controls = true) { loads++; result() }
        val p = CharacterPlacement(1.5, 25.0, 75.0)
        f.session.placement(CharacterPlacementState(p, loaded = true))
        f.activate(); advanceTimeBy(300); runCurrent(); f.rendered()
        assertTrue(f.states.last().retainRenderer)
        f.session.panelVisible(false)
        assertFalse(f.renderer.presentations.last().second)
        assertFalse(f.states.last().presentationAllowed)
        assertTrue(f.states.last().settings.available)
        val before = f.renderer.commands.size
        f.session.receive(f.action(4).copy(kind = "expression", action_id = "normal"))
        f.session.receive(f.action(5))
        f.session.localPlayback(CharacterPlayback(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(6), "phone", 123, .4, true))
        assertEquals(before, f.renderer.commands.size)
        f.session.panelVisible(true); f.rendered()
        assertEquals(4L, f.renderer.snapshots.last().actionSeq)
        assertEquals(p to true, f.renderer.presentations.last())
        assertEquals(1, loads)
        assertTrue(f.sent.none { it is CharacterSettingsPatch || it is AudioStart })
        assertTrue(f.renderer.commands.none { it.first == "action" })
        f.session.closeAndJoin(); assertFalse(f.states.last().retainRenderer)
    }

    @Test fun sameConnectionSyncHidesImmediatelyButRetainsOwnership() = runTest {
        var loads = 0
        val f = Fixture(this, chat = true) { loads++; result() }
        f.activate(); advanceTimeBy(300); runCurrent(); f.rendered()
        val generation = f.states.last().viewGeneration
        repeat(3) {
            f.session.receive(ChatActionsState(1, audioId(1), audioId(2), audioId(3), it.toLong(), it.toLong(), it + 1L,
                null, null, false, "no_target", false, "no_target"))
            f.session.baseState(audioId(1), audioId(3), false)
            assertTrue(f.states.last().retainRenderer)
            assertFalse(f.states.last().presentationAllowed)
            assertFalse(f.renderer.presentations.last().second)
            f.session.baseState(audioId(1), audioId(3), true); f.rendered()
        }
        assertEquals(1, loads)
        assertEquals(generation, f.states.last().viewGeneration)
        assertTrue(f.renderer.presentations.last().second)
        f.session.closeAndJoin()
    }

    @Test fun confirmedPcSettingsAndModelChangesNeverResetLocalPlacement() = runTest {
        var model = snapshot()
        var bytes = payload
        val f = Fixture(this, controls = true) { result(model, bytes) }
        val p = CharacterPlacement(1.5, 25.0, 75.0)
        f.session.placement(CharacterPlacementState(p, loaded = true))
        f.activate(); advanceTimeBy(300); runCurrent(); f.rendered()
        val all = JsonObject(dev.ene.companion.ui.characterSettingFields.associate { field ->
            field.key to when (field.kind) {
                "bool" -> JsonPrimitive(false)
                "expression" -> JsonPrimitive("bright")
                else -> field.samples().last()
            }
        })
        assertEquals(20, all.size)
        for (revision in 2L..3L) {
            if (revision == 3L) { bytes = "{\"synthetic\":true}".toByteArray(); model = CharacterSnapshot.parse(CharacterFixtures.manifest(bytes)) }
            model = CharacterSnapshot.parse(JsonObject(model.json + mapOf("settings" to all,
                "settings_revision" to JsonPrimitive(revision), "state_revision" to JsonPrimitive(revision))).toString())
            f.session.receive(CharacterChanged(1, audioId(1), audioId(2), revision, model.modelVersion, "settings_changed"))
            advanceTimeBy(300); runCurrent()
            f.session.event(f.renderer, CharacterEvent("ready", model.modelVersion))
            assertEquals(all, f.renderer.snapshots.last().json["settings"])
            assertEquals(p to true, f.renderer.presentations.last())
            f.session.openSettings(); f.session.previewSettings("head_pat_strength", JsonPrimitive(1.1), false)
            f.session.closeSettings()
            assertEquals(p to true, f.renderer.presentations.last())
        }
        assertTrue(f.sent.none { it is CharacterSettingsPatch })
        f.session.closeAndJoin()
    }

    @Test fun pcSettingsChangedDuringBaseSyncAreFetchedOnResume() = runTest {
        var model = snapshot()
        var loads = 0
        val f = Fixture(this, controls = true) { loads++; result(model) }
        f.activate(); advanceTimeBy(300); runCurrent(); f.rendered()
        f.session.baseState(audioId(1), audioId(3), false)
        model = CharacterSnapshot.parse(JsonObject(model.json + mapOf(
            "state_revision" to JsonPrimitive(2), "settings_revision" to JsonPrimitive(2),
            "settings" to buildJsonObject { put("idle_motion_strength", 1.6) })).toString())
        f.session.receive(CharacterChanged(1, audioId(1), audioId(2), 2, model.modelVersion, "settings_changed"))
        advanceTimeBy(300); runCurrent(); assertEquals(1, loads)
        f.session.baseState(audioId(1), audioId(3), true)
        advanceTimeBy(300); runCurrent(); f.rendered()
        assertEquals(2, loads)
        assertEquals(2L, f.renderer.snapshots.last().settingsRevision)
        f.session.closeAndJoin()
    }

    @Test fun placementLoadBlocksOnlyRenderingAndLateResultIsAppliedBeforeSnapshot() = runTest {
        val f = Fixture(this) { result() }
        f.session.placement(CharacterPlacementState())
        f.activate(); advanceTimeBy(300); runCurrent()
        assertEquals(1, f.media.size)
        assertTrue(f.renderer.snapshots.isEmpty())
        val p = CharacterPlacement(1.3, 20.0, 80.0)
        f.session.placement(CharacterPlacementState(p, loaded = true))
        assertEquals(1, f.renderer.snapshots.size)
        assertEquals(p, f.renderer.presentations.last().first)
        f.rendered(); f.session.placement(CharacterPlacementState(p.copy(scale = 1.8), loaded = true))
        assertEquals(1, f.renderer.snapshots.size)
        assertEquals(1.8, f.renderer.presentations.last().first.scale, 0.0)
        f.session.closeAndJoin()
    }

    @Test fun rendererFailureStagesArePreservedButUnknownDetailsAreDiscarded() = runTest {
        for (code in listOf("character_bridge_timeout", "character_initialization_failed",
            "character_asset_failed", "character_webview_unsupported", "synthetic private detail")) {
            val f = Fixture(this) { result() }
            f.activate(); advanceTimeBy(300); runCurrent()
            f.session.rendererFailed(f.renderer, code)
            assertEquals("error", f.states.last().status)
            assertEquals(if (code.startsWith("character_")) code else "character_render_failed", f.states.last().errorCode)
            f.session.closeAndJoin()
        }
    }

    @Test fun rendererEventPreservesInitializationStage() = runTest {
        val f = Fixture(this) { result() }
        f.activate(); advanceTimeBy(300); runCurrent()
        f.session.event(f.renderer, CharacterEvent("error", code = "character_initialization_failed"))
        assertEquals("character_initialization_failed", f.states.last().errorCode)
        f.session.closeAndJoin()
    }

    @Test fun shutdownReleasesRendererOwnedMountAndStreamsBeforeCacheCleanup() = runTest {
        val cache = CharacterCache(temporary.newFolder())
        val model = snapshot()
        val load = cache.begin("1".repeat(64), model).use { ticket ->
            ticket.open(model.entryAssetId!!).use { it.write(payload) }
            CharacterLoad(model, ticket.commit())
        }
        var retained: CharacterCache.CachedCharacter? = null
        var stream: java.io.InputStream? = null
        var clears = 0
        val renderer = object : Renderer() {
            override fun show(snapshot: CharacterSnapshot, character: CharacterCache.CachedCharacter?) {
                retained = character?.retain(); stream = retained?.open(model.entryAssetId!!)
            }
            override fun clear() { stream?.close(); retained?.close(); clears++ }
        }
        val f = Fixture(this) { load }
        f.activate(); f.session.attach(renderer); advanceTimeBy(300); runCurrent()
        assertNotNull(retained)
        f.session.closeAndJoin(); f.session.shutdown()
        assertEquals(1, clears)
        cache.clear(); assertEquals(0, cache.versionCount)
    }
    private inner class Fixture(test: TestScope, controls: Boolean = false, chat: Boolean = false, loader: suspend () -> CharacterLoad) {
        private val sessionReady = ready.copy(capabilities = ready.capabilities +
            (if (controls) listOf("character_controls_v1") else emptyList()) + (if (chat) listOf("chat_actions_v1") else emptyList()))
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
