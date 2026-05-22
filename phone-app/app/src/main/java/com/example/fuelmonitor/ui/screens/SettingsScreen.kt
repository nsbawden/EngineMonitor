package com.example.fuelmonitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fuelmonitor.AltitudeUnit
import com.example.fuelmonitor.AppSettings
import com.example.fuelmonitor.FuelBleState
import com.example.fuelmonitor.GradeUnit
import com.example.fuelmonitor.LocationState
import com.example.fuelmonitor.effectiveFuelGph
import com.example.fuelmonitor.ui.components.DeviceSwitchRow
import com.example.fuelmonitor.ui.components.SettingsNumberField
import com.example.fuelmonitor.ui.theme.Panel
import com.example.fuelmonitor.ui.theme.TextMuted

@Composable
internal fun SettingsScreen(
    bleState: FuelBleState,
    location: LocationState,
    settings: AppSettings,
    fuelUsedSinceFullGallons: Double,
    calibrationTripEstimatedGallons: Double,
    onSettingsChanged: (AppSettings) -> Unit,
    onResetFullTank: () -> Unit,
    onSetFuelGaugeFraction: (Double) -> Unit,
    onStartCalibrationTrip: (Double) -> Unit,
    onFinishCalibrationTrip: (Double, Double) -> Unit,
    onApplyDirectCalibration: (Double) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var tankText by remember(settings.tankCapacityGallons) { mutableStateOf("%.1f".format(settings.tankCapacityGallons)) }
    var flowText by remember(settings.injectorFlowLbHr) { mutableStateOf("%.1f".format(settings.injectorFlowLbHr)) }
    var injectorCountText by remember(settings.injectorCount) { mutableStateOf(settings.injectorCount.toString()) }
    var multiplierText by remember(settings.fuelCalibrationMultiplier) { mutableStateOf("%.3f".format(settings.fuelCalibrationMultiplier)) }
    var averageWindowText by remember(settings.dashboardAverageWindowHours) { mutableStateOf("%.2f".format(settings.dashboardAverageWindowHours)) }
    var instantMpgGoodText by remember(settings.instantMpgGoodThreshold) { mutableStateOf("%.1f".format(settings.instantMpgGoodThreshold)) }
    var distanceScaleText by remember(settings.distanceScale) { mutableStateOf("%.3f".format(settings.distanceScale)) }
    var directMpgText by remember { mutableStateOf("") }
    var pendingDirectMpg by remember { mutableStateOf<Double?>(null) }
    var startOdoText by remember(settings.calibrationStartOdometer) { mutableStateOf(if (settings.calibrationStartOdometer > 0.0) "%.1f".format(settings.calibrationStartOdometer) else "") }
    var finishOdoText by remember { mutableStateOf("") }
    var fillGallonsText by remember { mutableStateOf("") }
    val currentGaugeFractionText = if (settings.tankCapacityGallons > 0.0) {
        "%.2f".format(((settings.tankCapacityGallons - fuelUsedSinceFullGallons) / settings.tankCapacityGallons).coerceIn(0.0, 1.0))
    } else {
        "0.00"
    }
    var gaugeFractionText by remember { mutableStateOf(currentGaugeFractionText) }
    var gaugeFractionFocused by remember { mutableStateOf(false) }

    LaunchedEffect(currentGaugeFractionText, gaugeFractionFocused) {
        if (!gaugeFractionFocused) {
            gaugeFractionText = currentGaugeFractionText
        }
    }

    fun commitBasicSettings() {
        onSettingsChanged(
            settings.copy(
                tankCapacityGallons = tankText.toDoubleOrNull()?.coerceIn(1.0, 80.0) ?: settings.tankCapacityGallons,
                injectorFlowLbHr = flowText.toDoubleOrNull()?.coerceIn(1.0, 200.0) ?: settings.injectorFlowLbHr,
                injectorCount = injectorCountText.toIntOrNull()?.coerceIn(1, 16) ?: settings.injectorCount,
                fuelCalibrationMultiplier = multiplierText.toDoubleOrNull()?.coerceIn(0.25, 4.0) ?: settings.fuelCalibrationMultiplier,
                dashboardAverageWindowHours = averageWindowText.toDoubleOrNull()?.coerceIn(0.05, 24.0) ?: settings.dashboardAverageWindowHours,
                instantMpgGoodThreshold = instantMpgGoodText.toDoubleOrNull()?.coerceIn(0.0, 60.0) ?: settings.instantMpgGoodThreshold
            )
        )
    }

    val rawGph = effectiveFuelGph(bleState.telemetry, settings)
    val directKnownMpg = directMpgText.toDoubleOrNull()
    val directTargetMultiplier = if (directKnownMpg != null && directKnownMpg > 0.0 && location.speedMph > 1.0 && rawGph > 0.01) {
        (location.speedMph / directKnownMpg / rawGph).takeIf { it.isFinite() }?.coerceIn(0.25, 4.0)
    } else {
        null
    }

    if (pendingDirectMpg != null) {
        val knownMpg = pendingDirectMpg!!
        AlertDialog(
            onDismissRequest = {
                focusManager.clearFocus()
                pendingDirectMpg = null
            },
            title = { Text("Apply Direct Calibration?") },
            text = {
                Text(
                    "Use %.1f MPG at the current %.1f MPH reading to set fuel calibration now?".format(knownMpg, location.speedMph)
                )
            },
            confirmButton = {
                Button(onClick = {
                    focusManager.clearFocus()
                    onApplyDirectCalibration(knownMpg)
                    pendingDirectMpg = null
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    focusManager.clearFocus()
                    pendingDirectMpg = null
                }) {
                    Text("No")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Display", fontWeight = FontWeight.SemiBold)
                DeviceSwitchRow(
                    title = "Keep screen on",
                    detail = if (settings.keepScreenOn) {
                        "Screen stays awake while monitoring; background logging continues if you switch apps."
                    } else {
                        "Phone may sleep; background logging continues while the app session is open."
                    },
                    checked = settings.keepScreenOn,
                    enabled = true,
                    onCheckedChange = {
                        focusManager.clearFocus()
                        onSettingsChanged(settings.copy(keepScreenOn = it))
                    }
                )
                Text("Phone status bar remains visible above the app.", color = TextMuted, fontSize = 13.sp)
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Fuel Model", fontWeight = FontWeight.SemiBold)
                SettingsNumberField("Tank capacity", tankText, "gal") { tankText = it }
                SettingsNumberField("Injector flow", flowText, "lb/hr") { flowText = it }
                SettingsNumberField("Injector count", injectorCountText, "count") { injectorCountText = it }
                Text(
                    "Injector flow and count are saved on the phone and sent to the ESP over BLE when connected.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                SettingsNumberField("Fuel calibration", multiplierText, "x") { multiplierText = it }
                Button(onClick = { focusManager.clearFocus(); commitBasicSettings() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply Fuel Settings")
                }
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dashboard Units", fontWeight = FontWeight.SemiBold)
                SettingsNumberField("Dashboard avg window", averageWindowText, "hr") { averageWindowText = it }
                SettingsNumberField("Instant MPG green at", instantMpgGoodText, "MPG") { instantMpgGoodText = it }
                Button(onClick = { focusManager.clearFocus(); commitBasicSettings() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply Dashboard Settings")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { focusManager.clearFocus(); onSettingsChanged(settings.copy(altitudeUnit = AltitudeUnit.Feet)) },
                        modifier = Modifier.weight(1f),
                        enabled = settings.altitudeUnit != AltitudeUnit.Feet
                    ) {
                        Text("Feet")
                    }
                    Button(
                        onClick = { focusManager.clearFocus(); onSettingsChanged(settings.copy(altitudeUnit = AltitudeUnit.Meters)) },
                        modifier = Modifier.weight(1f),
                        enabled = settings.altitudeUnit != AltitudeUnit.Meters
                    ) {
                        Text("Meters")
                    }
                }
                Text("Grade Units", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { focusManager.clearFocus(); onSettingsChanged(settings.copy(gradeUnit = GradeUnit.Percent)) },
                        modifier = Modifier.weight(1f),
                        enabled = settings.gradeUnit != GradeUnit.Percent
                    ) {
                        Text("Percent")
                    }
                    Button(
                        onClick = { focusManager.clearFocus(); onSettingsChanged(settings.copy(gradeUnit = GradeUnit.Degrees)) },
                        modifier = Modifier.weight(1f),
                        enabled = settings.gradeUnit != GradeUnit.Degrees
                    ) {
                        Text("Degrees")
                    }
                }
                Text(
                    "Altitude displays ${settings.altitudeUnit.label}; grade displays ${settings.gradeUnit.label}.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Distance Calibration", fontWeight = FontWeight.SemiBold)
                SettingsNumberField("Odometer scale", distanceScaleText, "x") { distanceScaleText = it }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val scale = distanceScaleText.toDoubleOrNull()?.coerceIn(0.25, 2.0) ?: settings.distanceScale
                        onSettingsChanged(settings.copy(distanceScale = scale))
                    },
                    enabled = distanceScaleText.toDoubleOrNull() != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply Odometer Scale")
                }
                Text(
                    "Values below 1.000 reduce GPS/speed distance. Dashboard ODO quick-sync can update this automatically.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Virtual Fuel Gauge", fontWeight = FontWeight.SemiBold)
                Text("Fuel remaining: %.2f gal".format((settings.tankCapacityGallons - fuelUsedSinceFullGallons).coerceAtLeast(0.0)), color = TextMuted)
                SettingsNumberField(
                    label = "Set fuel gauge",
                    value = gaugeFractionText,
                    suffix = "0-1",
                    modifier = Modifier.onFocusChanged { gaugeFractionFocused = it.isFocused },
                    onValueChange = { gaugeFractionText = it }
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        gaugeFractionText.toDoubleOrNull()?.let(onSetFuelGaugeFraction)
                    },
                    enabled = gaugeFractionText.toDoubleOrNull()?.let { it in 0.0..1.0 } == true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sync Virtual Fuel Gauge")
                }
                TextButton(onClick = { focusManager.clearFocus(); onResetFullTank() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Set Virtual Tank Full")
                }
                Text(
                    "This syncs the app's tank estimate to the RV gauge; it does not change injector fuel-use calibration.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Direct Calibration", fontWeight = FontWeight.SemiBold)
                Text(
                    "Use only under a known steady condition, such as 55 MPH on flat road with no wind.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                SettingsNumberField("Known MPG", directMpgText, "MPG") { directMpgText = it }
                Text(
                    "Current: %.1f MPH, raw %.3f GPH, multiplier %.3f%s".format(
                        location.speedMph,
                        rawGph,
                        settings.fuelCalibrationMultiplier,
                        directTargetMultiplier?.let { ", target %.3f".format(it) } ?: ""
                    ),
                    color = TextMuted,
                    fontSize = 13.sp
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        directKnownMpg?.let { pendingDirectMpg = it }
                    },
                    enabled = directTargetMultiplier != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply Direct Calibration")
                }
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Fill-Up Calibration", fontWeight = FontWeight.SemiBold)
                Text(
                    "Start with a full tank and odometer reading. At the next fill-up, enter odometer and gallons to full; the app adjusts the fuel multiplier.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                SettingsNumberField("Start odometer", startOdoText, "mi") { startOdoText = it }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        startOdoText.toDoubleOrNull()?.let(onStartCalibrationTrip)
                    },
                    enabled = startOdoText.toDoubleOrNull() != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Full-Tank Calibration Trip")
                }
                Text("Estimated fuel since start: %.3f gal".format(calibrationTripEstimatedGallons), color = TextMuted, fontSize = 13.sp)
                SettingsNumberField("Fill-up odometer", finishOdoText, "mi") { finishOdoText = it }
                SettingsNumberField("Gallons to full", fillGallonsText, "gal") { fillGallonsText = it }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val odo = finishOdoText.toDoubleOrNull()
                        val gallons = fillGallonsText.toDoubleOrNull()
                        if (odo != null && gallons != null) onFinishCalibrationTrip(odo, gallons)
                    },
                    enabled = finishOdoText.toDoubleOrNull() != null && fillGallonsText.toDoubleOrNull() != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply Fill-Up Calibration")
                }
            }
        }
    }
}
