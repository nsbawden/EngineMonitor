package com.example.fuelmonitor.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fuelmonitor.FuelHistoryPoint
import com.example.fuelmonitor.displayMpg
import com.example.fuelmonitor.takeIfHistoryValid
import com.example.fuelmonitor.ui.history.HistoryAxis
import com.example.fuelmonitor.ui.history.drawHistorySeries
import com.example.fuelmonitor.ui.history.formatAxis
import com.example.fuelmonitor.ui.history.formatTimeLabel
import com.example.fuelmonitor.ui.history.toDayLabel
import com.example.fuelmonitor.ui.history.toDayStartMs
import com.example.fuelmonitor.ui.history.toHistoryTsv
import com.example.fuelmonitor.ui.history.valueFor
import com.example.fuelmonitor.ui.theme.BatteryGreen
import com.example.fuelmonitor.ui.theme.Panel
import com.example.fuelmonitor.ui.theme.PanelAlt
import com.example.fuelmonitor.ui.theme.TextMuted
import com.example.fuelmonitor.ui.theme.TextPrimary
import com.example.fuelmonitor.ui.theme.VoltageAmber
import kotlin.math.abs

@Composable
internal fun HistoryScreen(
    historyPoints: List<FuelHistoryPoint>,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val dayKeys = remember(historyPoints) { historyPoints.map { it.dayKey }.distinct().sortedDescending() }
    var selectedDayKey by rememberSaveable { mutableIntStateOf(dayKeys.firstOrNull() ?: 0) }
    var selectedAxis by rememberSaveable { mutableStateOf(HistoryAxis.Mpg) }

    LaunchedEffect(dayKeys) {
        if (selectedDayKey == 0 || (dayKeys.isNotEmpty() && selectedDayKey !in dayKeys)) {
            selectedDayKey = dayKeys.firstOrNull() ?: 0
        }
    }

    val selectedIndex = dayKeys.indexOf(selectedDayKey).takeIf { it >= 0 } ?: 0
    val dayPoints = remember(historyPoints, selectedDayKey) {
        historyPoints.filter { it.dayKey == selectedDayKey }.sortedBy { it.timestampMs }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("History", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (selectedDayKey == 0) "No live history yet" else selectedDayKey.toDayLabel(), fontWeight = FontWeight.SemiBold)
                Text(
                    if (historyPoints.isEmpty()) {
                        "History records only when ESP injector input and phone GPS are both live."
                    } else {
                        "${dayPoints.size} samples for selected day"
                    },
                    color = TextMuted,
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { selectedDayKey = dayKeys.getOrElse(selectedIndex + 1) { selectedDayKey } },
                        enabled = selectedIndex < dayKeys.lastIndex,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Prev")
                    }
                    Button(
                        onClick = { selectedDayKey = dayKeys.getOrElse(selectedIndex - 1) { selectedDayKey } },
                        enabled = selectedIndex > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Next")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(dayPoints.toHistoryTsv())) },
                        enabled = dayPoints.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy Day")
                    }
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(historyPoints.sortedBy { it.timestampMs }.toHistoryTsv())) },
                        enabled = historyPoints.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy All")
                    }
                }
            }
        }

        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Live Day Graph", fontWeight = FontWeight.SemiBold)
                CombinedHistoryChart(dayPoints, selectedDayKey, selectedAxis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HistoryAxisButton(HistoryAxis.Mpg, selectedAxis == HistoryAxis.Mpg, VoltageAmber, Modifier.weight(1f)) {
                        selectedAxis = HistoryAxis.Mpg
                    }
                    HistoryAxisButton(HistoryAxis.Altitude, selectedAxis == HistoryAxis.Altitude, TextPrimary, Modifier.weight(1f)) {
                        selectedAxis = HistoryAxis.Altitude
                    }
                    HistoryAxisButton(HistoryAxis.Tank, selectedAxis == HistoryAxis.Tank, BatteryGreen, Modifier.weight(1f)) {
                        selectedAxis = HistoryAxis.Tank
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoryAxisButton(axis: HistoryAxis, selected: Boolean, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (selected) color.copy(alpha = 0.28f) else PanelAlt,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.height(44.dp),
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(axis.label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun CombinedHistoryChart(points: List<FuelHistoryPoint>, dayKey: Int, axis: HistoryAxis) {
    var zoom by rememberSaveable(dayKey) { mutableFloatStateOf(1f) }
    var panMs by rememberSaveable(dayKey) { mutableFloatStateOf(0f) }
    var chartWidthPx by remember(dayKey) { mutableFloatStateOf(1f) }
    val dayStart = remember(dayKey) { dayKey.toDayStartMs() }
    val dayLengthMs = 24f * 60f * 60f * 1000f

    fun applyChartGesture(zoomChange: Float, panX: Float) {
        val previousVisible = dayLengthMs / zoom.coerceAtLeast(1f)
        zoom = (zoom * zoomChange).coerceIn(1f, 24f)
        val visibleMs = dayLengthMs / zoom.coerceAtLeast(1f)
        val maxPan = (dayLengthMs - visibleMs).coerceAtLeast(0f)
        val msPerPixel = previousVisible / chartWidthPx.coerceAtLeast(1f)
        panMs = (panMs - panX * msPerPixel).coerceIn(0f, maxPan)
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(310.dp)
            .onSizeChanged { chartWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(dayKey, points.size) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var previousCentroid: Offset? = null
                    var previousSpan = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            val centroid = pressed.map { it.position }.reduce { acc, offset -> acc + offset } / pressed.size.toFloat()
                            val span = pressed.map { (it.position - centroid).getDistance() }.average().toFloat()
                            val lastCentroid = previousCentroid
                            if (lastCentroid != null && previousSpan > 0f && span > 0f) {
                                applyChartGesture(span / previousSpan, centroid.x - lastCentroid.x)
                            }
                            previousCentroid = centroid
                            previousSpan = span
                            event.changes.forEach { it.consume() }
                        } else {
                            previousCentroid = null
                            previousSpan = 0f
                            val change = pressed.first()
                            val delta = change.positionChange()
                            if (abs(delta.x) > abs(delta.y)) {
                                applyChartGesture(1f, delta.x)
                                change.consume()
                            }
                        }
                    }
                }
            }
    ) {
        val left = 52.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = size.height - 38.dp.toPx()
        val labelPaint = android.graphics.Paint().apply {
            setColor(android.graphics.Color.argb(190, 159, 176, 198))
            textSize = 9.sp.toPx()
            isAntiAlias = true
        }

        drawLine(TextMuted.copy(alpha = 0.35f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        drawLine(TextMuted.copy(alpha = 0.35f), Offset(left, top), Offset(left, bottom), 1.dp.toPx())

        val visibleMs = dayLengthMs / zoom.coerceAtLeast(1f)
        val maxPan = (dayLengthMs - visibleMs).coerceAtLeast(0f)
        val windowStart = dayStart + panMs.coerceIn(0f, maxPan).toLong()
        val windowEnd = windowStart + visibleMs.toLong()
        val visiblePoints = points.filter { it.timestampMs in windowStart..windowEnd }

        val axisValues = visiblePoints.mapNotNull { it.valueFor(axis) }
        val hasAxisValues = axisValues.size >= 2
        val axisMin = axisValues.minOrNull() ?: 0.0
        val axisMax = axisValues.maxOrNull() ?: 1.0
        val axisPadding = ((axisMax - axisMin) * 0.08).takeIf { it > 0.0 } ?: 1.0
        val axisLow = axisMin - axisPadding
        val axisHigh = axisMax + axisPadding
        val axisRange = (axisHigh - axisLow).takeIf { it > 0.0 } ?: 1.0

        repeat(5) { tick ->
            val fraction = tick / 4f
            val y = bottom - (bottom - top) * fraction
            val tickValue = axisLow + axisRange * fraction
            drawLine(TextMuted.copy(alpha = 0.20f), Offset(left, y), Offset(right, y), 1.dp.toPx())
            drawLine(TextMuted.copy(alpha = 0.60f), Offset(left - 5.dp.toPx(), y), Offset(left, y), 1.dp.toPx())
            drawIntoCanvas {
                it.nativeCanvas.drawText(tickValue.formatAxis(axis.unit), 2.dp.toPx(), y + 3.dp.toPx(), labelPaint)
            }
        }

        repeat(5) { tick ->
            val fraction = tick / 4f
            val x = left + (right - left) * fraction
            val labelMs = windowStart + (windowEnd - windowStart) * tick / 4L
            drawLine(TextMuted.copy(alpha = 0.16f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            drawLine(TextMuted.copy(alpha = 0.60f), Offset(x, bottom), Offset(x, bottom + 5.dp.toPx()), 1.dp.toPx())
            drawIntoCanvas {
                it.nativeCanvas.drawText(labelMs.formatTimeLabel(), x - 18.dp.toPx(), bottom + 20.dp.toPx(), labelPaint)
            }
        }

        if (visiblePoints.size < 2 || !hasAxisValues) {
            drawIntoCanvas {
                val message = if (visiblePoints.size < 2) "No live samples in this view" else "No valid ${axis.label} samples in this view"
                it.nativeCanvas.drawText(message, left + 12.dp.toPx(), top + 28.dp.toPx(), labelPaint)
            }
            return@Canvas
        }

        drawHistorySeries(visiblePoints, windowStart, windowEnd, left, right, top, bottom, VoltageAmber) { it.displayMpg() }
        drawHistorySeries(visiblePoints, windowStart, windowEnd, left, right, top, bottom, TextPrimary) { it.altitudeFt.takeIfHistoryValid() }
        drawHistorySeries(visiblePoints, windowStart, windowEnd, left, right, top, bottom, BatteryGreen) { it.tankGallons }
    }
}
