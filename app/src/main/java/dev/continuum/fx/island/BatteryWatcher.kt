package dev.continuum.fx.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Watches the battery for the island: plugging in (by cable or wireless), dropping
 * to 20% and 10%, and reaching full each make the island speak up briefly.
 */
class BatteryWatcher(private val context: Context) {

    private var lastPlugged: Int? = null
    private var lastLevel: Int? = null
    private var wasFull = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> flash()
                Intent.ACTION_BATTERY_CHANGED -> update(intent)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    private fun update(intent: Intent) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (raw < 0) return
        val level = (raw * 100 / scale).coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val full = plugged != 0 && (status == BatteryManager.BATTERY_STATUS_FULL || level >= 100)
        val charging = plugged != 0 && (status == BatteryManager.BATTERY_STATUS_CHARGING || full)
        val fullIn = if (Build.VERSION.SDK_INT >= 28 && charging && !full) {
            try {
                context.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining().takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        IslandRepository.battery.value = BatteryInfo(level, charging, plugged, full, fullIn)

        val previousLevel = lastLevel
        val previousPlugged = lastPlugged
        lastLevel = level
        lastPlugged = plugged
        if (previousLevel == null || previousPlugged == null) {
            wasFull = full
            return
        }
        when {
            previousPlugged == 0 && plugged != 0 -> flash()
            plugged == 0 && LOW_MARKS.any { previousLevel > it && level <= it } -> flash()
            full && !wasFull -> flash()
        }
        wasFull = full
    }

    private fun flash() {
        IslandRepository.batteryDemo.value = null
        IslandRepository.batteryFlash.value = System.currentTimeMillis()
    }

    private companion object {
        val LOW_MARKS = listOf(20, 10)
    }
}
