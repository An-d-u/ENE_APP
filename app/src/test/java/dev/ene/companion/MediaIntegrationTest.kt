package dev.ene.companion

import dev.ene.companion.character.*
import dev.ene.companion.connection.ExtensionSession
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 실제 세션 두 개와 합성 그래픽/오디오 장치로 수명을 결합한다. 기기 실행은 아니다. */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun twentyConnectionsCombineSettingsPatsPlaybackModelReplacementAndCleanup() = runTest {
        val cache = CharacterCache(temporary.newFolder())
        val identity = "1".repeat(64)
        repeat(20) { cycle ->
            var payload = "{\"synthetic_cycle\":$cycle}".toByteArray()
            var model = CharacterSnapshot.parse(CharacterFixtures.manifest(payload))
            val capabilities = listOf("audio_pcm_v1", "character_v1", "character_controls_v1")
            val ready = Ready(audioId(9), audioId(1), audioId(3), 1, capabilities)
            val sent = mutableListOf<WireMessage>()
            val commands = mutableListOf<Pair<String, JsonObject>>()
            val states = mutableListOf<CharacterViewState>()
            var mounted: CharacterCache.CachedCharacter? = null
            var opened = 0
            var closed = 0
            val renderer = object : CharacterRenderer {
                override fun show(snapshot: CharacterSnapshot, character: CharacterCache.CachedCharacter?) {
                    mounted?.close(); mounted = character?.retain()
                }
                override fun post(type: String, value: JsonObject) { commands += type to value }
            }
            val character = CharacterSession(ready, identity, backgroundScope,
                CharacterPlatform(true) { _, _, _ ->
                    val value = cache.acquire(identity, model) ?: cache.begin(identity, model).use { ticket ->
                        ticket.open(model.entryAssetId!!).use { it.write(payload) }; ticket.commit()
                    }
                    CharacterLoad(model, value)
                }, mediaFactory = { _, _ ->
                    opened++
                    object : CharacterMedia {
                        var done = false
                        override suspend fun manifest(): ByteArray = error("합성 로더")
                        override suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit) = error("합성 로더")
                        override fun close() { if (!done) { done = true; closed++ } }
                    }
                }, send = { sent += it; true }, nowMillis = { testScheduler.currentTime },
                isPublicAssistant = { it == audioId(4) }, onState = states::add)
            val audioMedia = TestAudioMedia()
            val audioPlatform = AudioTestPlatform()
            val audio = ExtensionSession(ready, backgroundScope, { audioMedia }, audioPlatform,
                { sent += it; true }, { testScheduler.currentTime }, { it == audioId(4) }, {},
                { fail("합성 연결은 유지되어야 합니다.") }, capabilities.toSet() - "audio_pcm_v1", character::localPlayback)
            try {
                val extension = ExtensionsReady(1, audioId(1), audioId(2), capabilities)
                character.attach(renderer); character.resumed(true); character.baseState(audioId(1), audioId(3), true); character.receive(extension)
                audio.resumed(true); audio.baseState(audioId(1), audioId(3), true); audio.receive(extension)
                advanceTimeBy(300); runCurrent(); character.event(renderer, CharacterEvent("ready", model.modelVersion))
                character.openSettings(); character.previewSettings("head_pat_strength", JsonPrimitive(1.8), false); character.submitSettings()
                val patch = sent.filterIsInstance<CharacterSettingsPatch>().single()
                model = CharacterSnapshot.parse(JsonObject(model.json + mapOf("state_revision" to JsonPrimitive(2),
                    "settings_revision" to JsonPrimitive(2))).toString())
                character.receive(CharacterSettingsResult(1, audioId(1), audioId(2), patch.command_id, "conflict", 2, "revision_conflict"))
                advanceTimeBy(300); runCurrent(); character.event(renderer, CharacterEvent("ready", model.modelVersion))
                assertFalse(states.last().settings.busy)
                character.closeSettings()
                character.event(renderer, CharacterEvent("head_pat_input", input = HeadPatInput(model.modelVersion!!, audioId(100 + cycle), 0, "start", .4)))
                assertEquals(1, sent.filterIsInstance<HeadPat>().count { it.phase == "start" })
                audio.receive(AudioOffer(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6), 24000, 1, 2, 2000))
                runCurrent(); audioMedia.chunks.send(ByteArray(9600) { if (it % 2 == 0) 0x10 else 0x27 }); runCurrent()
                assertEquals(1, sent.filterIsInstance<AudioPrepared>().size)
                audio.receive(AudioStart(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6)))
                runCurrent(); audioPlatform.sinks.single().head = 2400; advanceTimeBy(50); runCurrent()
                assertTrue(commands.any { it.first == "playback" && it.second.getValue("mouth_open").jsonPrimitive.double > 0.0 })
                if (cycle % 2 == 0) character.rendererFailed(renderer, "character_renderer_gone")
                else {
                    payload = "{\"synthetic_replacement\":$cycle}".toByteArray()
                    model = CharacterSnapshot.parse(CharacterFixtures.changed(CharacterFixtures.manifest(payload, revision = 3), "settings_revision", JsonPrimitive(3)))
                    character.receive(CharacterChanged(1, audioId(1), audioId(2), 3, model.modelVersion, "model_changed"))
                    advanceTimeBy(300); runCurrent(); character.event(renderer, CharacterEvent("ready", model.modelVersion))
                }
                assertEquals(1, sent.filterIsInstance<HeadPat>().count { it.phase == "cancel" })
            } finally {
                audio.closeAndJoin(); character.closeAndJoin(); mounted?.close(); mounted = null
            }
            assertEquals(opened, closed)
            assertTrue(audioMedia.done); assertEquals(1, audioPlatform.sinks.single().releases)
            assertEquals(1, audioPlatform.focuses.single().abandons)
            val count = commands.size
            advanceTimeBy(1000); runCurrent(); assertEquals(count, commands.size)
            assertTrue(cache.versionCount <= 2)
        }
    }
}
