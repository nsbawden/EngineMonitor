package com.example.fuelmonitor.ui.screens

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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fuelmonitor.ActiveTrip
import com.example.fuelmonitor.AppSettings
import com.example.fuelmonitor.FillupRecord
import com.example.fuelmonitor.LocationState
import com.example.fuelmonitor.expectedOdometer
import com.example.fuelmonitor.ui.toSheetsTsv
import com.example.fuelmonitor.ui.components.CompactMetric
import com.example.fuelmonitor.ui.components.SettingsNumberField
import com.example.fuelmonitor.ui.theme.BatteryGreen
import com.example.fuelmonitor.ui.theme.Panel
import com.example.fuelmonitor.ui.theme.PanelAlt
import com.example.fuelmonitor.ui.theme.TextMuted
import com.example.fuelmonitor.ui.theme.TextPrimary
import com.example.fuelmonitor.ui.theme.VoltageAmber
import com.example.fuelmonitor.ui.theme.WarningOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun FillupsScreen(
    location: LocationState,
    settings: AppSettings,
    activeTrip: ActiveTrip,
    fuelUsedSinceFullGallons: Double,
    virtualOdometer: Double,
    fillupRecords: List<FillupRecord>,
    onAddFillup: (FillupRecord) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val expectedOdometer = activeTrip.expectedOdometer(location, settings.distanceScale, virtualOdometer)
    val expectedGallonsToFull = fuelUsedSinceFullGallons.coerceIn(0.0, settings.tankCapacityGallons)
    var odometerText by remember { mutableStateOf(if (expectedOdometer > 0.0) "%.1f".format(expectedOdometer) else "") }
    var gallonsText by remember { mutableStateOf("") }
    var totalCostText by remember { mutableStateOf("") }
    var filledToFull by remember { mutableStateOf(true) }
    var pendingRecord by remember { mutableStateOf<FillupRecord?>(null) }

    fun candidateRecord(): FillupRecord? {
        val odometer = odometerText.toDoubleOrNull() ?: return null
        val gallons = gallonsText.toDoubleOrNull() ?: return null
        val totalCost = totalCostText.toDoubleOrNull() ?: 0.0
        val pricePerGallon = if (gallons > 0.0) totalCost / gallons else 0.0
        if (odometer <= 0.0 || gallons <= 0.0) return null
        return FillupRecord(
            timestampMs = System.currentTimeMillis(),
            odometer = odometer,
            gallonsAdded = gallons,
            pricePerGallon = pricePerGallon,
            totalCost = totalCost,
            filledToFull = filledToFull
        )
    }

    fun warningText(record: FillupRecord): String? {
        val notes = mutableListOf<String>()
        if (expectedOdometer > 0.0) {
            val diff = record.odometer - expectedOdometer
            if (kotlin.math.abs(diff) > 2.0) {
                notes += "Odometer differs by %.1f mi from the app estimate.".format(diff)
            }
        }
        if (record.filledToFull) {
            val diff = record.gallonsAdded - expectedGallonsToFull
            if (kotlin.math.abs(diff) > 1.5 && kotlin.math.abs(diff) > expectedGallonsToFull * 0.15) {
                notes += "Gallons differ by %.2f gal from the virtual gauge estimate.".format(diff)
            }
        } else {
            val currentFuel = settings.tankCapacityGallons - expectedGallonsToFull
            val projectedFuel = currentFuel + record.gallonsAdded
            if (projectedFuel > settings.tankCapacityGallons + 0.25) {
                notes += "Partial fill would put the virtual tank %.2f gal over full.".format(projectedFuel - settings.tankCapacityGallons)
            }
        }
        return notes.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun saveRecord(record: FillupRecord) {
        onAddFillup(record)
        gallonsText = ""
        totalCostText = ""
        odometerText = "%.1f".format(record.odometer)
        filledToFull = true
        pendingRecord = null
    }

    if (pendingRecord != null) {
        val record = pendingRecord!!
        AlertDialog(
            onDismissRequest = {
                focusManager.clearFocus()
                pendingRecord = null
            },
            title = { Text("Check Fillup Values") },
            text = {
                Text(
                    warningText(record).orEmpty() +
                        "\n\nExpected odometer: ${expectedOdometer.takeIf { it > 0.0 }?.let { "%.1f mi".format(it) } ?: "-"}" +
                        "\nExpected gallons to full: %.2f gal".format(expectedGallonsToFull)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    focusManager.clearFocus()
                    saveRecord(record)
                }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    focusManager.clearFocus()
                    pendingRecord = null
                }) {
                    Text("Go Back")
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
        Text("Fillups", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("New Fillup", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FuelLevelEstimateTile(
                        gallons = (settings.tankCapacityGallons - expectedGallonsToFull).coerceIn(0.0, settings.tankCapacityGallons),
                        tankCapacityGallons = settings.tankCapacityGallons,
                        modifier = Modifier.weight(1f)
                    )
                    CompactMetric("Odo Est.", expectedOdometer.takeIf { it > 0.0 }?.let { "%.1f".format(it) } ?: "-", TextPrimary, Modifier.weight(1f))
                }
                SettingsNumberField("Odometer", odometerText, "mi") { odometerText = it }
                SettingsNumberField("Gallons added", gallonsText, "gal") { gallonsText = it }
                SettingsNumberField("Total cost", totalCostText, "$") { totalCostText = it }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = filledToFull, onCheckedChange = { filledToFull = it })
                    Column(Modifier.weight(1f)) {
                        Text("Filled tank to full", fontWeight = FontWeight.SemiBold)
                        Text("Gallons are always added; checked means this entry syncs the tank to known full.", color = TextMuted, fontSize = 13.sp)
                    }
                }
                Text(
                    "Price/gal: ${totalCostText.toDoubleOrNull()?.let { total -> gallonsText.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { "$%.2f".format(total / it) } } ?: "-"}",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val record = candidateRecord() ?: return@Button
                        if (warningText(record) != null) {
                            pendingRecord = record
                        } else {
                            saveRecord(record)
                        }
                    },
                    enabled = candidateRecord() != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Fillup")
                }
            }
        }

        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Recent Fillups", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        focusManager.clearFocus()
                        clipboard.setText(AnnotatedString(fillupRecords.toSheetsTsv()))
                    }) {
                        Text("Export")
                    }
                }
                if (fillupRecords.isEmpty()) {
                    Text("No fillups recorded yet.", color = TextMuted, fontSize = 13.sp)
                } else {
                    fillupRecords.take(20).forEach { record ->
                        FillupRow(record)
                    }
                }
            }
        }
    }
}

