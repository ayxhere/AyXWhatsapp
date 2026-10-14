package ayx.whatsapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/**
 * Provides the active [AyxTheme] to the composition.
 * Screens read [AyxThemeProvider.current] instead of hardcoded colors.
 */
val LocalAyxTheme = compositionLocalOf<AyxTheme> { NeumorphismTheme }

object AyxThemeProvider {
    val current: AyxTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAyxTheme.current
}

/**
 * Observes [ThemeStore.appearanceStyle] and resolves the active theme.
 * Call once near the top of the composition (e.g. inside setContent).
 */
@Composable
fun rememberAyxTheme(): AyxTheme {
    // reading .value makes this recompose on style change
    val styleId = UiStyleStore.uiStyle.value
    return ayxThemeById(styleId)
}
