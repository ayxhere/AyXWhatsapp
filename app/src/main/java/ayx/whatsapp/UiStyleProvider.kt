package ayx.whatsapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/**
 * Provides the active [UiStyle] to the composition.
 * Components read [UiStyleProvider.current] for corners, elevations,
 * glass and borders — independent of the color theme.
 */
val LocalUiStyle = compositionLocalOf<UiStyle> { DefaultUi }

object UiStyleProvider {
    val current: UiStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalUiStyle.current
}

/**
 * Observes [UiStyleStore.uiStyle] and resolves the active UI style.
 * Call near the top of the composition; recomposes on style change.
 */
@Composable
fun rememberUiStyle(): UiStyle {
    val styleId = UiStyleStore.uiStyle.value
    return uiStyleById(styleId)
}
