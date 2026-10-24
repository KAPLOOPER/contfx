package dev.continuum.fx.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.continuum.fx.BuildConfig
import dev.continuum.fx.ContinuumApp
import dev.continuum.fx.MainActivity
import dev.continuum.fx.R

/** Hears back from Android's installer: a confirmation to show, or how the install went. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // The app's screen shows the confirmation while it's open (and picks it up when
                // it's next opened); only when it isn't on screen does this try directly.
                Updater.confirm.value = confirm
                if (!Updater.screenVisible) {
                    try {
                        context.startActivity(confirm)
                        Updater.confirm.value = null
                    } catch (_: Exception) {
                    }
                }
            }
            return
        }
        Updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    companion object {
        const val ACTION = "dev.continuum.fx.INSTALL_STATUS"
    }
}

/** After an update lands, says so, with a tap back into the app. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val note = NotificationCompat.Builder(context, ContinuumApp.CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_stat_ink)
                .setContentTitle("Continuum FX updated")
                .setContentText("Now on build ${BuildConfig.BUILD_NUMBER}. Your settings are as you left them.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTE_ID, note)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val NOTE_ID = 4
    }
}
