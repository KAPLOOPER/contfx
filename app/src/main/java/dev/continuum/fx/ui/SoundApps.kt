package dev.continuum.fx.ui

import androidx.compose.foundation.layout.heightIn
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.audio.EngineMode
import dev.continuum.fx.audio.EngineSettings
import dev.continuum.fx.audio.EngineStatus
import dev.continuum.fx.audio.HeardApps
import dev.continuum.fx.data.Library
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.OnOffDot
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Txt
import dev.continuum.fx.volume.AppVolumes

/** An app's name, from the system, or its package name when it can't be had. */
internal fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) {
    pkg
}

private fun appIcon(context: Context, pkg: String, px: Int): Bitmap? = try {
    val d = context.packageManager.getApplicationIcon(pkg)
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(b))
    }
} catch (_: Exception) {
    null
}

@Composable
internal fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 26.dp.roundToPx() }
    val bmp = remember(pkg) { appIcon(context, pkg, px) }
    if (bmp != null) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape))
    } else {
        Image(painterResource(R.drawable.ic_music), contentDescription = null, modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(LocalPalette.current.sub))
    }
}

/** A player's Equalizer button brought you here: offer to give that app this EQ. */
@Composable
internal fun OpenedFromRow(pkg: String, library: Library, actions: AppActions) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val name = remember(pkg) { appLabel(context, pkg) }
    val profile = library.active
    val current = library.appProfile(pkg)
    // On ink that the caller provides (it drips from the title's drop).
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(pkg)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Txt("From $name", 16.sp, p.onInk, bold = true, maxLines = 1)
            Txt(
                if (current?.id == profile.id) "$name uses ${profile.name}" else "Give $name ${profile.name}, just for it?",
                13.sp, p.onInkSub, maxLines = 2,
            )
        }
        if (current?.id != profile.id) {
            Chip("Give it", false, onClick = { actions.linkApp(pkg, profile.id) }, on = Surface.Ink)
        }
        IconBubble(R.drawable.ic_check, "Done", onClick = actions::dismissOpenedFrom, on = Surface.Ink)
    }
}

/**
 * Which apps use this EQ of their own. In Direct mode each app's sound is separate, so each
 * gets its own; in Capture mode everything is mixed together first, so an app's own EQ is used
 * whenever it's what's playing.
 */
@Composable
internal fun AppsList(library: Library, actions: AppActions) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val profile = library.active
    val heard by HeardApps.apps.collectAsState()
    val known by AppVolumes.known.collectAsState()
    val playing by EngineStatus.processingPackages.collectAsState()
    val mode by EngineSettings.mode.collectAsState()
    val list = (playing + heard + known + library.apps.keys).distinct()
    Column(Modifier.padding(bottom = 8.dp)) {
        Txt(
            if (mode == EngineMode.Direct) "Each app plays through its own EQ, side by side."
            else "An app's own EQ is used while it's what's playing.",
            14.sp, p.sub, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        if (list.isEmpty()) {
            Rest("Apps show up here once they've played something through the EQ.")
        }
        list.forEach { pkg ->
            key(pkg) {
                val own = library.appProfile(pkg)
                val on = own?.id == profile.id
                val name = remember(pkg) { appLabel(context, pkg) }
                val rowSource = remember { MutableInteractionSource() }
                val toggle = { actions.linkApp(pkg, if (on) null else profile.id) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = rowSource, indication = null, role = Role.Switch, onClick = toggle)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(pkg)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(name, 16.sp, if (on) p.text else p.sub, maxLines = 1)
                        if (own != null && !on) Txt("Uses ${own.name}", 13.sp, p.sub, maxLines = 1)
                    }
                    OnOffDot(on, R.drawable.ic_check, "$name uses ${profile.name}", toggle)
                }
            }
        }
    }
}

