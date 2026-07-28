package com.careon.wear.ui

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme

/** CareOn web/mobile palette adapted for a bright, glanceable watch surface. */
object CareOnWearColors {
    val Background = Color(0xFFF6F6F6)
    val Surface = Color(0xFFFFFFFF)
    val Text = Color(0xFF444444)
    val Body = Color(0xFF575757)
    val Muted = Color(0xFF878787)
    val Line = Color(0xFFDDDDDD)
    val Primary = Color(0xFF24C898)
    val PrimaryDark = Color(0xFF0BB985)
    val PrimarySoft = Color(0xFF62E4BE)
    val Warning = Color(0xFFFF777B)
    val WarningSoft = Color(0xFFF6F6F6)
    val Danger = Color(0xFFFF777B)
    val DangerSoft = Color(0xFFF6F6F6)
}

val CareOnWearColorScheme = ColorScheme(
    primary = CareOnWearColors.Primary,
    primaryDim = CareOnWearColors.PrimaryDark,
    primaryContainer = CareOnWearColors.PrimarySoft,
    onPrimary = Color.White,
    onPrimaryContainer = CareOnWearColors.PrimaryDark,
    secondary = CareOnWearColors.PrimaryDark,
    secondaryDim = CareOnWearColors.PrimaryDark,
    secondaryContainer = CareOnWearColors.PrimarySoft,
    onSecondary = Color.White,
    onSecondaryContainer = CareOnWearColors.PrimaryDark,
    tertiary = CareOnWearColors.Warning,
    tertiaryDim = CareOnWearColors.Warning,
    tertiaryContainer = CareOnWearColors.WarningSoft,
    onTertiary = CareOnWearColors.Text,
    onTertiaryContainer = CareOnWearColors.Text,
    surfaceContainerLow = CareOnWearColors.Surface,
    surfaceContainer = CareOnWearColors.Surface,
    surfaceContainerHigh = Color(0xFFEBEBEB),
    onSurface = CareOnWearColors.Text,
    onSurfaceVariant = CareOnWearColors.Body,
    outline = CareOnWearColors.Line,
    outlineVariant = CareOnWearColors.Line,
    background = CareOnWearColors.Background,
    onBackground = CareOnWearColors.Text,
    error = CareOnWearColors.Danger,
    errorDim = CareOnWearColors.Danger,
    errorContainer = CareOnWearColors.DangerSoft,
    onError = Color.White,
    onErrorContainer = CareOnWearColors.Danger,
)
