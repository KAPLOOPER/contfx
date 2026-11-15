/*
 * The island's small change: how times, timers and calls read, the words for sound modes and
 * devices, and the few plain pieces (a tinted glyph, a round button, a flat pill) the reply card
 * and the volume sheet share with it.
 */

package dev.continuum.fx.island.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.continuum.fx.R
import dev.continuum.fx.island.BatteryInfo
import dev.continuum.fx.island.CallInfo
import dev.continuum.fx.island.LiveInfo
import dev.continuum.fx.island.TimerInfo
import dev.continuum.fx.ui.components.squash
import dev.continuum.fx.ui.theme.Txt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// ---- Formatting ----

/** A span of time as a clock reads it: 4:05, or 1:04:05 past the hour; negative with a minus. */
internal fun clock(ms: Long): String {
    val negative = ms < 0
    val total = abs(ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val body = if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    return if (negative) "−$body" else body
}

/** What a timer reads now: the time left on a countdown (rounded up, as a clock app does), or the time run. */
internal fun timerText(t: TimerInfo, now: Long): String =
    if (t.countDown) {
        val left = t.base - now
        clock(if (left > 0) left + 999 else left)
    } else {
        clock(now - t.base)
    }

/** How long a call has run, or just "Call" when the dialer doesn't say when it started. */
internal fun callText(c: CallInfo, now: Long): String =
    c.startedAt?.let { clock((now - it).coerceAtLeast(0)) } ?: "Call"

/** The time of day in the phone's own 12 or 24 hours, without AM/PM; just the hour with [hoursOnly]. */
internal fun clockText(context: Context, at: Long, hoursOnly: Boolean = false): String {
    val h24 = DateFormat.is24HourFormat(context)
    val pattern = when {
        hoursOnly && h24 -> "HH"
        hoursOnly -> "h"
        h24 -> "H:mm"
        else -> "h:mm"
    }
    return Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
}

internal fun minutesText(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("mm"))

internal fun weekday(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))

internal fun dayMonth(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))

/** How long ago, in the fewest characters: now, 4m, 2h, 3d. */
internal fun ago(at: Long, now: Long): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }
}

/** A length of time in words, rounded up to the minute: 8 min, 2 h, 1 h 20 min. */
internal fun duration(ms: Long): String {
    val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

/** A code split in two for reading ("482 913"); copied without the space. */
internal fun spacedCode(code: String): String = when {
    code.length == 6 -> code.substring(0, 3) + " " + code.substring(3)
    code.length == 8 -> code.substring(0, 4) + " " + code.substring(4)
    else -> code
}

/** What the battery is doing, in a word or two. */
internal fun batteryWord(b: BatteryInfo): String = when {
    b.full -> "charged"
    b.charging && b.wireless -> "charging wirelessly"
    b.charging -> "charging"
    b.low -> "low"
    else -> "${b.level}% left"
}

/** What a live item shows on the band: the app's own few characters, its percentage, or its name. */
internal fun liveShort(l: LiveInfo): String? = when {
    l.short != null -> l.short
    l.progress != null && l.progress >= 0f -> "${(l.progress * 100).toInt()}%"
    l.progress != null -> null
    else -> l.title.takeIf { it.isNotBlank() }?.let { if (it.length > 14) it.take(13) + "…" else it }
}

@DrawableRes
internal fun soundIcon(mode: Int): Int = when (mode) {
    0 -> R.drawable.ic_bell_off
    1 -> R.drawable.ic_vibrate
    else -> R.drawable.ic_bell
}

internal fun soundLabel(mode: Int): String = when (mode) {
    0 -> "Silent"
    1 -> "Vibrate"
    else -> "Ring"
}

/** A device's name, short enough for the band. */
internal fun outputLabel(name: String): String = if (name.length > 18) name.take(17).trimEnd() + "…" else name

/** "Today", "Tomorrow" or the day's name, for an alarm time. */
internal fun alarmDay(at: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
    }
}

/** The alarm's time in the phone's own style, with AM/PM when it uses them. */
internal fun alarmTime(context: Context, at: Long): String =
    DateFormat.getTimeFormat(context).format(java.util.Date(at))

// ---- Motion ----

/**
 * An endlessly repeating 0..1 phase that runs only while [running]. Stopped, nothing asks for a
 * new frame, so a still island isn't redrawn at all.
 */
@Composable
internal fun loopPhase(running: Boolean, periodMs: Int, label: String): State<Float> {
    if (!running) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = label).animateFloat(
        0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = label,
    )
}

// ---- Plain pieces ----

@Composable
internal fun Glyph(@DrawableRes icon: Int, tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Image(painterResource(icon), contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/** An app's small icon from its notification, tinted; the island's own mark when it has none. */
@Composable
internal fun AppGlyph(icon: android.graphics.drawable.Icon?, tint: Color, size: Dp) {
    val context = LocalContext.current
    val image = remember(icon) {
        try {
            icon?.loadDrawable(context)?.toBitmap(72, 72)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (image != null) {
        Image(image, contentDescription = null, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(tint))
    } else {
        Glyph(R.drawable.ic_island, tint, size)
    }
}

/** A solid round button, with the Continuum squash. */
@Composable
internal fun RoundButton(
    @DrawableRes icon: Int,
    description: String,
    bg: Color,
    fg: Color,
    size: Dp,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .squash(source)
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, fg, size * 0.42f)
    }
}

/** A flat pill with any fill. */
@Composable
internal fun InkPill(
    text: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .squash(source, pill = true)
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Glyph(icon, fg, 18.dp)
        Txt(text, 15.sp, fg, maxLines = 1)
    }
}
