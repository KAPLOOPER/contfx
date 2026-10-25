package dev.continuum.fx.volume

/** The order app rows are shown in. */
object AppOrder {
    /**
     * [current] in the order of [previous], with newcomers after it, alphabetically by [label].
     * Android lists players in no lasting order, so going by its list would shuffle the rows
     * under your finger.
     */
    fun stable(previous: List<String>, current: Collection<String>, label: (String) -> String): List<String> {
        val now = current.toSet()
        val shown = previous.toSet()
        val kept = previous.filter { it in now }
        val added = now.filter { it !in shown }.sortedWith(compareBy({ label(it).lowercase() }, { it }))
        return kept + added
    }
}
