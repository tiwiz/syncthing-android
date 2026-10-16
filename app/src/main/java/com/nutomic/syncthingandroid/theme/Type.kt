package com.nutomic.syncthingandroid.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.nutomic.syncthingandroid.R

/** Width of the wide cut, on Roboto Flex's `wdth` axis (100 is normal). */
private const val WIDE_WIDTH = 118f

private val TEXT_WEIGHTS = (300..900 step 100).map(::FontWeight)

private fun robotoFlex(width: Float): FontFamily =
    FontFamily(
        TEXT_WEIGHTS.map { weight ->
            Font(
                R.font.roboto_flex,
                weight = weight,
                variationSettings =
                    FontVariation.Settings(weight, FontStyle.Normal, FontVariation.width(width)),
            )
        }
    )

/** Roboto Flex, bundled because F-Droid builds cannot use downloadable fonts. */
internal object AppFonts {
    /** Body, title and label text. */
    val text: FontFamily = robotoFlex(width = 100f)

    /** Display and headline text: the same font, wider, for the expressive look. */
    val wide: FontFamily = robotoFlex(width = WIDE_WIDTH)
}

private fun TextStyle.text() = copy(fontFamily = AppFonts.text)

private fun TextStyle.wide() = copy(fontFamily = AppFonts.wide)

/** Material's type scale in Roboto Flex; display and headline styles use the wide cut. */
internal val AppTypography: Typography =
    Typography().run {
        copy(
            displayLarge = displayLarge.wide(),
            displayMedium = displayMedium.wide(),
            displaySmall = displaySmall.wide(),
            headlineLarge = headlineLarge.wide(),
            headlineMedium = headlineMedium.wide(),
            headlineSmall = headlineSmall.wide(),
            titleLarge = titleLarge.text(),
            titleMedium = titleMedium.text(),
            titleSmall = titleSmall.text(),
            bodyLarge = bodyLarge.text(),
            bodyMedium = bodyMedium.text(),
            bodySmall = bodySmall.text(),
            labelLarge = labelLarge.text(),
            labelMedium = labelMedium.text(),
            labelSmall = labelSmall.text(),
            displayLargeEmphasized = displayLargeEmphasized.wide(),
            displayMediumEmphasized = displayMediumEmphasized.wide(),
            displaySmallEmphasized = displaySmallEmphasized.wide(),
            headlineLargeEmphasized = headlineLargeEmphasized.wide(),
            headlineMediumEmphasized = headlineMediumEmphasized.wide(),
            headlineSmallEmphasized = headlineSmallEmphasized.wide(),
            titleLargeEmphasized = titleLargeEmphasized.text(),
            titleMediumEmphasized = titleMediumEmphasized.text(),
            titleSmallEmphasized = titleSmallEmphasized.text(),
            bodyLargeEmphasized = bodyLargeEmphasized.text(),
            bodyMediumEmphasized = bodyMediumEmphasized.text(),
            bodySmallEmphasized = bodySmallEmphasized.text(),
            labelLargeEmphasized = labelLargeEmphasized.text(),
            labelMediumEmphasized = labelMediumEmphasized.text(),
            labelSmallEmphasized = labelSmallEmphasized.text(),
        )
    }
