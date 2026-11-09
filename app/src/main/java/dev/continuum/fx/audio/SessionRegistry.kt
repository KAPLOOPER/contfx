package dev.continuum.fx.audio

/**
 * Which effect is on which session, and whose session it is. Android reuses session numbers, so
 * a number that now belongs to another app gets its old effect taken off first; an effect that
 * couldn't be put on isn't counted (it's tried again at the next check). Kept apart from Android
 * so it can be tested on the JVM; SessionWatcher calls it under its lock.
 */
internal class SessionRegistry<T>(private val release: (T) -> Unit) {
    private class Entry<T>(val pkg: String, val effect: T)
    private val entries = LinkedHashMap<Int, Entry<T>>()

    /** Brings the effects in line with [candidates] (session to app); returns those in place. */
    fun sync(candidates: Map<Int, String>, attach: (Int, String) -> T?): Map<Int, String> {
        val stale = entries.filter { (id, entry) -> candidates[id] != entry.pkg }.keys
        stale.forEach { id -> entries.remove(id)?.let { runCatching { release(it.effect) } } }
        candidates.forEach { (id, pkg) ->
            if (id !in entries) attach(id, pkg)?.let { entries[id] = Entry(pkg, it) }
        }
        return entries.mapValues { it.value.pkg }
    }

    fun effects(): List<T> = entries.values.map { it.effect }

    fun clear() {
        entries.values.forEach { runCatching { release(it.effect) } }
        entries.clear()
    }
}
