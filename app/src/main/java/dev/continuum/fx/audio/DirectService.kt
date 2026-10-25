package dev.continuum.fx.audio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import dev.continuum.fx.ContinuumApp
import dev.continuum.fx.MainActivity
import dev.continuum.fx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Direct mode's foreground service: keeps the EQ on every playing app's own sound for as long
 * as it runs (the effects belong to this process). No capture, so no capture prompt or icon;
 * the notification is the way to stop it from anywhere.
 */
class DirectService : Service() {

    private var watcher: SessionWatcher? = null
    private var eq: DirectEq? = null
    private val spectrum = DirectSpectrum()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as ContinuumApp
        val notification = buildNotification(app.store.library.active.name)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (eq != null) return START_NOT_STICKY

        try {
            val direct = DirectEq()
            eq = direct
            val w = SessionWatcher(this, direct)
            direct.source = { w.attached() }
            watcher = w
            direct.update(app.store.library, FxStore.state.value, FxEnvironment.context.value, EngineStatus.comparing.value, EngineStatus.boostDb.value)
            w.start()

            scope.launch {
                combine(app.store.state, FxStore.state, FxEnvironment.context, EngineStatus.comparing, EngineStatus.boostDb) { lib, fx, ctx, compare, boost ->
                    { direct.update(lib, fx, ctx, compare, boost) }
                }.collect { it() }
            }
            scope.launch {
                app.store.state.map { it.active.name }.distinctUntilChanged().collect { name -> main.post { updateNotification(name) } }
            }
            scope.launch {
                // The spectrum runs only while the EQ graph is on screen.
                LiveAudio.listening.collect { on -> main.post { spectrum.listen(on) } }
            }
            EngineStatus.error.value = null
            EngineStatus.running.value = true
        } catch (t: Throwable) {
            EngineStatus.error.value = t.message ?: "Couldn't start the EQ."
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        EngineStatus.comparing.value = false
        spectrum.release()
        LiveAudio.fftSize = 4096
        // Releasing the watcher's effects takes the EQ off every app at once.
        watcher?.stop()
        watcher = null
        eq?.shutdown()
        eq = null
        EngineStatus.running.value = false
        super.onDestroy()
    }

    private fun buildNotification(profileName: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DirectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, ContinuumApp.CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_stat_ink)
            .setContentTitle("Equalizer on")
            .setContentText(profileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(profileName: String) {
        if (eq == null) return
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(profileName))
    }

    companion object {
        private const val ACTION_STOP = "dev.continuum.fx.action.STOP_DIRECT"
        private const val NOTIFICATION_ID = 8

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DirectService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DirectService::class.java))
        }
    }
}
