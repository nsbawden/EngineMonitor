package com.example.fuelmonitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fuelmonitor.ActiveTrip
import com.example.fuelmonitor.AppSettings
import com.example.fuelmonitor.FuelBleState
import com.example.fuelmonitor.LocationState
import com.example.fuelmonitor.effectiveFuelGph
import com.example.fuelmonitor.ui.components.SettingsNumberField
import com.example.fuelmonitor.ui.theme.Panel
import com.example.fuelmonitor.ui.theme.TextMuted
import com.example.fuelmonitor.ui.theme.TextPrimary

@Composable
internal fun TripScreen(
    bleState: FuelBleState,
    location: LocationState,
    settings: AppSettings,
    activeTrip: ActiveTrip,
    virtualOdometer: Double,
    fuelUsedSinceFullGallons: Double,
    calibrationTripEstimatedGallons: Double,
    onStartTrip: (Double, Double, Boolean) -> Unit,
    onStopTrip: () -> Unit,
    onResetTrip: () -> Unit,
    onApplyRemainingMiles: (Double) -> Unit,
    onApplyCurrentOdometer: (Double) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val telemetry = bleState.telemetry
    val fuelUseGph = effectiveFuelGph(telemetry, settings)
    var startOdoText by remember {
        mutableStateOf(
            when {
                activeTrip.startOdometer > 0.0 -> "%.1f".format(activeTrip.startOdometer)
                virtualOdometer > 0.0 -> "%.1f".format(virtualOdometer)
                else -> ""
            }
        )
    }
    var plannedMilesText by remember(activeTrip.plannedMiles) {
        mutableStateOf(if (activeTrip.plannedMiles > 0.0) "%.1f".format(activeTrip.plannedMiles) else "")
    }
    var showCalibrationPrompt by remember { mutableStateOf(false) }
    var pendingStartOdo by remember { mutableStateOf(0.0) }
    var pendingMiles by remember { mutableStateOf(0.0) }
    var remainingMilesText by remember { mutableStateOf("") }
    var currentOdometerText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Trip", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Trip Setup", fontWeight = FontWeight.SemiBold)
                SettingsNumberField("Start odometer", startOdoText, "mi") { startOdoText = it }
                SettingsNumberField("Route distance", plannedMilesText, "mi") { plannedMilesText = it }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val odo = startOdoText.toDoubleOrNull()
                        val miles = plannedMilesText.toDoubleOrNull()
                        if (odo != null && miles != null && miles > 0.0) {
                            pendingStartOdo = odo
                            pendingMiles = miles
                            showCalibrationPrompt = true
                        }
                    },
                    enabled = startOdoText.toDoubleOrNull() != null && (plannedMilesText.toDoubleOrNull() ?: 0.0) > 0.0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (activeTrip.active) "Restart Trip" else "Start Trip")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { focusManager.clearFocus(); onStopTrip() }, enabled = activeTrip.active, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                    Button(onClick = { focusManager.clearFocus(); onResetTrip() }, modifier = Modifier.weight(1f)) {
                        Text("Reset")
                    }
                }
                Text("Trip Corrections", fontWeight = FontWeight.SemiBold)
                SettingsNumberField("Google Maps remaining", remainingMilesText, "mi") { remainingMilesText = it }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        remainingMilesText.toDoubleOrNull()?.let(onApplyRemainingMiles)
                    },
                    enabled = activeTrip.active && remainingMilesText.toDoubleOrNull() != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply Remaining Miles")
                }
                SettingsNumberField("Current odometer", currentOdometerText, "mi") { currentOdometerText = it }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        currentOdometerText.toDoubleOrNull()?.let(onApplyCurrentOdometer)
                    },
                    enabled = currentOdometerText.toDoubleOrNull() != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sync To Odometer")
                }
            }
        }
        TripSummaryCard(activeTrip, location, fuelUseGph, settings, fuelUsedSinceFullGallons, showDetailRow = true)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live Inputs", fontWeight = FontWeight.SemiBold)
                Text("Raw packet", color = TextMuted)
                Text(telemetry.rawPacket.ifBlank { "-" }, color = TextPrimary, fontSize = 13.sp)
                Text("Calibrated fuel use: %.3f GPH".format(fuelUseGph), color = TextMuted, fontSize = 13.sp)
                Text("Calibration trip estimate: %.3f gal".format(calibrationTripEstimatedGallons), color = TextMuted, fontSize = 13.sp)
                Text("Speed source: ${if (location.usingFallbackSpeed) "bench fallback" else "GPS"}", color = TextMuted, fontSize = 13.sp)
            }
        }
    }

    if (showCalibrationPrompt) {
        AlertDialog(
            onDismissRequest = { showCalibrationPrompt = false },
            title = { Text("Use Odometer For Calibration?") },
            text = {
                Text(
                    "Use %.1f mi as the starting odometer for the next fill-up MPG calibration run?".format(pendingStartOdo)
                )
            },
            confirmButton = {
                Button(onClick = {
                    focusManager.clearFocus()
                    onStartTrip(pendingStartOdo, pendingMiles, true)
                    showCalibrationPrompt = false
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    focusManager.clearFocus()
                    onStartTrip(pendingStartOdo, pendingMiles, false)
                    showCalibrationPrompt = false
                }) {
                    Text("No")
                }
            }
        )
    }
}
