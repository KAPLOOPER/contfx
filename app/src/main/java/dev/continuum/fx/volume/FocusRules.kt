package dev.continuum.fx.volume

/** Someone asking for audio focus, as much as the rules need of it. */
data class FocusAsk(
    val client: String,
    val pkg: String,
    val uid: Int,
    /** AudioManager.AUDIOFOCUS_GAIN* */
    val gain: Int,
    /** AudioManager.AUDIOFOCUS_FLAG_* */
    val flags: Int,
    /** AudioAttributes.USAGE_* */
    val usage: Int,
    val sdk: Int,
)

/** What the rules decide; the helper carries it out. */
sealed interface FocusAction {
    /** The answer to a request: [result] is AUDIOFOCUS_REQUEST_FAILED, _GRANTED or _DELAYED. */
    data class Reply(val client: String, val result: Int) : FocusAction

    /** Tell [client] its focus changed ([change] is AUDIOFOCUS_GAIN or a loss). */
    data class Dispatch(val client: String, val change: Int) : FocusAction

    /** Lower (or restore) everything [uid] is playing, for a moment, under a navigation prompt. */
    data class Duck(val uid: Int) : FocusAction
    data class Unduck(val uid: Int) : FocusAction
}

/**
 * Audio focus as Android runs it, with one difference: apps that play alongside. Taken from
 * Android's own MediaFocusControl and FocusRequester: a request takes focus from whoever had
 * it (for good, or for a while, or just ducking them under it), losses only ever grow until
 * focus comes back, focus returns to the next in line when the top lets go, a navigation prompt
 * ducks rather than pauses, and while a phone call holds focus, others wait or are refused.
 *
 * The difference: for ordinary media (music, video, games), an app in [alongside] neither takes
 * focus from others nor loses it to them, so it keeps playing whatever else starts, and doesn't
 * stop anything when it does. Calls, alarms, the ringtone and anything else that isn't media
 * still pause it, and navigation still ducks it.
 */
class FocusRules {
    var alongside: Set<String> = emptySet()

    private class Entry(val ask: FocusAsk) {
        /** The loss it's been given (0 = it has focus). */
        var loss = 0
        /** Whether that loss was told to the app (a duck is done for it, quietly). */
        var notified = false
        var ducked = false
    }

    private val stack = ArrayList<Entry>() // the top is the end
    private val delayed = ArrayList<FocusAsk>()

    /** Who holds focus, top first, for tests and diagnosis. */
    fun holders(): List<String> = stack.reversed().map { it.ask.client }

    /** Who's waiting for a call to end. */
    fun waiting(): List<String> = delayed.map { it.client }

    fun request(ask: FocusAsk): List<FocusAction> {
        val out = ArrayList<FocusAction>()
        val top = stack.lastOrNull()
        // Already on top with the same request: nothing changes.
        if (top != null && top.ask.client == ask.client && top.ask.gain == ask.gain) {
            out += FocusAction.Reply(ask.client, GRANTED)
            return out
        }
        // A call holds focus: others wait for it to end, if they're willing, or are refused.
        if (top != null && locks(top.ask) && !locks(ask)) {
            delayed.removeAll { it.client == ask.client }
            if (ask.flags and FLAG_DELAY_OK != 0) {
                delayed += ask
                out += FocusAction.Reply(ask.client, DELAYED)
            } else {
                out += FocusAction.Reply(ask.client, FAILED)
            }
            return out
        }
        grant(ask, out)
        out += FocusAction.Reply(ask.client, GRANTED)
        return out
    }

    fun abandon(client: String): List<FocusAction> {
        val out = ArrayList<FocusAction>()
        if (delayed.removeAll { it.client == client }) return out
        val i = stack.indexOfFirst { it.ask.client == client }
        if (i < 0) return out
        val wasTop = i == stack.lastIndex
        val gone = stack.removeAt(i)
        if (gone.ducked) {
            gone.ducked = false
            unduckIfClear(gone.ask.uid, out)
        }
        if (!wasTop) return out
        // A call ended with others waiting: they're granted now, in the order they asked.
        if (delayed.isNotEmpty() && stack.lastOrNull()?.let { locks(it.ask) } != true) {
            val waiting = delayed.toList()
            delayed.clear()
            for (ask in waiting) {
                grant(ask, out)
                out += FocusAction.Dispatch(ask.client, GAIN)
            }
            return out
        }
        stack.lastOrNull()?.let { regain(it, out) }
        return out
    }

