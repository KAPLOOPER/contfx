package dev.continuum.keyboard.autofill

/**
 * Main-thread-only ownership of provider suggestions. No credential text is inspected here.
 * [inflate] must deliver its callback on the same thread as the lifecycle methods.
 *
 * Input views can exist while hidden, so their existence isn't a signal to render. Queue offers
 * until startView, discard them on finish, and never let a late remote callback revive old UI.
 */
internal class InlineSuggestionsController<S : Any, V : Any>(
    private val inflate: (S, (V?) -> Unit) -> Unit,
    private val render: (List<V>) -> Unit,
    private val report: (String) -> Unit = {},
) {
    private var inputActive = false
    private var viewActive = false
    private var panelOpen = false
    private var generation = 0L
    private var pending: List<S>? = null

    fun startInput() {
        inputActive = true
        viewActive = false
        panelOpen = false
        clear()
    }

    fun finishInput() {
        inputActive = false
        viewActive = false
        clear()
    }

    fun startView() {
        viewActive = true
        inflatePending()
    }

    fun finishView() {
        viewActive = false
        clear()
    }

    fun setPanelOpen(open: Boolean) {
        if (panelOpen == open) return
        panelOpen = open
        if (open) clear() else inflatePending()
    }

    /** True means accepted for rendering/queuing, not that Android must show fallback UI. */
    fun offer(suggestions: List<S>): Boolean {
        clear()
        if (!inputActive || suggestions.isEmpty()) return false
        pending = suggestions.toList()
        inflatePending()
        return true
    }

    fun clear() {
        generation++
        pending = null
        render(emptyList())
    }

    private fun inflatePending() {
        if (!inputActive || !viewActive || panelOpen) return
        val suggestions = pending ?: return
        pending = null
        val token = generation
        val views = MutableList<V?>(suggestions.size) { null }
        val completed = BooleanArray(suggestions.size)

        fun complete(index: Int, view: V?) {
            if (token != generation || !inputActive || !viewActive || panelOpen || completed[index]) return
            completed[index] = true
            views[index] = view
            report("inflation result: slot=$index success=${view != null}")
            // Preserve provider order even when surfaces arrive in a different order. A failed
            // or non-returning provider never reserves a blank strip: only real views are shown.
            render(views.filterNotNull())
        }

        suggestions.forEachIndexed { index, suggestion ->
            // Rendering/reporting can synchronously trigger a lifecycle change.
            if (token != generation || !inputActive || !viewActive || panelOpen) return
            try {
                inflate(suggestion) { view -> complete(index, view) }
            } catch (_: Exception) {
                // No exception message/stack: a remote provider may put sensitive data in it.
                report("inflation failed: slot=$index")
                complete(index, null)
            }
        }
    }
}
