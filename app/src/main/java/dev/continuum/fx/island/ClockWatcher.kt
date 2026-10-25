package dev.continuum.fx.island

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Watches the phone's clock and sound switch for the island: the next alarm (whenever
 * one is set, changed or goes off) and the ring/vibrate/silent mode.
 */
class ClockWatcher(private val context: Context) {

    private var lastRinger: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                -> readAlarm()
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                    // The broadcast also arrives when nothing changed (on start, for instance).
                    if (mode >= 0 && mode != lastRinger) {
                        if (lastRinger != null) IslandRepository.sound.value = SoundMode(mode, System.currentTimeMillis())
                        lastRinger = mode
                    }
                }
            }
        }
    }

    fun start() {
        lastRinger = try {
            context.getSystemService(AudioManager::class.java)?.ringerMode
        } catch (_: Exception) {
            null
        }
        readAlarm()
        val filter = IntentFilter().apply {
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    private fun readAlarm() {
        val next = try {
            context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        } catch (_: Exception) {
            null
        }
        IslandRepository.alarm.value = next?.let { AlarmInfo(at = it.triggerTime, show = it.showIntent) }
    }
}
