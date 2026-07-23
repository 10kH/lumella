package com.woolab.lumella

import com.woolab.lumella.brain.BrainFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTest {

    @Test
    fun parsesAllKeysFromPropertiesMap() {
        val config = AppConfig.fromProperties(
            mapOf(
                "lumella.tokenServiceBaseUrl" to "http://192.168.1.5:8788",
                "lumella.lumaBaseUrl" to "http://192.168.1.5:8010",
                "lumella.localToken" to "shared-secret",
                "lumella.brainClassName" to "com.example.CustomBrain",
                "lumella.brainEmail" to "learner@example.com",
                "lumella.brainPassword" to "hunter2",
            ),
        )

        assertEquals("http://192.168.1.5:8788", config.tokenServiceBaseUrl)
        assertEquals("http://192.168.1.5:8010", config.lumaBaseUrl)
        assertEquals("shared-secret", config.localToken)
        assertEquals("com.example.CustomBrain", config.brainClassName)
        assertEquals("learner@example.com", config.brainEmail)
        assertEquals("hunter2", config.brainPassword)
    }

    @Test
    fun missingKeysDefaultToEmptyStringsFailClosed() {
        val config = AppConfig.fromProperties(emptyMap())

        assertEquals("", config.tokenServiceBaseUrl)
        assertEquals("", config.lumaBaseUrl)
        assertEquals("", config.localToken)
        assertEquals("", config.brainEmail)
        assertEquals("", config.brainPassword)
    }

    @Test
    fun blankOrMissingBrainClassNameFallsBackToDefault() {
        val missing = AppConfig.fromProperties(emptyMap())
        val blank = AppConfig.fromProperties(mapOf("lumella.brainClassName" to "   "))

        assertEquals(BrainFactory.DEFAULT_BRAIN_CLASS_NAME, missing.brainClassName)
        assertEquals(BrainFactory.DEFAULT_BRAIN_CLASS_NAME, blank.brainClassName)
    }
}
