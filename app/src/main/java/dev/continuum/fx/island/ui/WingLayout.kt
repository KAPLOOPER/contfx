package dev.continuum.fx.island.ui

/** Springs overshoot both ends; Compose padding may never follow them below zero. */
internal fun wingPadding(padding: Float, presence: Float): Float =
    padding.coerceAtLeast(0f) * presence.coerceIn(0f, 1f)
