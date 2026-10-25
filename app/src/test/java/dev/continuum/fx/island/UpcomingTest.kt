package dev.continuum.fx.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingTest {
    private val min = 60_000L
    private fun event(begin: Long, end: Long = begin + 30 * min, title: String = "Standup") =
        EventInfo(id = 1, title = title, begin = begin, end = end, location = null, joinUrl = null)

    @Test
    fun showsFromAQuarterHourBeforeToFiveMinutesIn() {
        val e = event(begin = 100 * min)
        assertFalse(Upcoming.shows(e, 84 * min))
        assertTrue(Upcoming.shows(e, 85 * min))
        assertTrue(Upcoming.shows(e, 104 * min))
        assertFalse(Upcoming.shows(e, 105 * min))
    }

    @Test
    fun aShortEventLeavesWhenItEnds() {
        val e = event(begin = 100 * min, end = 102 * min)
        assertTrue(Upcoming.shows(e, 101 * min))
        assertFalse(Upcoming.shows(e, 102 * min))
    }

    @Test
    fun countsDownInWholeMinutes() {
        val e = event(begin = 100 * min)
        assertEquals("in 15 min", Upcoming.label(e, 85 * min))
        assertEquals("in 12 min", Upcoming.label(e, 88 * min + 10_000))
        assertEquals("in 1 min", Upcoming.label(e, 99 * min))
        assertEquals("Now", Upcoming.label(e, 100 * min - 20_000))
        assertEquals("Now", Upcoming.label(e, 102 * min))
    }

    @Test
    fun picksTheEarliestStillWorthShowing() {
        val started = event(begin = 97 * min, title = "Started")
        val next = event(begin = 110 * min, title = "Next")
        val long = event(begin = 60 * min, end = 200 * min, title = "Long ago")
        assertEquals("Started", Upcoming.pick(listOf(next, started, long), 100 * min)?.title)
        assertEquals("Next", Upcoming.pick(listOf(next, started, long), 103 * min)?.title)
        assertNull(Upcoming.pick(listOf(long), 100 * min))
    }

    @Test
    fun checksAgainWhenTheIslandWouldChange() {
        val e = event(begin = 100 * min)
        assertEquals(85 * min, Upcoming.nextChange(e, 80 * min))
        assertEquals(100 * min - 30_000, Upcoming.nextChange(e, 90 * min))
        assertEquals(105 * min, Upcoming.nextChange(e, 101 * min))
    }

    @Test
    fun findsVideoCallLinks() {
        assertEquals("https://meet.google.com/abc-defg-hij", Upcoming.meetingLink(null, "Join: https://meet.google.com/abc-defg-hij."))
        assertEquals(
            "https://us02web.zoom.us/j/8812345678?pwd=xyz",
            Upcoming.meetingLink("Room 4", "<a href=\"https://us02web.zoom.us/j/8812345678?pwd=xyz\">Zoom</a>"),
        )
        assertEquals(
            "https://teams.microsoft.com/l/meetup-join/19%3ameeting",
            Upcoming.meetingLink("https://teams.microsoft.com/l/meetup-join/19%3ameeting"),
        )
        assertNull(Upcoming.meetingLink("https://example.com/agenda", "Call me on 0207 946 0000"))
        assertNull(Upcoming.meetingLink("https://notzoom.us.evil.com/j/1"))
    }
}
