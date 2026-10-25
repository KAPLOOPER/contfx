/*
 * Continuum Keyboard: a modified version of FUTO Keyboard. This file is new in the
 * modified version and is distributed under the same FUTO Source First License 1.1-kb.
 */
package org.futo.inputmethod.latin.uix.actions.scantext

import android.content.Intent
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action

/** Text read by [ScanTextActivity], waiting to be typed when the keyboard comes back. */
object ScanTextResult {
    @Volatile
    private var pending: String? = null

    fun offer(text: String) {
        pending = text
    }

    fun take(): String? {
        val text = pending
        pending = null
        return text
    }
}

/** Scan text with the camera and type it into the field you were in. */
val ScanTextAction = Action(
    icon = R.drawable.continuum_scan_text,
    name = R.string.continuum_scan_text,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        val intent = Intent(context, ScanTextActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
        )
        context.startActivity(intent)
    },
    windowImpl = null,
)
