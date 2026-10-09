package dev.ene.companion

import dev.ene.companion.character.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CharacterPresentationOwnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = "1".repeat(64)
    private val bytes = "{}".toByteArray()
    private val snapshot = CharacterSnapshot.parse(CharacterFixtures.manifest(bytes))
    private fun load(cache: CharacterCache): CharacterLoad = CharacterLoad(snapshot,
        cache.acquire(identity, snapshot) ?: cache.begin(identity, snapshot).use { ticket ->
            ticket.open(snapshot.entryAssetId!!).use { it.write(bytes) }; ticket.commit()
        })
    private class Fixture {
        val states = mutableListOf<CharacterViewState>()
        val owner = CharacterPresentationOwner(states::add)
        val renderer = RetainedRenderer()
        var binding = owner.bind("1".repeat(64))
        init { owner.resumed(true); owner.attach(renderer) }
        fun show(load: CharacterLoad) {
            owner.update(binding, CharacterViewState("rendering", retainRenderer = true))
            owner.show(binding, load.snapshot, load.character)
        }
        fun ready() {
            assertTrue(owner.event(renderer, CharacterEvent("ready", renderer.snapshots.last().modelVersion,
                presentationGeneration = renderer.generation)))
            owner.update(binding, CharacterViewState("ready", retainRenderer = true, presentationAllowed = true))
        }
    }

    @Test fun preparedModelSurvivesTransientReleaseAndRequiresFreshReadiness() {
        val cache = CharacterCache(temporary.newFolder())
        val f = Fixture()
        load(cache).use { f.show(it); f.ready() }
        f.renderer.stream = f.renderer.mounted!!.open(snapshot.entryAssetId!!)
        val view = f.states.last().viewGeneration
        repeat(3) {
            f.owner.release(f.binding, CharacterStopReason.BACKGROUND)
            assertTrue(f.states.last().retainRenderer); assertFalse(f.states.last().presentationAllowed)
            assertFalse(f.renderer.visible); assertEquals(0, f.renderer.clears)
            f.binding = f.owner.bind(identity)
            assertFalse(f.states.last().presentationAllowed)
            load(cache).use { f.show(it); assertFalse(f.renderer.visible); f.ready() }
            assertTrue(f.renderer.visible); assertEquals(view, f.states.last().viewGeneration)
        }
        f.owner.discard(CharacterStopReason.REGISTRATION_CHANGE)
        cache.clear(); assertEquals(0, cache.versionCount)
    }

    @Test fun staleBindingAndLateReadinessCannotAffectCurrentModel() {
        val cache = CharacterCache(temporary.newFolder()); val f = Fixture()
        load(cache).use { f.show(it); f.ready() }
        val old = f.binding; val oldGeneration = f.renderer.generation
        f.owner.release(old, CharacterStopReason.TRANSIENT_DISCONNECT)
        f.binding = f.owner.bind(identity)
        load(cache).use { f.show(it) }
        f.owner.release(old, CharacterStopReason.CLOSED)
        f.owner.update(old, CharacterViewState("error"))
        assertFalse(f.owner.event(f.renderer, CharacterEvent("ready", snapshot.modelVersion, presentationGeneration = oldGeneration)))
        assertFalse(f.renderer.visible); assertEquals(0, f.renderer.clears)
        f.ready(); f.owner.discard(CharacterStopReason.CLOSED); cache.clear()
    }

    @Test fun incompleteInitialLoadAndChangedRegistrationAreNotRetained() {
        val cache = CharacterCache(temporary.newFolder()); val f = Fixture()
        load(cache).use { f.show(it) }
        f.owner.release(f.binding, CharacterStopReason.BACKGROUND)
        assertFalse(f.states.last().retainRenderer)
        cache.clear()
        f.binding = f.owner.bind(identity); f.owner.attach(f.renderer)
        load(cache).use { f.show(it); f.ready() }
        f.owner.bind("2".repeat(64))
        assertFalse(f.states.last().retainRenderer); assertFalse(f.renderer.visible)
        cache.clear()
    }

    @Test fun backgroundRendererDeathAndOldViewReleaseAreIsolated() {
        val cache = CharacterCache(temporary.newFolder()); val f = Fixture()
        load(cache).use { f.show(it); f.ready() }
        f.owner.release(f.binding, CharacterStopReason.BACKGROUND)
        f.owner.rendererFailed(f.renderer, "character_renderer_gone")
        assertFalse(f.states.last().retainRenderer); assertEquals("error", f.states.last().status)
        cache.clear()
        f.binding = f.owner.bind(identity)
        val replacement = RetainedRenderer(); f.owner.attach(replacement)
        load(cache).use {
            f.owner.update(f.binding, CharacterViewState("rendering", retainRenderer = true))
            f.owner.show(f.binding, it.snapshot, it.character)
        }
        f.owner.detach(f.renderer)
        assertTrue(f.owner.event(replacement, CharacterEvent("ready", snapshot.modelVersion, presentationGeneration = replacement.generation)))
        f.owner.discard(CharacterStopReason.CLOSED); cache.clear()
    }
}
