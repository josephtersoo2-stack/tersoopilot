package com.multibrowser.antidetect.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

object ThemeManager {
    private const val PREFS_NAME = "octo_theme_prefs"
    private const val KEY_THEME = "theme_mode"

    var currentThemeMode by mutableStateOf(ThemeMode.DARK)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_THEME, ThemeMode.DARK.name) ?: ThemeMode.DARK.name
        currentThemeMode = try {
            ThemeMode.valueOf(saved)
        } catch (_: Exception) {
            ThemeMode.DARK
        }
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        currentThemeMode = mode
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun toggleTheme(context: Context) {
        val next = if (currentThemeMode == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT
        setThemeMode(context, next)
    }
}

// Brand Accent Colors
val OctoPrimary = Color(0xFF007AFF) // Electric Blue
val OctoSuccess = Color(0xFF34C759) // Emerald Green
val OctoWarning = Color(0xFFFF9500) // Amber
val OctoDanger = Color(0xFFFF3B30) // Crimson Red

// Dark Palette
val DarkBackground = Color(0xFF0F0F12)
val DarkSurface = Color(0xFF18181C)
val DarkSurfaceElevated = Color(0xFF202026)
val DarkBorder = Color(0xFF2C2C34)
val DarkTextPrimary = Color(0xFFFFFFFF)
val DarkTextSecondary = Color(0xFF9898A0)
val DarkTextMuted = Color(0xFF686872)

// Light Palette
val LightBackground = Color(0xFFF5F6FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceElevated = Color(0xFFEBEFF5)
val LightBorder = Color(0xFFD6DBE5)
val LightTextPrimary = Color(0xFF141519)
val LightTextSecondary = Color(0xFF585E70)
val LightTextMuted = Color(0xFF8890A2)

data class OctoColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val isDark: Boolean
)

val DarkOctoColors = OctoColors(
    background = DarkBackground,
    surface = DarkSurface,
    surfaceElevated = DarkSurfaceElevated,
    border = DarkBorder,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    textMuted = DarkTextMuted,
    isDark = true
)

val LightOctoColors = OctoColors(
    background = LightBackground,
    surface = LightSurface,
    surfaceElevated = LightSurfaceElevated,
    border = LightBorder,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    textMuted = LightTextMuted,
    isDark = false
)

val LocalOctoColors = staticCompositionLocalOf { DarkOctoColors }

// Dynamic color accessors for composables
val OctoBackground: Color @Composable get() = LocalOctoColors.current.background
val OctoSurface: Color @Composable get() = LocalOctoColors.current.surface
val OctoSurfaceElevated: Color @Composable get() = LocalOctoColors.current.surfaceElevated
val OctoBorder: Color @Composable get() = LocalOctoColors.current.border
val OctoTextPrimary: Color @Composable get() = LocalOctoColors.current.textPrimary
val OctoTextSecondary: Color @Composable get() = LocalOctoColors.current.textSecondary
val OctoTextMuted: Color @Composable get() = LocalOctoColors.current.textMuted
val isAppInDarkTheme: Boolean @Composable get() = LocalOctoColors.current.isDark

private val DarkColorScheme = darkColorScheme(
    primary = OctoPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF004080),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = OctoSuccess,
    onSecondary = Color.White,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    outlineVariant = Color(0xFF3A3A44),
    error = OctoDanger,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = OctoPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF004080),
    secondary = OctoSuccess,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceElevated,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = Color(0xFFC0C6D4),
    error = OctoDanger,
    onError = Color.White
)

@Composable
fun OctoTheme(
    themeMode: ThemeMode = ThemeManager.currentThemeMode,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (isDark) DarkOctoColors else LightOctoColors
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalOctoColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

@Composable
fun octoTextFieldColors(
    containerColor: Color = OctoSurface,
    focusedBorderColor: Color = OctoPrimary,
    unfocusedBorderColor: Color = OctoBorder
): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = OctoTextPrimary,
    unfocusedTextColor = OctoTextPrimary,
    focusedBorderColor = focusedBorderColor,
    unfocusedBorderColor = unfocusedBorderColor,
    focusedContainerColor = containerColor,
    unfocusedContainerColor = containerColor,
    cursorColor = OctoPrimary,
    focusedLabelColor = OctoPrimary,
    unfocusedLabelColor = OctoTextMuted,
    focusedPlaceholderColor = OctoTextMuted,
    unfocusedPlaceholderColor = OctoTextMuted,
    focusedLeadingIconColor = OctoPrimary,
    unfocusedLeadingIconColor = OctoTextMuted,
    focusedTrailingIconColor = OctoTextPrimary,
    unfocusedTrailingIconColor = OctoTextMuted
)

@Composable
fun octoElevatedTextFieldColors(
    focusedBorderColor: Color = OctoPrimary,
    unfocusedBorderColor: Color = OctoBorder
): TextFieldColors = octoTextFieldColors(
    containerColor = OctoSurfaceElevated,
    focusedBorderColor = focusedBorderColor,
    unfocusedBorderColor = unfocusedBorderColor
)

