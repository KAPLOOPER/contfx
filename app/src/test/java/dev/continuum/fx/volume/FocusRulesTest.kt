package dev.continuum.fx.volume

import dev.continuum.fx.volume.FocusAction.Dispatch
import dev.continuum.fx.volume.FocusAction.Duck
import dev.continuum.fx.volume.FocusAction.Reply
import dev.continuum.fx.volume.FocusAction.Unduck
import dev.continuum.fx.volume.FocusRules.Companion.DELAYED
import dev.continuum.fx.volume.FocusRules.Companion.FAILED
import dev.continuum.fx.volume.FocusRules.Companion.GAIN
import dev.continuum.fx.volume.FocusRules.Companion.GAIN_MAY_DUCK
import dev.continuum.fx.volume.FocusRules.Companion.GAIN_TRANSIENT
import dev.continuum.fx.volume.FocusRules.Companion.GRANTED
import dev.continuum.fx.volume.FocusRules.Companion.LOSS
import dev.continuum.fx.volume.FocusRules.Companion.LOSS_DUCK
import dev.continuum.fx.volume.FocusRules.Companion.LOSS_TRANSIENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusRulesTest {
    private fun app(pkg: String, uid: Int, gain: Int = GAIN, usage: Int = 1, flags: Int = 0, client: String = "$pkg#1") =
        FocusAsk(client, pkg, uid, gain, flags, usage, 34)

    private val spotify = app("com.spotify.music", 10)
    private val youtube = app("com.google.android.youtube", 11)
    private val maps = app("com.google.android.apps.maps", 12, gain = GAIN_MAY_DUCK, usage = 12)
    private val alarm = app("com.google.android.deskclock", 13, gain = GAIN_TRANSIENT, usage = 4)
    private val call = FocusAsk(FocusRules.CALL_CLIENT, "com.android.server.telecom", 1000, GAIN_TRANSIENT, 4, 2, 34)

    @Test
    fun withNothingAlongsideItBehavesAsAndroid() {
        val r = FocusRules()
        assertEquals(listOf(Reply(spotify.client, GRANTED)), r.request(spotify))
        // YouTube starts: Spotify is told it lost focus for good, and leaves the stack.
        assertEquals(listOf(Dispatch(spotify.client, LOSS), Reply(youtube.client, GRANTED)), r.request(youtube))
        assertEquals(listOf(youtube.client), r.holders())
    }

    @Test
    fun anAppAlongsideKeepsPlayingWhenAnotherStarts() {
        val r = FocusRules().apply { alongside = setOf(spotify.pkg) }
        r.request(spotify)
        // YouTube starts: Spotify hears nothing, and keeps playing.
        assertEquals(listOf(Reply(youtube.client, GRANTED)), r.request(youtube))
        assertEquals(listOf(youtube.client, spotify.client), r.holders())
        // YouTube stops: Spotify never lost anything, so it's told nothing.
        assertEquals(emptyList<FocusAction>(), r.abandon(youtube.client))
    }

    @Test
    fun anAppAlongsideDoesntPauseOthersWhenItStarts() {
        val r = FocusRules().apply { alongside = setOf(youtube.pkg) }
        r.request(spotify)
        assertEquals(listOf(Reply(youtube.client, GRANTED)), r.request(youtube))
    }

    @Test
    fun aPassingRequestHandsFocusBack() {
        val r = FocusRules()
        r.request(spotify)
        assertEquals(listOf(Dispatch(spotify.client, LOSS_TRANSIENT), Reply(alarm.client, GRANTED)), r.request(alarm))
        assertEquals(listOf(Dispatch(spotify.client, GAIN)), r.abandon(alarm.client))
    }

    @Test
    fun alarmsStillPauseAnAppAlongside() {
        val r = FocusRules().apply { alongside = setOf(spotify.pkg) }
        r.request(spotify)
        assertEquals(listOf(Dispatch(spotify.client, LOSS_TRANSIENT), Reply(alarm.client, GRANTED)), r.request(alarm))
    }

    @Test
    fun navigationDucksQuietlyAndLiftsAfter() {
        val r = FocusRules().apply { alongside = setOf(spotify.pkg) }
        r.request(spotify)
        assertEquals(listOf(Duck(10), Reply(maps.client, GRANTED)), r.request(maps))
        assertEquals(listOf(Unduck(10)), r.abandon(maps.client))
    }

    @Test
    fun anAppThatPausesForPromptsIsToldInstead() {
        val podcast = app("au.com.shiftyjelly.pocketcasts", 20, flags = FocusRules.FLAG_PAUSES_ON_DUCKABLE_LOSS)
        val r = FocusRules()
        r.request(podcast)
        assertEquals(listOf(Dispatch(podcast.client, LOSS_DUCK), Reply(maps.client, GRANTED)), r.request(maps))
        assertEquals(listOf(Dispatch(podcast.client, GAIN)), r.abandon(maps.client))
    }

    @Test
    fun aCallPausesEverythingAndOthersWaitForIt() {
        val r = FocusRules().apply { alongside = setOf(spotify.pkg) }
        r.request(spotify)
        assertEquals(listOf(Dispatch(spotify.client, LOSS_TRANSIENT), Reply(call.client, GRANTED)), r.request(call))
        // During the call: refused, unless willing to wait.
        assertEquals(listOf(Reply(youtube.client, FAILED)), r.request(youtube))
        val patient = youtube.copy(flags = FocusRules.FLAG_DELAY_OK)
        assertEquals(listOf(Reply(patient.client, DELAYED)), r.request(patient))
        // The call ends: the one waiting gets focus (Spotify plays alongside, so it isn't paused for it).
        val after = r.abandon(call.client)
        assertTrue(after.contains(Dispatch(patient.client, GAIN)))
    }

    @Test
    fun afterACallFocusReturns() {
        val r = FocusRules()
        r.request(spotify)
        r.request(call)
        assertEquals(listOf(Dispatch(spotify.client, GAIN)), r.abandon(call.client))
    }

    @Test
    fun askingAgainOnTopChangesNothing() {
        val r = FocusRules()
        r.request(spotify)
        assertEquals(listOf(Reply(spotify.client, GRANTED)), r.request(spotify))
        assertEquals(listOf(spotify.client), r.holders())
    }

    @Test
    fun lossesOnlyGrowUntilFocusReturns() {
        assertEquals(LOSS_TRANSIENT, FocusRules.lossFor(GAIN_TRANSIENT, LOSS_DUCK))
        assertEquals(LOSS, FocusRules.lossFor(GAIN_TRANSIENT, LOSS))
        assertEquals(LOSS_TRANSIENT, FocusRules.lossFor(GAIN_MAY_DUCK, LOSS_TRANSIENT))
        assertEquals(LOSS, FocusRules.lossFor(GAIN, LOSS_DUCK))
    }

    @Test
    fun goingAwayLiftsEveryDuck() {
        val r = FocusRules()
        r.request(spotify)
        r.request(maps)
        assertEquals(listOf(Unduck(10)), r.clear())
        assertEquals(emptyList<String>(), r.holders())
    }
}
