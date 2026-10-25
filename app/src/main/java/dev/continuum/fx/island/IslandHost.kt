package dev.continuum.fx.island

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.continuum.fx.island.ui.IslandRoot

/**
 * Two overlay windows, the way Essentials does it: a large window that only draws
 * (so the ink can stretch and drip without stealing touches), and a small touchable
 * window kept exactly over the island, which forwards its touches to the drawing.
 */
class IslandHost(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val spot = mutableStateOf<CameraSpot?>(null)
    private val outsideTaps = mutableIntStateOf(0)

    private var frame: FrameLayout? = null
    private var compose: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var drawParams: WindowManager.LayoutParams? = null

    private var touchView: View? = null
    private var touchParams: WindowManager.LayoutParams? = null
    private var touchAdded = false
    private var bounds: Rect? = null
    private var wantsOutside = false

    private val density get() = context.resources.displayMetrics.density
    private val pad get() = (6 * density).toInt()

    // With the screen off nothing can see the island, so it stops drawing altogether.
    private var screenWatching = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> owner?.sleep()
                Intent.ACTION_SCREEN_ON -> owner?.wake()
            }
        }
    }

    /** Shows, moves or removes the island to match the switch and the current screen. */
    fun sync(enabled: Boolean) {
        val located = CameraLocator.locate(context, wm)
        if (!enabled || located.landscape) {
            detach()
            return
        }
        spot.value = located
        if (frame == null) attach(located) else layoutDraw(located)
        layoutTouch()
    }

    fun detach() {
        if (screenWatching) {
            try {
                context.unregisterReceiver(screen)
            } catch (_: Exception) {
            }
            screenWatching = false
        }
        removeTouch()
        frame?.let {
            try {
                wm.removeViewImmediate(it)
            } catch (_: Exception) {
            }
        }
        owner?.onDestroy()
        frame = null
        compose = null
        owner = null
        drawParams = null
        bounds = null
    }

    private fun attach(located: CameraSpot) {
        val lifecycle = OverlayLifecycleOwner().also { it.onCreate() }
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            if (Build.VERSION.SDK_INT >= 35) requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            setContent { IslandRoot(spot, outsideTaps, this@IslandHost::onBounds) }
            isHapticFeedbackEnabled = true
        }
        val root = FrameLayout(context).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            addView(view)
        }
        val lp = params(touchable = false)
        sizeDraw(lp, located)
        try {
            wm.addView(root, lp)
            frame = root
            compose = view
            owner = lifecycle
            drawParams = lp
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(context, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenWatching = true
            if (context.getSystemService(PowerManager::class.java)?.isInteractive == false) lifecycle.sleep()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't add the island window", e)
            lifecycle.onDestroy()
        }
    }

    private fun sizeDraw(lp: WindowManager.LayoutParams, located: CameraSpot) {
        lp.x = 0
        lp.y = 0
        lp.width = located.screenWidth
        lp.height = (located.screenHeight * 0.6f).toInt()
    }

    private fun layoutDraw(located: CameraSpot) {
        val lp = drawParams ?: return
        val view = frame ?: return
        val before = lp.width to lp.height
        sizeDraw(lp, located)
        if (before == (lp.width to lp.height)) return
        try {
            wm.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't resize the island window", e)
        }
    }

    /** The island reports where it can be touched, and whether a tap elsewhere should fold it up. */
    private fun onBounds(rect: Rect?, outside: Boolean) {
        wantsOutside = outside
        if (rect == bounds) return
        bounds = rect
        layoutTouch()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun layoutTouch() {
        val draw = drawParams
        val b = bounds
        if (draw == null || b == null || b.isEmpty) {
            removeTouch()
            return
        }
        val lp = touchParams ?: params(touchable = true).also { touchParams = it }
        lp.x = draw.x + b.left - pad
        lp.y = draw.y + b.top - pad
        lp.width = b.width() + pad * 2
        lp.height = b.height() + pad * 2
        val view = touchView ?: View(context).also { v ->
            v.setOnTouchListener { _, event -> forward(event) }
            touchView = v
        }
        try {
            if (touchAdded) wm.updateViewLayout(view, lp) else wm.addView(view, lp)
            touchAdded = true
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't place the island touch window", e)
            touchAdded = false
        }
    }

    private fun removeTouch() {
        if (!touchAdded) return
        try {
            touchView?.let { wm.removeViewImmediate(it) }
        } catch (_: Exception) {
        }
        touchAdded = false
    }

    private fun forward(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (wantsOutside) outsideTaps.intValue += 1
            return false
        }
        val target = compose ?: return false
        val draw = drawParams ?: return false
        val touch = touchParams ?: return false
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation((touch.x - draw.x).toFloat(), (touch.y - draw.y).toFloat())
        target.dispatchTouchEvent(copy)
        copy.recycle()
        return true
    }

    private fun params(touchable: Boolean) = WindowManager.LayoutParams(
        1,
        1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        title = if (touchable) "Continuum island touch" else "Continuum island"
    }

    private companion object {
        const val TAG = "IslandHost"
    }
}
