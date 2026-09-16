package dev.ene.companion

import dev.ene.companion.character.*
import dev.ene.companion.protocol.CharacterAction
import org.junit.Assert.*
import org.junit.Test

class CharacterSequenceTest {
    private fun snapshot(revision: Long = 1) = CharacterSnapshot.parse(CharacterFixtures.manifest(revision = revision))
    private fun action(sequence: Long, model: String = snapshot().modelVersion!!) = CharacterAction(
        1, audioId(1), audioId(2), model, sequence, "gesture", "nod", 0)

    @Test fun duplicateAndGapDoNotReplayActions() {
        val state = CharacterSequence()
        assertTrue(state.install(snapshot()))
        state.rendered(snapshot().modelVersion!!)
        assertEquals("apply", state.action(action(4)))
        assertEquals("ignore", state.action(action(4)))
        assertEquals("refresh", state.action(action(6)))
        assertEquals("ignore", state.action(action(7)))
        assertEquals(7L, state.observedAction)
    }

    @Test fun downloadAndRotationOnlyObserveTransientActions() {
        val state = CharacterSequence()
        assertEquals("ignore", state.action(action(5)))
        assertTrue(state.install(snapshot()))
        assertEquals(5L, state.observedAction)
        state.rendered(snapshot().modelVersion!!)
        assertEquals("ignore", state.action(action(5)))
        assertEquals("apply", state.action(action(6)))
        state.detached()
        assertEquals("ignore", state.action(action(7)))
        state.rendered(snapshot().modelVersion!!)
        assertEquals("apply", state.action(action(8)))
    }

    @Test fun newerRevisionPreventsStaleDownloadOrOldModelAction() {
        val state = CharacterSequence()
        state.changed(2, snapshot().modelVersion)
        assertFalse(state.install(snapshot(1)))
        assertTrue(state.install(snapshot(2)))
        state.rendered(snapshot().modelVersion!!)
        assertEquals("ignore", state.action(action(4, "b".repeat(64))))
        assertEquals("apply", state.action(action(5)))
        assertFalse(state.changed(1, null))
    }

    @Test fun unsupportedActionCannotReachRendererAndNewSnapshotDoesNotLowerWatermark() {
        val state = CharacterSequence()
        state.install(snapshot()); state.rendered(snapshot().modelVersion!!)
        assertEquals("ignore", state.action(action(4).copy(action_id = "missing")))
        state.loading()
        state.action(action(10))
        state.install(snapshot())
        state.rendered(snapshot().modelVersion!!)
        assertEquals(10L, state.observedAction)
        assertEquals("ignore", state.action(action(9)))
        assertEquals("apply", state.action(action(11)))
    }

    @Test fun appliedExpressionBecomesRestorableBaseStateWithoutReplayingItsAnimation() {
        val state = CharacterSequence()
        state.install(snapshot()); state.rendered(snapshot().modelVersion!!)
        assertEquals("apply", state.action(action(4).copy(kind = "expression", action_id = "bright", duration_ms = 500)))
        state.detached()
        assertEquals("bright", state.snapshot!!.json.getValue("default_expression").toString().trim('"'))
        assertEquals(4L, state.snapshot!!.actionSeq)
    }
}
