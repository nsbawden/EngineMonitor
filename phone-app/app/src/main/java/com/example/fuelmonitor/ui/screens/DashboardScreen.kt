package com.example.fuelmonitor.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fuelmonitor.ActiveTrip
import com.example.fuelmonitor.AppSettings
import com.example.fuelmonitor.FuelBleState
import com.example.fuelmonitor.FuelHistoryPoint
import com.example.fuelmonitor.GradeState
import com.example.fuelmonitor.GradeUnit
import com.example.fuelmonitor.LocationState
import com.example.fuelmonitor.MaxGradeDisplayPercent
import com.example.fuelmonitor.altitudeText
import com.example.fuelmonitor.arrivalText
import com.example.fuelmonitor.calculateInstantMpgForDisplay
import com.example.fuelmonitor.dashboardAverageWindowLabel
import com.example.fuelmonitor.displayMpg
import com.example.fuelmonitor.effectiveFuelGph
import com.example.fuelmonitor.etaText
import com.example.fuelmonitor.expectedOdometer
import com.example.fuelmonitor.leadingOdometerDigits
import com.example.fuelmonitor.milesCompleted
import com.example.fuelmonitor.milesRemaining
import com.example.fuelmonitor.proposedDistanceScale
import com.example.fuelmonitor.takeIfHistoryValid
import com.example.fuelmonitor.ui.components.CompactMetric
import com.example.fuelmonitor.ui.components.SpeedSignTile
import com.example.fuelmonitor.ui.components.StatusChip
import com.example.fuelmonitor.ui.components.ValueTile
import com.example.fuelmonitor.ui.components.tileFontSize
import com.example.fuelmonitor.ui.history.drawHistorySeries
import com.example.fuelmonitor.ui.history.formatTimeLabel
import com.example.fuelmonitor.ui.theme.BatteryGreen
import com.example.fuelmonitor.ui.theme.Panel
import com.example.fuelmonitor.ui.theme.PanelAlt
import com.example.fuelmonitor.ui.theme.TextMuted
import com.example.fuelmonitor.ui.theme.TextPrimary
import com.example.fuelmonitor.ui.theme.VoltageAmber
import com.example.fuelmonitor.ui.theme.WarningOrange
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
internal fun DashboardScreen(
    bleState: FuelBleState,
    location: LocationState,
    settings: AppSettings,
    fuelUsedSinceFullGallons: Double,
    virtualOdometer: Double,
    gradeState: GradeState,
    historyPoints: List<FuelHistoryPoint>,
    rollingAverageMpg: Double?,
    activeTrip: ActiveTrip,
    onOpenTrip: () -> Unit,
    onApplyDashboardOdometer: (Double, Boolean) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val telemetry = bleState.telemetry
    val fuelUseGph = effectiveFuelGph(telemetry, settings)
    val mpg = calculateInstantMpgForDisplay(bleState.connected, fuelUseGph, location.speedMph)
    val averageMpg = when {
        activeTrip.active && activeTrip.arrived -> activeTrip.arrivedAverageMpg.takeIf { it > 0.01 }
        activeTrip.active -> {
            val miles = activeTrip.milesCompleted(location, settings.distanceScale)
            if (activeTrip.fuelUsedGallons > 0.01 && miles > 0.01) miles / activeTrip.fuelUsedGallons else null
        }
        else -> rollingAverageMpg
    }
    val averageLabel = if (activeTrip.active) "Trip Avg" else "Avg ${settings.dashboardAverageWindowLabel()}"
    val altitudeText = location.altitudeText(settings.altitudeUnit)
    val fuelRemaining = (settings.tankCapacityGallons - fuelUsedSinceFullGallons).coerceIn(0.0, settings.tankCapacityGallons)
    val currentVirtualOdometer = activeTrip.expectedOdometer(location, settings.distanceScale, virtualOdometer)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DashboardFuelTile(
                gallons = fuelRemaining,
                tankCapacityGallons = settings.tankCapacityGallons,
                modifier = Modifier.weight(1f)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(2f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SpeedSignTile("%.1f".format(location.speedMph), Modifier.weight(1f))
                    ValueTile(
                        "Instant MPG",
                        if (bleState.connected) "%.1f MPG".format(mpg ?: 0.0) else "- MPG",
                        when {
                            !bleState.connected -> TextMuted
                            (mpg ?: 0.0) >= settings.instantMpgGoodThreshold -> BatteryGreen
                            else -> WarningOrange
                        },
                        Modifier.weight(1f)
                    )
                }
                OdometerTile(
                    odometer = currentVirtualOdometer,
                    activeTrip = activeTrip,
                    location = location,
                    distanceScale = settings.distanceScale,
                    onApplyDashboardOdometer = onApplyDashboardOdometer,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ValueTile("Altitude", altitudeText, TextPrimary, Modifier.weight(1f))
            GradeTile(gradeState = gradeState, gradeUnit = settings.gradeUnit, modifier = Modifier.weight(1f))
            ValueTile(averageLabel, averageMpg?.let { "%.1f MPG".format(it) } ?: "- MPG", VoltageAmber, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ValueTile("RPM", "%.0f".format(telemetry.rpm), TextPrimary, Modifier.weight(1f))
            ValueTile("Fuel Use", "%.2f GPH".format(fuelUseGph), VoltageAmber, Modifier.weight(1f))
            ValueTile("Duty", "%.1f%%".format(telemetry.duty * 100.0), TextPrimary, Modifier.weight(1f))
        }
        DashboardTripCard(activeTrip, location, settings, onOpenTrip)
        DashboardTripGraph(activeTrip, historyPoints)
    }
}
@Composable
internal fun TripSummaryCard(
    trip: ActiveTrip,
    location: LocationState,
    fuelUseGph: Double,
    settings: AppSettings,
    fuelUsedSinceFullGallons: Double,
    showDetailRow: Boolean
) {
    val milesCompleted = trip.milesCompleted(location, settings.distanceScale)
    val milesRemaining = trip.milesRemaining(location, settings.distanceScale)
    val eta = trip.etaText(location.speedMph, settings.distanceScale)
    val arrival = trip.arrivalText(location.speedMph, settings.distanceScale)
    val tripMpg = if (trip.fuelUsedGallons > 0.01 && milesCompleted > 0.01) milesCompleted / trip.fuelUsedGallons else null
    val fuelRemaining = (settings.tankCapacityGallons - fuelUsedSinceFullGallons).coerceAtLeast(0.0)
    val projectedFuelAtArrival = if (fuelUseGph > 0.01 && location.speedMph > 1.0) {
        fuelRemaining - (milesRemaining / location.speedMph) * fuelUseGph
    } else {
        fuelRemaining
    }

    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (trip.active) "Active Trip" else "Trip Not Started",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(
                    if (trip.active) "Running" else "Idle",
                    if (trip.active) BatteryGreen else TextMuted,
                    Modifier.weight(0.55f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CompactMetric("Remain", if (trip.active) "%.1f mi".format(milesRemaining) else "-", VoltageAmber, Modifier.weight(1f))
                CompactMetric("Arrival", if (trip.active) arrival else "-", BatteryGreen, Modifier.weight(1f))
                    CompactMetric("ETA", if (trip.active) eta else "-", VoltageAmber, Modifier.weight(1f))
            }
            if (showDetailRow) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactMetric("Done", if (trip.active) "%.1f mi".format(milesCompleted) else "-", TextPrimary, Modifier.weight(1f))
                    CompactMetric("Trip Fuel", if (trip.active) "%.2f gal".format(trip.fuelUsedGallons) else "-", WarningOrange, Modifier.weight(1f))
                    CompactMetric("Trip MPG", tripMpg?.let { "%.1f".format(it) } ?: "-", VoltageAmber, Modifier.weight(1f))
                }
            }
            if (trip.active) {
                Text(
                    "Fuel at arrival est. %.1f gal".format(projectedFuelAtArrival.coerceAtLeast(0.0)),
                    color = if (projectedFuelAtArrival < 2.0) WarningOrange else TextMuted,
                    fontSize = 13.sp
                )
            } else {
                Text("Set route miles and odometer on the Trip tab.", color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}
@Composable
internal fun DashboardTripCard(
    trip: ActiveTrip,
    location: LocationState,
    settings: AppSettings,
    onOpenTrip: () -> Unit
) {
    if (!trip.active) {
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(72.dp)) {
            Button(
                onClick = onOpenTrip,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(8.dp)
            ) {
                Text("Start Trip", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    val milesCompleted = trip.milesCompleted(location, settings.distanceScale)
    val milesRemaining = trip.milesRemaining(location, settings.distanceScale)
    val arrived = trip.arrived
    val eta = trip.etaText(location.speedMph, settings.distanceScale)
    val arrival = trip.arrivalText(location.speedMph, settings.distanceScale)

    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(72.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.Center) {
            if (arrived) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactMetric("Fuel Used", "%.2f gal".format(trip.arrivedFuelUsedGallons), WarningOrange, Modifier.weight(1f))
                    CompactMetric("Avg MPG", trip.arrivedAverageMpg.takeIf { it > 0.0 }?.let { "%.1f".format(it) } ?: "-", VoltageAmber, Modifier.weight(1f))
                    CompactMetric("Avg Speed", trip.arrivedAverageSpeedMph.takeIf { it > 0.0 }?.let { "%.1f mph".format(it) } ?: "-", BatteryGreen, Modifier.weight(1f))
                }
                Text("Stop or reset this trip from the Trip tab.", color = TextMuted, fontSize = 13.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactMetric("Remain", "%.1f mi".format(milesRemaining), VoltageAmber, Modifier.weight(1f))
                    CompactMetric("Arrival", arrival, BatteryGreen, Modifier.weight(1f))
                    CompactMetric("ETA", eta, VoltageAmber, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun DashboardTripGraph(activeTrip: ActiveTrip, historyPoints: List<FuelHistoryPoint>) {
    val tripPoints = remember(activeTrip.startTimeMs, historyPoints) {
        if (activeTrip.active && activeTrip.startTimeMs > 0L) {
            historyPoints.filter { it.timestampMs >= activeTrip.startTimeMs }.sortedBy { it.timestampMs }
        } else {
            emptyList()
        }
    }
    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Trip Graph", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("MPG", color = VoltageAmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("ALT", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("TANK", color = BatteryGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            TripHistoryChart(tripPoints, activeTrip.startTimeMs)
        }
    }
}

@Composable
internal fun TripHistoryChart(points: List<FuelHistoryPoint>, tripStartMs: Long) {
    Canvas(modifier = Modifier.fillMaxWidth().height(176.dp)) {
        val left = 10.dp.toPx()
        val right = size.width - 10.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val labelPaint = android.graphics.Paint().apply {
            setColor(android.graphics.Color.argb(185, 159, 176, 198))
            textSize = 9.sp.toPx()
            isAntiAlias = true
        }

        drawLine(TextMuted.copy(alpha = 0.26f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        drawLine(TextMuted.copy(alpha = 0.26f), Offset(left, top), Offset(left, bottom), 1.dp.toPx())
        repeat(4) { tick ->
            val y = top + (bottom - top) * tick / 3f
            drawLine(TextMuted.copy(alpha = 0.13f), Offset(left, y), Offset(right, y), 1.dp.toPx())
        }

        val hasAnySeriesData = points.count { it.displayMpg() != null } >= 2 ||
            points.count { it.altitudeFt.takeIfHistoryValid() != null } >= 2 ||
            points.count { it.tankGallons.takeIfHistoryValid() != null } >= 2

        if (tripStartMs <= 0L || points.size < 2 || !hasAnySeriesData) {
            drawIntoCanvas {
                it.nativeCanvas.drawText(
                    if (tripStartMs > 0L) "Trip graph will fill as valid samples arrive" else "Start a trip for live graph",
                    left + 8.dp.toPx(),
                    top + 28.dp.toPx(),
                    labelPaint
                )
            }
            return@Canvas
        }

        val windowStart = tripStartMs
        val windowEnd = max(System.currentTimeMillis(), points.last().timestampMs + 1L)
        repeat(4) { tick ->
            val x = left + (right - left) * tick / 3f
            drawLine(TextMuted.copy(alpha = 0.10f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
        }

        drawHistorySeries(points, windowStart, windowEnd, left, right, top, bottom, VoltageAmber) { it.displayMpg() }
        drawHistorySeries(points, windowStart, windowEnd, left, right, top, bottom, TextPrimary) { it.altitudeFt.takeIfHistoryValid() }
        drawHistorySeries(points, windowStart, windowEnd, left, right, top, bottom, BatteryGreen) { it.tankGallons }

        drawIntoCanvas {
            it.nativeCanvas.drawText(
                "${points.first().timestampMs.formatTimeLabel()}  -  ${points.last().timestampMs.formatTimeLabel()}",
                left,
                size.height - 4.dp.toPx(),
                labelPaint
            )
        }
    }
}

@Composable
internal fun GaugeCard(
    mpg: Double?,
    bleConnected: Boolean,
    fuelRemainingGallons: Double,
    tankCapacityGallons: Double,
    virtualOdometer: Double,
    activeTrip: ActiveTrip,
    location: LocationState,
    distanceScale: Double,
    onApplyDashboardOdometer: (Double, Boolean) -> Unit
) {
    var showOdometerDialog by remember { mutableStateOf(false) }
    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(14.dp)
            ) {
                FuelBarGauge(
                    gallons = fuelRemainingGallons,
                    tankCapacityGallons = tankCapacityGallons,
                    modifier = Modifier.weight(0.28f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(0.72f)) {
                    HalfGauge(
                        value = (mpg ?: 0.0).coerceIn(0.0, 30.0),
                        max = 30.0,
                        label = "INSTANT MPG",
                        valueText = if (bleConnected) "%.1f".format(mpg ?: 0.0) else "-",
                        subValueText = virtualOdometer.takeIf { it > 0.0 }?.let { "%.1f".format(it) } ?: "-",
                        onSubValueClick = { showOdometerDialog = true },
                        color = when {
                            !bleConnected -> TextMuted
                            (mpg ?: 0.0) >= 7.0 -> BatteryGreen
                            else -> WarningOrange
                        }
                    )
                }
            }
            Text("Bench target: 55 MPH / 10 MPG = 5.5 GPH", color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp))
        }
    }
    if (showOdometerDialog) {
        OdometerQuickSyncDialog(
            currentOdometer = virtualOdometer,
            activeTrip = activeTrip,
            location = location,
            distanceScale = distanceScale,
            onDismiss = { showOdometerDialog = false },
            onApply = { odometer, applyScale ->
                onApplyDashboardOdometer(odometer, applyScale)
                showOdometerDialog = false
            }
        )
    }
}
@Composable
internal fun DashboardFuelTile(gallons: Double, tankCapacityGallons: Double, modifier: Modifier = Modifier) {
    Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = modifier.height(152.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp)
        ) {
            FuelBarGauge(
                gallons = gallons,
                tankCapacityGallons = tankCapacityGallons,
                modifier = Modifier.fillMaxWidth(),
                heightDp = 128
            )
        }
    }
}

@Composable
internal fun GradeTile(gradeState: GradeState, gradeUnit: GradeUnit, modifier: Modifier = Modifier) {
    val gradePercent = gradeState.gradePercent
    val displayValue = gradePercent?.let {
        when (gradeUnit) {
            GradeUnit.Percent -> "%.1f%%".format(it)
            GradeUnit.Degrees -> "%.1f deg".format(Math.toDegrees(atan(it / 100.0)))
        }
    } ?: "-"
    val color = when {
        gradePercent == null -> TextMuted
        gradePercent <= -5.0 -> WarningOrange
        else -> TextPrimary
    }
    Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = modifier.height(72.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val grade = (gradePercent ?: 0.0).coerceIn(-MaxGradeDisplayPercent, MaxGradeDisplayPercent)
                val centerY = size.height / 2f
                val swing = (abs(grade) / MaxGradeDisplayPercent).toFloat() * (size.height * 0.38f)
                val leftY = centerY + if (grade >= 0.0) swing else -swing
                val rightY = centerY - if (grade >= 0.0) swing else -swing
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, leftY)
                    lineTo(size.width, rightY)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, color = color.copy(alpha = 0.16f))
                drawLine(
                    color = color.copy(alpha = 0.34f),
                    start = Offset(0f, leftY),
                    end = Offset(size.width, rightY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = TextMuted.copy(alpha = 0.22f),
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = 1.dp.toPx()
                )
            }
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(6.dp)
            ) {
                Text("Grade", color = TextMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
                Text(displayValue, color = color, fontSize = displayValue.tileFontSize(), fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun OdometerTile(
    odometer: Double,
    activeTrip: ActiveTrip,
    location: LocationState,
    distanceScale: Double,
    onApplyDashboardOdometer: (Double, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showOdometerDialog by remember { mutableStateOf(false) }
    Surface(
        color = PanelAlt,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .height(72.dp)
            .clickable { showOdometerDialog = true }
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(6.dp)
        ) {
            Text("Odometer", color = TextMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
            Text(
                odometer.takeIf { it > 0.0 }?.let { "%.1f".format(it) } ?: "-",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
    if (showOdometerDialog) {
        OdometerQuickSyncDialog(
            currentOdometer = odometer,
            activeTrip = activeTrip,
            location = location,
            distanceScale = distanceScale,
            onDismiss = { showOdometerDialog = false },
            onApply = { newOdometer, applyScale ->
                onApplyDashboardOdometer(newOdometer, applyScale)
                showOdometerDialog = false
            }
        )
    }
}

@Composable
internal fun HalfGauge(
    value: Double,
    max: Double,
    label: String,
    valueText: String,
    subValueText: String,
    onSubValueClick: () -> Unit,
    color: Color
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(190.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 24.dp.toPx()
            val gaugeWidth = size.width * 0.86f
            val arcHeight = gaugeWidth * 0.50f
            val left = (size.width - gaugeWidth) / 2f
            val top = 28.dp.toPx()
            val rect = Rect(left, top, left + gaugeWidth, top + arcHeight * 2f)
            val capAngle = Math.toDegrees((stroke / 2f / (rect.width / 2f)).toDouble()).toFloat()
            val start = 180f + capAngle
            val sweep = 180f - capAngle * 2f
            val progress = sweep * (value / max).toFloat().coerceIn(0f, 1f)

            drawArc(
                color = PanelAlt,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = start,
                sweepAngle = progress,
                useCenter = false,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            for (tick in 0..10) {
                val angle = Math.toRadians(180.0 + tick * 18.0)
                val center = Offset(rect.center.x, rect.center.y)
                val outer = Offset(
                    center.x + cos(angle).toFloat() * rect.width / 2f,
                    center.y + sin(angle).toFloat() * rect.height / 2f
                )
                val inner = Offset(
                    center.x + cos(angle).toFloat() * (rect.width / 2f - 18.dp.toPx()),
                    center.y + sin(angle).toFloat() * (rect.height / 2f - 18.dp.toPx())
                )
                drawLine(Color.White.copy(alpha = if (tick % 5 == 0) 0.78f else 0.35f), inner, outer, if (tick % 5 == 0) 3.dp.toPx() else 2.dp.toPx(), StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 58.dp)) {
            Text(valueText, color = color, fontSize = 50.sp, fontWeight = FontWeight.Bold)
            Text(label, color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onSubValueClick)
            ) {
                Text(subValueText, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("ODO", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun OdometerQuickSyncDialog(
    currentOdometer: Double,
    activeTrip: ActiveTrip,
    location: LocationState,
    distanceScale: Double,
    onDismiss: () -> Unit,
    onApply: (Double, Boolean) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val prefill = remember(currentOdometer) { currentOdometer.leadingOdometerDigits() }
    var odometerText by remember(prefill) {
        mutableStateOf(TextFieldValue(prefill, selection = TextRange(prefill.length)))
    }
    val enteredOdometer = odometerText.text.toDoubleOrNull()
    val proposedScale = enteredOdometer?.let { activeTrip.proposedDistanceScale(location, distanceScale, it) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = {
            focusManager.clearFocus()
            onDismiss()
        },
        title = { Text("Sync Odometer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Current app ODO: ${currentOdometer.takeIf { it > 0.0 }?.let { "%.1f".format(it) } ?: "-"}",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                if (proposedScale != null) {
                    Text("New distance scale: %.3f".format(proposedScale), color = VoltageAmber, fontSize = 13.sp)
                } else {
                    Text("Distance scale needs an active trip with enough driven miles.", color = TextMuted, fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        enteredOdometer?.let { onApply(it, true) }
                    },
                    enabled = enteredOdometer != null && proposedScale != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sync + Apply Correction")
                }
                TextButton(
                    onClick = {
                        focusManager.clearFocus()
                        enteredOdometer?.let { onApply(it, false) }
                    },
                    enabled = enteredOdometer != null && enteredOdometer > 0.0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sync Odometer Only")
                }
                OutlinedTextField(
                    value = odometerText,
                    onValueChange = { odometerText = it },
                    label = { Text("Actual RV odometer") },
                    suffix = { Text("mi") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                focusManager.clearFocus()
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
internal fun FuelBarGauge(
    gallons: Double,
    tankCapacityGallons: Double,
    modifier: Modifier = Modifier,
    heightDp: Int = 196
) {
    val fraction = if (tankCapacityGallons > 0.0) (gallons / tankCapacityGallons).coerceIn(0.0, 1.0) else 0.0
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.height(heightDp.dp)) {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .size(width = 48.dp, height = (heightDp - 42).coerceAtLeast(72).dp)
                .background(PanelAlt, RoundedCornerShape(8.dp))
                .padding(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(((heightDp - 54).coerceAtLeast(60) * fraction).dp)
                    .background(
                        when {
                            fraction <= 0.15 -> WarningOrange
                            fraction <= 0.30 -> VoltageAmber
                            else -> BatteryGreen
                        },
                        RoundedCornerShape(6.dp)
                    )
            )
            Canvas(Modifier.fillMaxSize()) {
                listOf(0.0f, 0.25f, 0.50f, 0.75f, 1.0f).forEach { mark ->
                    val y = size.height * (1f - mark)
                    drawLine(
                        color = Color.White.copy(alpha = if (mark == 0.0f || mark == 1.0f || mark == 0.50f) 0.82f else 0.58f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = if (mark == 0.0f || mark == 1.0f || mark == 0.50f) 2.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("%.1f gal".format(gallons), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
