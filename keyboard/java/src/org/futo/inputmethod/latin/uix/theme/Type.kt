package org.futo.inputmethod.latin.uix.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.futo.inputmethod.latin.R

/** Continuum Keyboard: Libron throughout, in its two weights. */
val Libron = FontFamily(
    Font(R.font.libron_regular, FontWeight.Normal),
    Font(R.font.libron_bold, FontWeight.Bold),
)

data object Typography {
    data object Heading {
        val MediumMl = TextStyle(
            fontFamily = Libron,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 30.sp
        )

        val Medium = TextStyle(
            fontFamily = Libron,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            //lineHeight = 13.6.sp
            lineHeight = 20.sp,
        )

        val RegularMl = TextStyle(
            fontFamily = Libron,
            fontWeight = FontWeight.Normal,
            fontSize = 20.sp,
            lineHeight = 30.sp
        )

        val Regular = TextStyle(
            fontFamily = Libron,
            fontWeight = FontWeight.Normal,
            fontSize = 20.sp,
            //lineHeight = 13.6.sp
            lineHeight = 20.sp,
        )
    }

    data object Body {
        val MediumMl = TextStyle(
            fontFamily = Libron,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 24.sp
        )

        val Medium = TextStyle(
            fontFamily = Libron,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            //lineHeight = 10.56.sp
            lineHeight = 16.sp,
        )

        val RegularMl = TextStyle(
            fontFamily = Libron,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp
        )

        val Regular = TextStyle(
            fontFamily = Libron,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            //lineHeight = 10.56.sp
            lineHeight = 16.sp,
        )
    }

    val SmallMl = TextStyle(
        fontFamily = Libron,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )

    val Small = TextStyle(
        fontFamily = Libron,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        //lineHeight = 9.52.sp
        lineHeight = 14.sp
    )
}