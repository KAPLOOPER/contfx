package dev.continuum.keyboard.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineSuggestionsControllerTest {
    private class Host {
        val callbacks = linkedMapOf<String, (String?) -> Unit>()
        val requested = mutableListOf<String>()
        val events = mutableListOf<String>()
        val throws = mutableSetOf<String>()
        var visible = emptyList<String>()
        val controller = InlineSuggestionsController<String, String>(
            inflate = { suggestion, callback ->
                requested += suggestion
                if (suggestion in throws) error("private-provider-payload")
                callbacks[suggestion] = callback
            },
            render = { visible = it },
            report = events::add,
        )

        fun start() {
            controller.startInput()
            controller.startView()
        }

        fun complete(suggestion: String, view: String? = "$suggestion-view") {
            callbacks.getValue(suggestion)(view)
        }
    }

    @Test
    fun responseBeforeInputStartsIsNotAccepted() {
        val host = Host()
        assertFalse(host.controller.offer(listOf("a")))
        host.controller.startView()
        assertTrue(host.requested.isEmpty())
    }

    @Test
    fun coldStartQueuesUntilInputViewStarts() {
        val host = Host()
        host.controller.startInput()
        assertTrue(host.controller.offer(listOf("a")))
        assertTrue(host.requested.isEmpty())
        host.controller.startView()
        assertEquals(listOf("a"), host.requested)
        assertTrue(host.visible.isEmpty())
        host.complete("a")
        assertEquals(listOf("a-view"), host.visible)
    }

    @Test
    fun retainedButHiddenInputViewStillQueuesNewOffers() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("old"))
        host.complete("old")
        host.controller.finishView()
        assertTrue(host.visible.isEmpty())
        host.controller.offer(listOf("return-from-unlock"))
        assertFalse(host.requested.contains("return-from-unlock"))
        host.controller.startView()
        host.complete("return-from-unlock")
        assertEquals(listOf("return-from-unlock-view"), host.visible)
    }

    @Test
    fun latestQueuedResponseWinsAndInflatesOnlyOnce() {
        val host = Host()
        host.controller.startInput()
        host.controller.offer(listOf("old"))
        host.controller.offer(listOf("new"))
        host.controller.startView()
        host.controller.startView()
        assertEquals(listOf("new"), host.requested)
    }

    @Test
    fun pendingCallbacksDoNotReserveABlankSuggestionStrip() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("never-returns"))
        assertTrue(host.visible.isEmpty())
    }

    @Test
    fun allNullViewsLeaveOrdinaryKeyboardToolsVisible() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("a", "b"))
        host.complete("a", null)
        host.complete("b", null)
        assertTrue(host.visible.isEmpty())
        assertEquals(2, host.events.count { "success=false" in it })
    }

    @Test
    fun thrownInflationIsContainedAndDoesNotLogProviderPayload() {
        val host = Host()
        host.throws += "a"
        host.start()
        assertTrue(host.controller.offer(listOf("a", "b")))
        host.complete("b")
        assertEquals(listOf("b-view"), host.visible)
        assertTrue(host.events.any { "inflation failed: slot=0" == it })
        assertFalse(host.events.any { "private-provider-payload" in it })
    }

    @Test
    fun callbackOrderDoesNotChangeProviderOrder() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("first", "second", "third"))
        host.complete("third")
        assertEquals(listOf("third-view"), host.visible)
        host.complete("first")
        assertEquals(listOf("first-view", "third-view"), host.visible)
        host.complete("second")
        assertEquals(listOf("first-view", "second-view", "third-view"), host.visible)
    }

    @Test
    fun aMissingSurfaceDoesNotPreventOtherChipsFromAppearing() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("a", "b", "c"))
        host.complete("b", null)
        host.complete("c")
        host.complete("a")
        assertEquals(listOf("a-view", "c-view"), host.visible)
    }

    @Test
    fun replacedResponseCannotReinsertOldViews() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("old"))
        host.controller.offer(listOf("new"))
        host.complete("old")
        assertTrue(host.visible.isEmpty())
        host.complete("new")
        assertEquals(listOf("new-view"), host.visible)
    }

    @Test
    fun emptyFrameworkResponseClearsAndInvalidatesOldCallbacks() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("old"))
        assertFalse(host.controller.offer(emptyList()))
        host.complete("old")
        assertTrue(host.visible.isEmpty())
    }

    @Test
    fun restartInputClearsPreviousFieldAndWaitsForStartView() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("old-field"))
        host.controller.startInput()
        host.complete("old-field")
        assertTrue(host.visible.isEmpty())
        host.controller.offer(listOf("new-field"))
        assertFalse(host.requested.contains("new-field"))
        host.controller.startView()
        host.complete("new-field")
        assertEquals(listOf("new-field-view"), host.visible)
    }

    @Test
    fun finishingViewInvalidatesInflationAndQueuedOffers() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("old"))
        host.controller.finishView()
        host.complete("old")
        host.controller.startView()
        assertTrue(host.visible.isEmpty())
        assertEquals(listOf("old"), host.requested)
    }

    @Test
    fun finishingInputWithoutEverShowingViewDropsPendingResponse() {
        val host = Host()
        host.controller.startInput()
        host.controller.offer(listOf("old"))
        host.controller.finishInput()
        host.controller.startView()
        assertTrue(host.requested.isEmpty())
        assertFalse(host.controller.offer(listOf("after-finish")))
    }

    @Test
    fun openingPanelInvalidatesInflationAndDefersNewResponses() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("old"))
        host.controller.setPanelOpen(true)
        host.complete("old")
        assertTrue(host.visible.isEmpty())
        host.controller.offer(listOf("new"))
        assertFalse(host.requested.contains("new"))
        host.controller.setPanelOpen(false)
        host.complete("new")
        assertEquals(listOf("new-view"), host.visible)
    }

    @Test
    fun duplicateCallbacksCannotInsertDuplicateChips() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("a"))
        host.complete("a")
        host.complete("a", "unexpected-second-view")
        assertEquals(listOf("a-view"), host.visible)
    }

    @Test
    fun featureDisableClearInvalidatesPendingInflation() {
        val host = Host()
        host.start()
        host.controller.offer(listOf("a"))
        host.controller.clear()
        host.complete("a")
        assertTrue(host.visible.isEmpty())
    }

    @Test
    fun synchronousCallbacksAreSupported() {
        var views = emptyList<Int>()
        val controller = InlineSuggestionsController<Int, Int>(
            inflate = { suggestion, callback -> callback(suggestion * 10) },
            render = { views = it },
        )
        controller.startInput()
        controller.startView()
        controller.offer(listOf(1, 2, 3))
        assertEquals(listOf(10, 20, 30), views)
    }

    @Test
    fun lifecycleChangeDuringSynchronousRenderingStopsRemainingInflations() {
        val requested = mutableListOf<Int>()
        lateinit var controller: InlineSuggestionsController<Int, Int>
        controller = InlineSuggestionsController(
            inflate = { suggestion, callback -> requested += suggestion; callback(suggestion) },
            render = { if (it.isNotEmpty()) controller.finishInput() },
        )
        controller.startInput()
        controller.startView()
        controller.offer(listOf(1, 2, 3))
        assertEquals(listOf(1), requested)
    }
}
