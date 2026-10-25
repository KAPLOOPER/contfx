package dev.continuum.fx.volume

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.continuum.fx.MainActivity

/**
 * The Volume tile's way in: starting an activity is what folds the shade away, so this one shows
 * nothing, raises the panel (opened out, every stream at hand) once the shade has gone, and leaves.
 */
class VolumeTrampoline : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = VolumeService.instance
        if (service != null) {
            Handler(Looper.getMainLooper()).postDelayed({ VolumeService.instance?.showPanel(true) }, 280)
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_VOLUME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
