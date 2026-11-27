package com.intempt.core.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mutation-module only: `Map.getOrDefault` is API 24 and `:app` lint checks tests at minSdk 23, so
 * the override is covered here for the gate, and under Robolectric in `FlagExposureTest` for the app.
 */
class ExposingFlagMapPureTest {
    private val seen = mutableListOf<Any?>()
    private val map = ExposingFlagMap(mapOf("a" to 1L, "n" to null)) { seen += it }

    @Test
    fun `getOrDefault records the key like get and keeps a served null`() {
        assertEquals(1L, map.getOrDefault("a", 0L))
        assertNull(map.getOrDefault("n", 9L))
        assertEquals(9L, map.getOrDefault("zzz", 9L))
        assertEquals(listOf("a", "n", "zzz"), seen)
    }

    @Test
    fun `a non-string key through the java view is the default and records nothing`() {
        @Suppress("UNCHECKED_CAST") // the java.util.Map view a Java or bridge caller holds
        val javaView = map as java.util.Map<Any?, Any?>
        assertEquals(5L, javaView.getOrDefault(1, 5L))
        assertEquals(1L, javaView.getOrDefault("a", 5L))
        assertEquals(listOf("a"), seen)
    }
}
