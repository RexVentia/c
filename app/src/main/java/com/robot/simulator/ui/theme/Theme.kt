package com.robot.simulator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C4C),
    secondary = Color(0xFF4D6357),
    tertiary = Color(0xFF3F6374),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5DDBA5),
    secondary = Color(0xFFB4CCBD),
    tertiary = Color(0xFFA6CDDF),
)

@Composable
fun RobotSimulatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