@Composable
internal fun FillupRow(record: FillupRecord) {
    val date = SimpleDateFormat("M/d/yy", Locale.US).format(Date(record.timestampMs))
    Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(date, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("%.1f mi".format(record.odometer), color = TextMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text("%.2f gal".format(record.gallonsAdded), color = BatteryGreen, modifier = Modifier.weight(1f))
                Text("$%.2f".format(record.totalCost), color = VoltageAmber, modifier = Modifier.weight(1f))
                Text(record.mpgSinceLastFillup.takeIf { it > 0.0 }?.let { "%.1f MPG".format(it) } ?: "- MPG", color = TextPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun FuelLevelEstimateTile(gallons: Double, tankCapacityGallons: Double, modifier: Modifier = Modifier) {
    val fraction = if (tankCapacityGallons > 0.0) (gallons / tankCapacityGallons).coerceIn(0.0, 1.0) else 0.0
    Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = modifier.height(70.dp)) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val fillWidth = size.width * fraction.toFloat()
                drawRect(
                    color = when {
                        fraction <= 0.15 -> WarningOrange.copy(alpha = 0.40f)
                        fraction <= 0.30 -> VoltageAmber.copy(alpha = 0.40f)
                        else -> BatteryGreen.copy(alpha = 0.34f)
                    },
                    size = Size(fillWidth, size.height)
                )
                listOf(0.25f, 0.50f, 0.75f).forEach { mark ->
                    val x = size.width * mark
                    drawLine(
                        color = Color.White.copy(alpha = if (mark == 0.50f) 0.45f else 0.30f),
                        start = Offset(x, 8.dp.toPx()),
                        end = Offset(x, size.height - 8.dp.toPx()),
                        strokeWidth = if (mark == 0.50f) 2.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            ) {
                Text("Gauge Est.", color = TextMuted, fontSize = 10.sp, maxLines = 1)
                Text("%.1f gal".format(gallons), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}
