package dev.continuum.keyboard

import android.content.Context
import android.graphics.drawable.Icon
import android.util.Size
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import dev.continuum.keyboard.ui.KeyPalette

/**
 * Password and address suggestions from your autofill service, shown as chips in the
 * strip and styled as Continuum pills.
 */
@RequiresApi(30)
object Autofill {
    fun request(context: Context, palette: KeyPalette): InlineSuggestionsRequest {
        val d = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * d).toInt()
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
                    .setPadding(px(16), 0, px(16), 0)
                    .build(),
            )
            .setStartIconStyle(ImageViewStyle.Builder().setLayoutMargin(0, 0, px(8), 0).build())
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
        val spec = InlinePresentationSpec.Builder(Size(px(64), px(40)), Size(px(320), px(40)))
            .setStyle(styles)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(6)
            .build()
    }
}
