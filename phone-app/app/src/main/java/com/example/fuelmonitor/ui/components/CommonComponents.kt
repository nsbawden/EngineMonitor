package com.example.fuelmonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fuelmonitor.FuelBleState
import com.example.fuelmonitor.LocationState
import com.example.fuelmonitor.ui.theme.BatteryGreen
import com.example.fuelmonitor.ui.theme.Panel
import com.example.fuelmonitor.ui.theme.PanelAlt
import com.example.fuelmonitor.ui.theme.TextMuted
import com.example.fuelmonitor.ui.theme.TextPrimary
import com.example.fuelmonitor.ui.theme.VoltageAmber
import com.example.fuelmonitor.ui.theme.WarningOrange

@Composable
internal fun StatusCard(bleState: FuelBleState, location: LocationState) {
    val telemetry = bleState.telemetry
    val injectorSignalPresent = bleState.connected && (telemetry.signalActive || telemetry.pulsesPerSecond > 0.1 || telemetry.widthUs > 0.0)
    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatusChip(
                    if (bleState.connected) "ESP live" else if (bleState.scanning) "Scanning" else "ESP offline",
                    if (bleState.connected) BatteryGreen else if (bleState.scanning) VoltageAmber else WarningOrange,
                    Modifier.weight(1f)
                )
                StatusChip(
                    when {
                        !bleState.connected -> "Pulse unknown"
                        telemetry.simulatedTelemetryEnabled -> "ESP sim"
                        injectorSignalPresent -> "Injector live"
                        else -> "No fuel pulse"
                    },
                    when {
                        !bleState.connected -> TextMuted
                        telemetry.simulatedTelemetryEnabled -> VoltageAmber
                        injectorSignalPresent -> BatteryGreen
                        else -> TextMuted
                    },
                    Modifier.weight(1f)
                )
            }
            Text(bleState.status, color = TextMuted, fontSize = 13.sp)
            Text(location.status, color = TextMuted, fontSize = 13.sp)
        }
    }
}
@Composable
internal fun DeviceSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    pending: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextMuted, fontSize = 13.sp)
        }
        if (pending) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}
@Composable
internal fun SettingsNumberField(
    label: String,
    value: String,
    suffix: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}
@Composable
internal fun PlaceholderScreen(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(body, color = TextMuted, modifier = Modifier.padding(12.dp))
        }
    }
}
@Composable
internal fun ValueTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = modifier.height(72.dp)) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(6.dp)
        ) {
            Text(label, color = TextMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
            Text(value, color = color, fontSize = value.tileFontSize(), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
@Composable
internal fun SpeedSignTile(value: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.height(72.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(5.dp)
                .background(Color.White, RoundedCornerShape(6.dp))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.dp.toPx()
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    style = Stroke(width = stroke)
                )
            }
            Text(
                text = value,
                color = Color.Black,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
@Composable
internal fun CompactMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = modifier.height(54.dp)) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp)
        ) {
            Text(label, color = TextMuted, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 1)
            Text(value, color = color, fontSize = value.compactMetricFontSize(), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
@Composable
internal fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp), modifier = modifier.height(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = color, fontSize = text.chipFontSize(), fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
internal fun String.tileFontSize() = when {
    length >= 14 -> 15.sp
    length >= 12 -> 16.sp
    length >= 10 -> 18.sp
    length >= 8 -> 20.sp
    length >= 7 -> 21.sp
    length >= 6 -> 22.sp
    else -> 24.sp
}

internal fun String.compactMetricFontSize() = when {
    length >= 11 -> 13.sp
    length >= 9 -> 14.sp
    length >= 7 -> 15.sp
    else -> 16.sp
}

internal fun String.chipFontSize() = when {
    length >= 15 -> 15.sp
    length >= 12 -> 16.sp
    length >= 9 -> 17.sp
    else -> 18.sp
}
