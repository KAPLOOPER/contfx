package dev.continuum.fx.volume

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrderTest {
    private val names = mapOf("com.apple.android.music" to "Apple Music", "com.google.android.youtube" to "YouTube", "com.spotify.music" to "Spotify")
    private val label: (String) -> String = { names[it] ?: it }

    @Test
    fun rowsStayPutWhenAndroidReordersItsPlayers() {
        val shown = listOf("com.apple.android.music", "com.google.android.youtube")
        // YouTube opened a new player, so Android now lists it first.
        val next = AppOrder.stable(shown, linkedSetOf("com.google.android.youtube", "com.apple.android.music"), label)
        assertEquals(shown, next)
    }

    @Test
    fun newcomersJoinAtTheEndAlphabetically() {
        val next = AppOrder.stable(listOf("com.google.android.youtube"), listOf("com.spotify.music", "com.google.android.youtube", "com.apple.android.music"), label)
        assertEquals(listOf("com.google.android.youtube", "com.apple.android.music", "com.spotify.music"), next)
    }

    @Test
    fun stoppedAppsLeaveWithoutMovingTheRest() {
        val next = AppOrder.stable(listOf("com.spotify.music", "com.apple.android.music", "com.google.android.youtube"), setOf("com.google.android.youtube", "com.spotify.music"), label)
        assertEquals(listOf("com.spotify.music", "com.google.android.youtube"), next)
    }

    @Test
    fun firstListIsAlphabetical() {
        val next = AppOrder.stable(emptyList(), listOf("com.google.android.youtube", "com.spotify.music", "com.apple.android.music"), label)
        assertEquals(listOf("com.apple.android.music", "com.spotify.music", "com.google.android.youtube"), next)
    }
}
