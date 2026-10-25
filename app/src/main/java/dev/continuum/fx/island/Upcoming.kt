package dev.continuum.fx.island

/** A calendar event, as much as the island needs of it. */
data class EventInfo(
    /** The event's id in the calendar, for opening it. */
    val id: Long,
    val title: String,
    /** Wall-clock start and end of this occurrence. */
    val begin: Long,
    val end: Long,
    val location: String?,
    /** A video call link found in the event, for Join. */
    val joinUrl: String?,
    val demo: Boolean = false,
)

/**
 * When an event belongs in the island and what it says there: from a quarter of an hour
 * before it starts until five minutes in, counting down by the minute.
 */
object Upcoming {
    const val LEAD_MS = 15 * 60_000L
    const val LINGER_MS = 5 * 60_000L

    /** Whether [e] sits in the island at [now]. */
    fun shows(e: EventInfo, now: Long): Boolean =
        now >= e.begin - LEAD_MS && now < minOf(e.begin + LINGER_MS, e.end)

    /** "in 12 min" while it's coming, "Now" once it's (all but) started. */
    fun label(e: EventInfo, now: Long): String {
        val left = e.begin - now
        if (left <= 30_000L) return "Now"
        return "in ${(left + 59_999L) / 60_000L} min"
    }

    /** The event to follow: the earliest that hasn't finished being shown. */
    fun pick(events: List<EventInfo>, now: Long): EventInfo? =
        events.filter { it.end > now && it.begin + LINGER_MS > now }.minByOrNull { it.begin }

    /** When what the island shows for [e] next changes, so it's checked again then. */
    fun nextChange(e: EventInfo, now: Long): Long = listOf(e.begin - LEAD_MS, e.begin - 30_000L, minOf(e.begin + LINGER_MS, e.end))
        .filter { it > now }
        .minOrNull() ?: (now + 60_000L)

    private val url = Regex("""https?://[^\s<>"'()\[\]{}]+""", RegexOption.IGNORE_CASE)

    private val meetingHosts = listOf(
        "meet.google.com", "zoom.us", "zoomgov.com", "teams.microsoft.com", "teams.live.com",
        "webex.com", "whereby.com", "meet.jit.si", "gotomeeting.com", "chime.aws", "bluejeans.com",
    )

    /** The first video call link (Meet, Zoom, Teams and the like) in [texts], if any. */
    fun meetingLink(vararg texts: String?): String? {
        for (t in texts) {
            if (t.isNullOrBlank()) continue
            for (m in url.findAll(t)) {
                val link = m.value.trimEnd('.', ',', ';', ':', '!', '?')
                val host = link.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                    .substringAfterLast('@').substringBefore(':').lowercase()
                if (meetingHosts.any { host == it || host.endsWith(".$it") }) return link
            }
        }
        return null
    }
}
