package com.chill.familyvlog.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val TuiColorScheme = darkColorScheme(
    primary = TuiPrimary,
    onPrimary = TuiBackground,
    primaryContainer = TuiPanelRaised,
    onPrimaryContainer = TuiTextPrimary,
    secondary = TuiGoogle,
    onSecondary = TuiBackground,
    background = TuiBackground,
    onBackground = TuiTextPrimary,
    surface = TuiPanel,
    onSurface = TuiTextPrimary,
    surfaceVariant = TuiPanelRaised,
    onSurfaceVariant = TuiTextSecondary,
    error = TuiFailure,
    onError = TuiBackground,
    outline = TuiBorder,
)

private val TuiTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = FontFamily.Default),
        displayMedium = displayMedium.copy(fontFamily = FontFamily.Default),
        displaySmall = displaySmall.copy(fontFamily = FontFamily.Default),
        headlineLarge = headlineLarge.copy(fontFamily = FontFamily.Default),
        headlineMedium = headlineMedium.copy(
            fontFamily = FontFamily.Default,
            fontSize = 20.sp,
            lineHeight = 24.sp,
        ),
        headlineSmall = headlineSmall.copy(fontFamily = FontFamily.Default),
        titleLarge = titleLarge.copy(
            fontFamily = FontFamily.Default,
            fontSize = 20.sp,
            lineHeight = 24.sp,
        ),
        titleMedium = titleMedium.copy(
            fontFamily = FontFamily.Default,
            fontSize = 18.sp,
            lineHeight = 22.sp,
        ),
        titleSmall = titleSmall.copy(fontFamily = FontFamily.Default),
        bodyLarge = bodyLarge.copy(fontFamily = FontFamily.Default),
        bodyMedium = bodyMedium.copy(
            fontFamily = FontFamily.Default,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodySmall = bodySmall.copy(
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
        labelLarge = labelLarge.copy(
            fontFamily = FontFamily.Default,
            fontSize = 16.sp,
            lineHeight = 20.sp,
        ),
        labelMedium = labelMedium.copy(fontFamily = FontFamily.Default, lineHeight = 16.sp),
        labelSmall = labelSmall.copy(fontFamily = FontFamily.Default),
    )
}

private val TuiShapes = Shapes(
    small = RoundedCornerShape(TuiPanelCorner),
    medium = RoundedCornerShape(TuiButtonCorner),
    large = RoundedCornerShape(TuiButtonCorner),
)

@Composable
fun FamilyVlogTuiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TuiColorScheme,
        typography = TuiTypography,
        shapes = TuiShapes,
        content = content,
    )
}
