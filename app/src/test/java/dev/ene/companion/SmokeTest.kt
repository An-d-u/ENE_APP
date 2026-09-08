package dev.ene.companion

import org.junit.Assert.assertEquals
import org.junit.Test

/** 최소 모듈이 의도한 앱 식별자로 빌드되는지 확인한다. */
class SmokeTest {
    @Test
    fun applicationIdMatchesIndependentCompanion() {
        assertEquals("dev.ene.companion", BuildConfig.APPLICATION_ID)
        assertEquals(1, BuildConfig.VERSION_CODE)
    }
}
