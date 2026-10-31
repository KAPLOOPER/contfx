package dev.continuum.fx.data

import android.content.Context
import dev.continuum.fx.dsp.Curve
import dev.continuum.fx.dsp.EqFit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What an EQ is fitted from: your earphones' measured response, the target, and how to fit. */
data class FitInputs(
    val measurement: Curve? = null,
    val target: Curve? = null,
    val options: EqFit.Options = EqFit.Options(),
)

/** The fit's inputs, kept so you can come back and fit again (another target, more filters). */
object FitStore {
    private const val FILE = "fit"
    private const val KEY = "fit_v1"
    private val _state = MutableStateFlow(FitInputs())
    val state: StateFlow<FitInputs> = _state.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        _state.value = raw?.let { runCatching { decode(JSONObject(it)) }.getOrNull() } ?: FitInputs()
    }

    @Synchronized
    fun update(transform: (FitInputs) -> FitInputs) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString(KEY, encode(next).toString())?.apply()
    }

    private fun encode(c: Curve) = JSONObject()
        .put("name", c.name)
        .put("f", JSONArray().also { a -> c.frequency.forEach { a.put(it) } })
        .put("db", JSONArray().also { a -> c.db.forEach { a.put(it) } })

    private fun decodeCurve(o: JSONObject?): Curve? {
        if (o == null) return null
        val f = o.optJSONArray("f") ?: return null
        val d = o.optJSONArray("db") ?: return null
        if (f.length() != d.length() || f.length() < 10) return null
        return Curve(o.optString("name", "Response"), DoubleArray(f.length()) { f.getDouble(it) }, DoubleArray(d.length()) { d.getDouble(it) })
    }

    private fun encode(s: FitInputs): JSONObject = JSONObject().apply {
        s.measurement?.let { put("measurement", encode(it)) }
        s.target?.let { put("target", encode(it)) }
        put("filters", s.options.filters)
        put("maxBoost", s.options.maxBoostDb)
        put("fitTo", s.options.fitTo)
    }

    private fun decode(o: JSONObject): FitInputs {
        val d = EqFit.Options()
        return FitInputs(
            measurement = decodeCurve(o.optJSONObject("measurement")),
            target = decodeCurve(o.optJSONObject("target")),
            options = EqFit.Options(
                filters = o.optInt("filters", d.filters).coerceIn(3, 16),
                maxBoostDb = o.optDouble("maxBoost", d.maxBoostDb).coerceIn(0.0, 12.0),
                fitTo = o.optDouble("fitTo", d.fitTo).coerceIn(4000.0, 20000.0),
            ),
        )
    }
}
