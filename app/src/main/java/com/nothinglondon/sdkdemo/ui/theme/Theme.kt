package com.nothinglondon.sdkdemo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NothingDarkScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = NothingBlack,
    secondary = NothingGray,
    onSecondary = NothingWhite,
    tertiary = NothingRed,
    background = NothingBlack,
    onBackground = NothingWhite,
    surface = NothingSurface,
    onSurface = NothingWhite,
    surfaceVariant = NothingCard,
    onSurfaceVariant = NothingGray,
    outline = NothingBorder,
)

@Composable
fun NothingAndroidSDKDemoTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NothingDarkScheme,
        typography = Typography,
        content = content
    )
}