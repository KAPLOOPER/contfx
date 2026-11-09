package dev.continuum.keyboard

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.util.AttributeSet
import android.util.Size
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.AttrRes
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import dev.continuum.keyboard.ui.KeyPalette

/**
 * Password and address suggestions from the selected autofill service, shown as chips in the
 * keyboard strip.
 *
 * Android hands the IME cross-process [InlineContentView] surfaces. They cannot be treated like
 * ordinary children: the wrapper below clips those surfaces to the strip, otherwise a provider's
 * surface can cover unrelated parts of the keyboard/window.
 */
@RequiresApi(30)
object Autofill {
    /**
     * Builds the IME side of the autofill contract. Android permits a single spec and reuses the
     * last one for extra suggestions. Keep the existing three equivalent specs as a compatibility
     * convention also used by HeliBoard, not as separate username/password/combined-login slots.
     */
    fun request(context: Context, palette: KeyPalette, heightPx: Int): InlineSuggestionsRequest {
        val d = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * d).toInt().coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val chip = Icon.createWithResource(context, R.drawable.autofill_chip).setTint(palette.fn)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackground(chip)
                    .setPadding(px(10), 0, px(10), 0)
                    .build(),
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chip)
                    .setPadding(px(12), 0, px(12), 0)
                    .build(),
            )
            .setStartIconStyle(ImageViewStyle.Builder().setLayoutMargin(0, 0, px(8), 0).build())
            .setEndIconStyle(ImageViewStyle.Builder().setLayoutMargin(px(8), 0, 0, 0).build())
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(palette.label)
                    .setTextSize(15f)
                    .build(),
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setLayoutMargin(px(6), 0, 0, 0)
                    .setTextColor(palette.sub)
                    .setTextSize(13f)
                    .build(),
            )
            .build()
        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val min = Size(px(100), height)
        val max = Size(px(360), height)
        val specs = List(3) { InlinePresentationSpec.Builder(min, max).setStyle(styles).build() }
        return InlineSuggestionsRequest.Builder(specs)
            .setMaxSuggestionCount(6)
            .build()
    }

    /** Host that clips remote inline surfaces to the keyboard's strip rectangle. */
    fun createHost(context: Context): FrameLayout = InlineContentClipView(context)

    /** Adds the ordinary scroll container inside the clipping host. */
    fun attachScroll(host: FrameLayout, row: LinearLayout) {
        val scroll = HorizontalScrollView(host.context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(row, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        host.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    /**
     * Matches Android's inline-suggestion hosting guidance: a transparent top-ordered SurfaceView
     * gives the system a surface to compose against, while clip bounds are refreshed on every draw.
     */
    @RequiresApi(30)
    private class InlineContentClipView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        @AttrRes defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private val parentBounds = Rect()
        private val contentBounds = Rect()
        private val drawListener = ViewTreeObserver.OnDrawListener { clipDescendants() }

        init {
            val background = SurfaceView(context).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSPARENT)
                isClickable = false
                isFocusable = false
            }
            addView(background, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnDrawListener(drawListener)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnDrawListener(drawListener)
            super.onDetachedFromWindow()
        }

        private fun clipDescendants() {
            parentBounds.set(0, 0, width, height)
            clipDescendant(this)
        }

        private fun clipDescendant(root: View?) {
            when (root) {
                is InlineContentView -> {
                    contentBounds.set(parentBounds)
                    offsetRectIntoDescendantCoords(root, contentBounds)
                    root.clipBounds = contentBounds
                }
                is ViewGroup -> for (i in 0 until root.childCount) clipDescendant(root.getChildAt(i))
            }
        }
    }
}
