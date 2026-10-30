package dev.continuum.fx.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Txt

/**
 * A rest state: what part of the app looks like with nothing in it yet. The same three things
 * every time: the resting drop (the one under the page's title, so empty reads as calm rather than
 * missing), one sentence, and at most one thing to do. No action is offered where there's nothing
 * for you to do but wait (an app has to play something first, say).
 *
 * Shown with its page, the drop is simply there; turning up while you're looking (the last filter
 * removed, say), it drips from the title's drop, as the rest of the app's ink does.
 */
@Composable
internal fun Rest(
    text: String,
    modifier: Modifier = Modifier,
    /** Kept in place while false, so the drop can rise back into the title's drop as it goes. */
    visible: Boolean = true,
    action: String? = null,
    @DrawableRes icon: Int? = null,
    on: Surface = Surface.Ground,
    onAction: () -> Unit = {},
) {
    val p = LocalPalette.current
    val seeds = LocalSeeds.current
    val drips = remember { on == Surface.Ground && seeds != null && System.currentTimeMillis() - seeds.pageAt > 700 }
    Column(modifier.fillMaxWidth().padding(vertical = if (visible) 10.dp else 0.dp)) {
        Seeded(visible = visible, corner = 4.5.dp, color = p.text, settledAtStart = !drips, modifier = Modifier.size(30.dp, 9.dp)) {}
        if (visible) {
            Gap(14.dp)
            Txt(text, 17.sp, p.text)
            if (action != null) {
                Gap(14.dp)
                PillButton(action, onAction, icon = icon, height = 38.dp, on = on)
            }
        }
    }
}
