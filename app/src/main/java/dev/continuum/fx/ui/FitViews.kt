package dev.continuum.fx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.data.FitStore
import dev.continuum.fx.dsp.EqFit
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Making an EQ from a measurement: your earphones' response and a target, and a preview of the
 * EQ that turns one into the other before it's made.
 */
@Composable
internal fun FitCard(actions: AppActions) {
    val p = LocalPalette.current
    val fit by FitStore.state.collectAsState()
    val preview by produceState<EqFit.Result?>(null, fit) {
        val m = fit.measurement
        val t = fit.target
        value = if (m != null && t != null) {
            withContext(Dispatchers.Default) { runCatching { EqFit.fit(m, t, fit.options) }.getOrNull() }
        } else null
    }
    fun options(transform: (EqFit.Options) -> EqFit.Options) = FitStore.update { it.copy(options = transform(it.options)) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(p.surface)
            .padding(18.dp),
    ) {
        Txt("Make an EQ from a measurement", 17.sp, p.text, bold = true)
        Spacer(Modifier.size(2.dp))
        Txt(
            "Your earphones' measured response, and the target you want them to sound like. Continuum fits an EQ between the two; after that it's an EQ like any other, to edit, link and share.",
            14.sp, p.sub,
        )
        Spacer(Modifier.size(12.dp))
        FileRow("Measurement", fit.measurement?.let { "${it.name} · ${it.size} points" } ?: "Your earphones, as measured", fit.measurement != null, actions::pickMeasurement)
        Spacer(Modifier.size(8.dp))
        FileRow("Target", fit.target?.name ?: "How you want them to sound", fit.target != null, actions::pickTarget)
        Spacer(Modifier.size(4.dp))
        Txt("Text files of frequency and level, as REW, squig.link and AutoEQ write them.", 13.sp, p.sub)

        Spacer(Modifier.size(14.dp))
        Txt("Filters", 15.sp, p.sub)
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(6, 8, 10, 12).forEach { n -> Chip("$n", fit.options.filters == n, onClick = { options { it.copy(filters = n) } }, on = Surface.Card) }
        }
        Spacer(Modifier.size(10.dp))
        Txt("Most lift", 15.sp, p.sub)
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(3.0, 6.0, 9.0).forEach { d -> Chip("+${d.toInt()} dB", fit.options.maxBoostDb == d, onClick = { options { it.copy(maxBoostDb = d) } }, on = Surface.Card) }
        }
        Spacer(Modifier.size(10.dp))
        Txt("Fit up to", 15.sp, p.sub)
        Spacer(Modifier.size(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(8000.0, 10000.0, 12000.0, 16000.0).forEach { f ->
                Chip("${(f / 1000).toInt()} kHz", fit.options.fitTo == f, onClick = { options { it.copy(fitTo = f) } }, on = Surface.Card)
            }
        }
        Spacer(Modifier.size(4.dp))
        Txt("Above this the difference counts for less and less: couplers stop agreeing with ears up there, and every fit of an earphone differs.", 13.sp, p.sub)

        preview?.let { r ->
            Spacer(Modifier.size(14.dp))
            FitGraph(r, fit.options.fitTo)
            Spacer(Modifier.size(6.dp))
            Txt(
                "Off by %.1f dB as they are, %.1f dB with these %d filters.".format(r.errorBefore, r.errorAfter, r.profile.bands.size),
                14.sp, p.text,
            )
            Txt("The faint line is the difference to make up; the solid one is what the EQ does.", 13.sp, p.sub)
        }

        Spacer(Modifier.size(14.dp))
        PillButton("Make the EQ", actions::makeFittedEq, icon = R.drawable.ic_check, primary = true, on = Surface.Card, height = 40.dp)
        Spacer(Modifier.size(10.dp))
        Txt(
            "Measured on an IEC 711 coupler? KEMAR's ears are 711 ear simulators and its head is diffuse-field equalised, so with spatial audio on the KEMAR head this same EQ keeps you on your target.",
            13.sp, p.sub,
        )
    }
}

@Composable
private fun FileRow(label: String, value: String, chosen: Boolean, onChoose: () -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(label, 15.sp, p.sub, maxLines = 1)
            Txt(value, 16.sp, if (chosen) p.text else p.sub, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        PillButton(if (chosen) "Change" else "Choose", onChoose, icon = R.drawable.ic_import, height = 36.dp, on = Surface.Card)
    }
}

/** The difference to make up (faint) and what the EQ does (solid), 20 Hz to 20 kHz. */
@Composable
private fun FitGraph(r: EqFit.Result, fitTo: Double) {
    val p = LocalPalette.current
    Canvas(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(p.ground)) {
        val pad = 8.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val range = max(6.0, (r.wanted.maxOf { abs(it) } + 1.0).coerceAtMost(18.0))
        fun x(f: Double) = (pad + log10(f / 20.0) / 3.0 * w).toFloat()
        fun y(db: Double) = (pad + (1 - (db.coerceIn(-range, range) + range) / (2 * range)) * h).toFloat()
        // 0 dB, and where the fit stops counting fully.
        drawLine(p.surface, Offset(pad, y(0.0)), Offset(pad + w, y(0.0)), strokeWidth = 1.dp.toPx())
        drawLine(p.surface, Offset(x(fitTo), pad), Offset(x(fitTo), pad + h), strokeWidth = 1.dp.toPx())
        fun line(values: DoubleArray): Path = Path().apply {
            for (i in values.indices) {
                val px = x(r.frequency[i])
                val py = y(values[i])
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(line(r.wanted), p.sub, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(line(r.response), p.text, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
