package dev.continuum.fx.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryTest {
    @Test
    fun anEffectThatCouldNotBePutOnIsNotCountedAndIsTriedAgain() {
        val registry = SessionRegistry<String> { }
        val candidates = mapOf(42 to "music")
        assertTrue(registry.sync(candidates) { _, _ -> null }.isEmpty())
        assertEquals(candidates, registry.sync(candidates) { _, _ -> "effect" })
    }

    @Test
    fun aReusedSessionNumberLetsGoOfTheOldAppsEffectFirst() {
        val calls = mutableListOf<String>()
        val registry = SessionRegistry<String> { calls += "release $it" }
        registry.sync(mapOf(42 to "old")) { _, pkg -> pkg }
        registry.sync(mapOf(42 to "new")) { _, pkg -> calls += "attach $pkg"; pkg }
        assertEquals(listOf("release old", "attach new"), calls)
        assertEquals(listOf("new"), registry.effects())
    }

    @Test
    fun everyEffectIsLetGoOnceEvenIfOneThrows() {
        val released = mutableListOf<String>()
        val registry = SessionRegistry<String> { released += it; if (it == "a") error("dead") }
        registry.sync(mapOf(1 to "a", 2 to "b")) { _, pkg -> pkg }
        registry.sync(mapOf(2 to "b")) { _, _ -> throw AssertionError("put on again") }
        registry.clear()
        registry.clear()
        assertEquals(listOf("a", "b"), released)
        assertTrue(registry.effects().isEmpty())
    }
}
