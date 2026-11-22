package to.wim.clauder.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

private val base = Typography()

/** Material's type scale, with the system serif for display, headline and Title Large: session
 * titles, empty-state titles and app bar titles. Body and labels stay sans serif. */
val AppTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Serif),
    displayMedium = base.displayMedium.copy(fontFamily = FontFamily.Serif),
    displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif),
    headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif),
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif),
)
