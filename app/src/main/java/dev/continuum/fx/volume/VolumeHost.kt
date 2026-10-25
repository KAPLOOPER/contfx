package dev.continuum.fx.volume

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
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
import dev.continuum.fx.island.OverlayLifecycleOwner
import dev.continuum.fx.volume.ui.VolumePanel
import kotlin.math.min

/** What the panel shows; the host and the service move it, the panel draws it. */
class PanelUi {
    val visible = mutableStateOf(false)
    val expanded = mutableStateOf(false)
    val stream = mutableStateOf(VolStream.Media)
    /** Bumped by every key press, so the panel stays up and the level drop speaks up. */
    val keyTick = mutableIntStateOf(0)
    /** A volume key is down: the panel doesn't leave while it's held. */
    val holding = mutableStateOf(false)
    /** The screen: width, height (pixels) and the strip of it the panel can use. */
    val screenW = mutableIntStateOf(0)
    val screenH = mutableIntStateOf(0)
    val stripW = mutableIntStateOf(0)
}

/**
 * Two overlay windows on the panel's side of the screen: a tall one that only draws (so the ink
 * can grow out of the edge and spread without taking touches), and a small touchable one kept
 * exactly over the panel. A tap anywhere else folds the panel away.
 */
class VolumeHost(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    val ui = PanelUi()

    private var frame: FrameLayout? = null
    private var compose: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var drawParams: WindowManager.LayoutParams? = null

    private var touchView: View? = null
    private var touchParams: WindowManager.LayoutParams? = null
    private var touchAdded = false
    private var bounds: Rect? = null

    private val density get() = context.resources.displayMetrics.density

    private var screenWatching = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // The screen going off ends whatever was showing.
            if (intent.action == Intent.ACTION_SCREEN_OFF) hide()
        }
    }

    fun attach() {
        if (frame != null) return
        val lifecycle = OverlayLifecycleOwner().also { it.onCreate() }
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            if (Build.VERSION.SDK_INT >= 35) requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            setContent {
                VolumePanel(
                    ui = ui,
                    onBounds = ::onBounds,
                    onSettled = ::onSettled,
                    openApp = ::openApp,
                )
            }
            isHapticFeedbackEnabled = true
        }
        val root = FrameLayout(context).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            addView(view)
            visibility = View.INVISIBLE
        }
        val lp = params(touchable = false)
        size(lp)
        try {
            wm.addView(root, lp)
            frame = root
            compose = view
            owner = lifecycle
            drawParams = lp
            ContextCompat.registerReceiver(context, screen, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            screenWatching = true
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't add the volume window", e)
            lifecycle.onDestroy()
        }
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

    /**
     * Shows the panel for [stream] (or keeps it up), opened out when [expanded]. From a key
     * press ([key]), it stays until the key comes up.
     */
    fun show(stream: VolStream, expanded: Boolean, key: Boolean = true) {
        val root = frame ?: return
        if (root.visibility != View.VISIBLE) {
            reposition()
            root.visibility = View.VISIBLE
        }
        ui.stream.value = stream
        // A fresh appearance starts as asked; one already up only ever opens further.
        if (!ui.visible.value) ui.expanded.value = expanded else if (expanded) ui.expanded.value = true
        ui.visible.value = true
        ui.holding.value = key
        ui.keyTick.intValue += 1
    }

    /** The volume key came up: the panel may start counting down to leave. */
    fun released() {
        ui.holding.value = false
        ui.keyTick.intValue += 1
    }

    fun hide() {
        ui.holding.value = false
        ui.expanded.value = false
        ui.visible.value = false
    }

    /** Fits the windows to the screen and the side the panel lives on. */
    fun reposition() {
        val lp = drawParams ?: return
        val view = frame ?: return
        size(lp)
        try {
            wm.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't resize the volume window", e)
        }
        layoutTouch()
    }

    private fun size(lp: WindowManager.LayoutParams) {
        val b = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else {
            val dm = context.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
        val w = b.width()
        val h = b.height()
        val strip = min(w, (430 * density).toInt())
        val left = VolumeSettings.state.value.left
        lp.width = strip
        lp.height = h
        lp.x = if (left) 0 else w - strip
        lp.y = 0
        ui.screenW.intValue = w
        ui.screenH.intValue = h
        ui.stripW.intValue = strip
    }

    /** Ink settled: hidden means nothing left to draw, so the windows rest. */
    private fun onSettled(shown: Boolean) {
        if (!shown) {
            frame?.visibility = View.INVISIBLE
            onBounds(null)
        }
    }

    private fun openApp() {
        hide()
        try {
            context.startActivity(
                Intent(context, dev.continuum.fx.MainActivity::class.java)
                    .setAction(dev.continuum.fx.MainActivity.ACTION_OPEN_VOLUME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } catch (_: Exception) {
        }
    }

    /** Where the panel can be touched, in the draw window's own pixels. */
    private fun onBounds(rect: Rect?) {
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
        val pad = (6 * density).toInt()
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
            Log.e(TAG, "Couldn't place the volume touch window", e)
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
            hide()
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
        title = if (touchable) "Continuum volume touch" else "Continuum volume"
    }

    private companion object {
        const val TAG = "VolumeHost"
    }
}
