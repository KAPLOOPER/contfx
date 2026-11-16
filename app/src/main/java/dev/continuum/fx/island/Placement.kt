package dev.continuum.fx.island

import kotlin.math.roundToInt

/** Quantise without a dead zone around zero: every fine step remains reachable. */
internal fun placementStep(value: Float, min: Float, max: Float, step: Float): Float =
    ((value / step).roundToInt() * step).coerceIn(min, max)
