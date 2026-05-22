package com.example.fuelmonitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun FuelMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PowerBlue,
            secondary = BatteryGreen,
            tertiary = VoltageAmber,
            background = AppBackground,
            surface = Panel,
            surfaceVariant = PanelAlt,
            onPrimary = Color.White,
            onSecondary = Color.Black,
            onTertiary = Color.Black,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            onSurfaceVariant = TextMuted
        ),
        content = content
    )
}
