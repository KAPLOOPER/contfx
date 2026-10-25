package dev.continuum.fx.audio

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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

/** Foreground service that owns the capture session for as long as processing is on. */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var engine: AudioEngine? = null
    private var watcher: SessionWatcher? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val app = application as ContinuumApp
        startForeground(NOTIFICATION_ID, buildNotification(app.store.library.active.name), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (engine != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null || resultCode != Activity.RESULT_OK) {
            fail("Audio capture wasn't allowed.")
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mp = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("Audio capture wasn't allowed.")
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSelf() }
                }
            }, main)
            projection = mp

            val eng = AudioEngine(mp) { message -> main.post { fail(message) } }
            eng.setSound(app.store.library, FxStore.state.value, FxEnvironment.context.value, false, emptyList())
            eng.start()
            engine = eng

            watcher = SessionWatcher(this).also { it.start() }

            scope.launch {
                // Only when the name changes: dragging a band shouldn't re-post the notification.
                app.store.state.map { it.active.name }.distinctUntilChanged().collect { name -> main.post { updateNotification(name) } }
            }
            scope.launch {
                combine(app.store.state, FxStore.state, FxEnvironment.context, EngineStatus.comparing, EngineStatus.processingPackages) { lib, fx, ctx, compare, playing ->
                    { eng.setSound(lib, fx, ctx, compare, playing) }
                }.collect { it() }
            }
            EngineStatus.error.value = null
            EngineStatus.running.value = true
        } catch (t: Throwable) {
            fail(t.message ?: "Couldn't start audio capture.")
        }
        return START_NOT_STICKY
    }

    private fun fail(message: String) {
        EngineStatus.error.value = message
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        EngineStatus.comparing.value = false
        watcher?.stop()
        watcher = null
        engine?.stop()
        engine = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
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
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, ContinuumApp.CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_stat_ink)
            .setContentTitle("Processing audio")
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
        if (engine == null) return
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(profileName))
    }

    companion object {
        const val ACTION_START = "dev.continuum.fx.action.START"
        const val ACTION_STOP = "dev.continuum.fx.action.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
