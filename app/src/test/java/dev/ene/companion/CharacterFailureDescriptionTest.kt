package dev.ene.companion

import dev.ene.companion.ui.characterFailureDescription
import org.junit.Assert.*
import org.junit.Test

class CharacterFailureDescriptionTest {
    @Test fun safeStagesAreDistinctAndArbitraryErrorsNeverReachTheScreen() {
        val stages = listOf("character_download_failed", "character_bridge_timeout",
            "character_initialization_failed", "character_asset_failed", "character_render_failed")
        val descriptions = stages.map(::characterFailureDescription)
        assertEquals(stages.size, descriptions.toSet().size)
        assertTrue(descriptions.all { it.isNotBlank() })
        assertEquals(characterFailureDescription(null), characterFailureDescription("synthetic private detail"))
        assertFalse(characterFailureDescription("synthetic private detail").contains("synthetic"))
    }
}
