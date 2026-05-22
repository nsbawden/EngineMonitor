package com.example.fuelmonitor.ui.history

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.example.fuelmonitor.FuelHistoryPoint
import com.example.fuelmonitor.displayMpg
import com.example.fuelmonitor.takeIfHistoryValid
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal enum class HistoryAxis(val label: String, val unit: String) {
    Mpg("MPG", "MPG"),
    Altitude("Altitude", "ft"),
    Tank("Tank", "gal")
}
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistorySeries(
    points: List<FuelHistoryPoint>,
    windowStart: Long,
    windowEnd: Long,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    color: Color,
    value: (FuelHistoryPoint) -> Double?
) {
    val values = points.mapNotNull(value)
    val min = values.minOrNull() ?: return
    val max = values.maxOrNull() ?: return
    val padding = ((max - min) * 0.08).takeIf { it > 0.0 } ?: 1.0
    val low = min - padding
    val range = (max + padding - low).takeIf { it > 0.0 } ?: 1.0
    val spanMs = (windowEnd - windowStart).toDouble().takeIf { it > 0.0 } ?: 1.0
    val chartPoints = points.mapNotNull { point ->
        val pointValue = value(point) ?: return@mapNotNull null
        val x = left + (right - left) * (((point.timestampMs - windowStart) / spanMs).toFloat())
        val normalized = ((pointValue - low) / range).toFloat().coerceIn(0f, 1f)
        val y = bottom - (bottom - top) * normalized
        Offset(x, y)
    }
    chartPoints.zipWithNext().forEach { (a, b) ->
        drawLine(color, a, b, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

internal fun FuelHistoryPoint.valueFor(axis: HistoryAxis): Double? {
    return when (axis) {
        HistoryAxis.Mpg -> displayMpg()
        HistoryAxis.Altitude -> altitudeFt.takeIfHistoryValid()
        HistoryAxis.Tank -> tankGallons.takeIfHistoryValid()
    }
}
internal fun List<FuelHistoryPoint>.toHistoryTsv(): String {
    val header = "date_time\tspeed_mph\trpm\tinstant_mpg\tgph\tinjector_pulse_width_us\tduty_percent\taltitude_ft\ttank_gallons\tesp_simulated\tphone_speed_simulated"
    val rows = sortedBy { it.timestampMs }.map { point ->
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(point.timestampMs))
        listOf(
            date,
            "%.2f".format(point.speedMph),
            "%.0f".format(point.rpm),
            "%.2f".format(point.instantMpg),
            "%.3f".format(point.gallonsPerHour),
            "%.0f".format(point.injectorPulseWidthUs),
            "%.3f".format(point.dutyPercent),
            "%.1f".format(point.altitudeFt),
            "%.2f".format(point.tankGallons),
            if (point.espSimulated) "yes" else "no",
            if (point.phoneSpeedSimulated) "yes" else "no"
        ).joinToString("\t")
    }
    return (listOf(header) + rows).joinToString("\n")
}
internal fun Long.toDayKey(): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = this
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH) + 1
    val day = calendar.get(Calendar.DAY_OF_MONTH)
    return year * 10_000 + month * 100 + day
}

internal fun Int.toDayStartMs(): Long {
    if (this <= 0) return System.currentTimeMillis().toDayKey().toDayStartMs()
    val year = this / 10_000
    val month = (this / 100) % 100
    val day = this % 100
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.YEAR, year)
    calendar.set(Calendar.MONTH, month - 1)
    calendar.set(Calendar.DAY_OF_MONTH, day)
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

internal fun Int.toDayLabel(): String {
    return SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(Date(toDayStartMs()))
}

internal fun Long.formatTimeLabel(): String {
    return SimpleDateFormat("h:mm a", Locale.US).format(Date(this))
}

internal fun Double.formatAxis(unit: String): String {
    return when (unit) {
        "ft" -> "%.0f".format(this)
        "gal" -> "%.1f".format(this)
        else -> "%.1f".format(this)
    }
}
