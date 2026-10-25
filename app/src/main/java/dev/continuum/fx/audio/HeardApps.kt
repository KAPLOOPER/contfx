package dev.continuum.fx.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Apps whose sound the EQ has worked on, newest first, so one can be given its own EQ. */
object HeardApps {
    private const val FILE = "heard_apps"
    private const val KEY = "apps"
    private const val MAX = 40
    private val _apps = MutableStateFlow<List<String>>(emptyList())
    val apps: StateFlow<List<String>> = _apps.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _apps.value = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    }

    @Synchronized
    fun add(pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        val now = _apps.value
        if (pkgs.all { it in now } && now.take(pkgs.size).toSet() == pkgs.toSet()) return
        val next = (pkgs.toList() + now.filter { it !in pkgs }).take(MAX)
        if (next == now) return
        _apps.value = next
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString(KEY, next.joinToString(","))?.apply()
    }
}