    /** Ends everything (the helper is going): every duck is lifted. */
    fun clear(): List<FocusAction> {
        val uids = stack.filter { it.ducked }.map { it.ask.uid }.toSet()
        stack.clear()
        delayed.clear()
        return uids.map { FocusAction.Unduck(it) }
    }

    private fun grant(ask: FocusAsk, out: MutableList<FocusAction>) {
        // Asking again replaces the old request, quietly.
        val old = stack.indexOfFirst { it.ask.client == ask.client }
        if (old >= 0) {
            val e = stack.removeAt(old)
            if (e.ducked) {
                e.ducked = false
                unduckIfClear(e.ask.uid, out)
            }
        }
        // Everyone already holding focus hears what this request costs them.
        val iterator = stack.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            if (exempt(ask, e.ask)) continue
            val loss = lossFor(ask.gain, e.loss)
            if (loss == e.loss) continue
            e.loss = loss
            if (loss == LOSS_DUCK && ducks(e.ask)) {
                // Android lowers it itself and doesn't tell the app.
                if (!e.ducked) {
                    val already = uidDucked(e.ask.uid)
                    e.ducked = true
                    if (!already) out += FocusAction.Duck(e.ask.uid)
                }
            } else {
                e.notified = true
                out += FocusAction.Dispatch(e.ask.client, loss)
            }
            if (loss == LOSS) {
                iterator.remove()
                if (e.ducked) {
                    e.ducked = false
                    unduckIfClear(e.ask.uid, out)
                }
            }
        }
        stack += Entry(ask)
    }

    private fun regain(e: Entry, out: MutableList<FocusAction>) {
        if (e.loss == 0) return
        e.loss = 0
        if (e.ducked) {
            e.ducked = false
            unduckIfClear(e.ask.uid, out)
        }
        if (e.notified) {
            e.notified = false
            out += FocusAction.Dispatch(e.ask.client, GAIN)
        }
    }

    private fun uidDucked(uid: Int) = stack.any { it.ask.uid == uid && it.ducked }

    private fun unduckIfClear(uid: Int, out: MutableList<FocusAction>) {
        if (!uidDucked(uid)) out += FocusAction.Unduck(uid)
    }

    /** Whether [holder] keeps playing through [ask] because one of them plays alongside. */
    private fun exempt(ask: FocusAsk, holder: FocusAsk): Boolean {
        if (ask.gain == GAIN_MAY_DUCK) return false
        if (!media(ask) || !media(holder)) return false
        return ask.pkg in alongside || holder.pkg in alongside
    }

    /** Android ducks it for the app, unless the app asked to pause instead or predates ducking. */
    private fun ducks(holder: FocusAsk) = holder.flags and FLAG_PAUSES_ON_DUCKABLE_LOSS == 0 && holder.sdk >= 26

    private fun media(a: FocusAsk) = a.usage == USAGE_UNKNOWN || a.usage == USAGE_MEDIA || a.usage == USAGE_GAME

    private fun locks(a: FocusAsk) = a.client == CALL_CLIENT || a.flags and FLAG_LOCK != 0

    companion object {
        const val FAILED = 0
        const val GRANTED = 1
        const val DELAYED = 2

        const val GAIN = 1
        const val GAIN_TRANSIENT = 2
        const val GAIN_MAY_DUCK = 3
        const val GAIN_EXCLUSIVE = 4
        const val LOSS = -1
        const val LOSS_TRANSIENT = -2
        const val LOSS_DUCK = -3

        const val FLAG_DELAY_OK = 1
        const val FLAG_PAUSES_ON_DUCKABLE_LOSS = 2
        const val FLAG_LOCK = 4

        const val USAGE_UNKNOWN = 0
        const val USAGE_MEDIA = 1
        const val USAGE_GAME = 14

        /** The client id Android's phone uses for rings and calls. */
        const val CALL_CLIENT = "AudioFocus_For_Phone_Ring_And_Calls"

        /** The loss a holder takes from a [gain] request, given the loss it already has. */
        fun lossFor(gain: Int, current: Int): Int = when (gain) {
            GAIN -> LOSS
            GAIN_TRANSIENT, GAIN_EXCLUSIVE -> if (current == LOSS) LOSS else LOSS_TRANSIENT
            GAIN_MAY_DUCK -> when (current) {
                0, LOSS_DUCK -> LOSS_DUCK
                LOSS_TRANSIENT -> LOSS_TRANSIENT
                else -> LOSS
            }
            else -> current
        }
    }
}
