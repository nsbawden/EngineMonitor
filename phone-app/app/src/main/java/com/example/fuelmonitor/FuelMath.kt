package com.example.fuelmonitor

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun String?.toDoubleOrZero(): Double = this?.toDoubleOrNull() ?: 0.0

fun effectiveFuelGph(telemetry: InjectorTelemetry, settings: AppSettings): Double {
    val reported = telemetry.gallonsPerHour * settings.fuelCalibrationMultiplier
    if (reported > MinimumFuelFlowForMpgGph) return reported
    val duty = telemetry.duty
    if (duty <= 0.0 || settings.injectorFlowLbHr <= 0.0 || settings.injectorCount <= 0) return 0.0
    val estimated = (settings.injectorFlowLbHr * settings.injectorCount * duty) / FuelDensityLbPerGallon
    return estimated * settings.fuelCalibrationMultiplier
}

fun calculateInstantMpgForLiveFuel(fuelUseGph: Double, speedMph: Double): Double {
    return if (fuelUseGph > MinimumFuelFlowForMpgGph && speedMph > MinimumSpeedForMpgMph) {
        speedMph / fuelUseGph
    } else {
        0.0
    }
}

fun calculateInstantMpgForDisplay(connected: Boolean, fuelUseGph: Double, speedMph: Double): Double? {
    if (!connected) return null
    return calculateInstantMpgForLiveFuel(fuelUseGph, speedMph).coerceAtMost(DashboardMpgDisplayCap)
}

fun Double.isHistoryValid(): Boolean = isFinite() && this != MissingHistoryValue

fun Double.takeIfHistoryValid(): Double? = takeIf { it.isHistoryValid() }

fun FuelHistoryPoint.displayMpg(): Double? = instantMpg.takeIfHistoryValid()?.coerceIn(0.0, HistoryMpgDisplayCap)

fun List<EconomySample>.pruneEconomySamples(nowMs: Long, windowHours: Double): List<EconomySample> {
    val windowMs = (windowHours.coerceIn(0.05, 24.0) * 3_600_000.0).toLong()
    val oldest = nowMs - windowMs
    return filter { it.timestampMs >= oldest && it.miles.isFinite() && it.gallons.isFinite() }
}

fun AppSettings.dashboardAverageWindowLabel(): String {
    return if (dashboardAverageWindowHours < 1.0) {
        "${(dashboardAverageWindowHours * 60.0).toInt()}m"
    } else {
        "${dashboardAverageWindowHours.toInt()}h"
    }
}

fun formatDuration(hours: Double): String {
    if (!hours.isFinite() || hours < 0.0) return "-"
    val totalMinutes = (hours * 60.0).toLong()
    val h = totalMinutes / 60L
    val m = totalMinutes % 60L
    return if (h > 0L) "${h}h ${m}m" else "${m}m"
}

fun LocationState.altitudeFor(unit: AltitudeUnit): Double {
    return when (unit) {
        AltitudeUnit.Feet -> altitudeFt
        AltitudeUnit.Meters -> altitudeFt * 0.3048
    }
}

fun LocationState.altitudeText(unit: AltitudeUnit): String {
    return if (hasAltitude) {
        "%.0f ${unit.label}".format(altitudeFor(unit))
    } else {
        "- ${unit.label}"
    }
}

fun ActiveTrip.addDistanceSample(speedMph: Double, nowMs: Long, distanceScale: Double): ActiveTrip {
    if (!active || arrived) return this
    val lastMs = when {
        lastDistanceUpdateMs > 0L -> lastDistanceUpdateMs
        startTimeMs > 0L -> startTimeMs
        else -> nowMs
    }
    val elapsedHours = (nowMs - lastMs).coerceIn(0L, MaxDistanceSampleMs) / 3_600_000.0
    val miles = speedMph.coerceAtLeast(0.0) * distanceScale.coerceIn(0.25, 2.0) * elapsedHours
    val movingDeltaMs = if (speedMph >= MovingSpeedThresholdMph) {
        (nowMs - lastMs).coerceIn(0L, MaxDistanceSampleMs)
    } else {
        0L
    }
    return copy(
        distanceOffsetMiles = (distanceOffsetMiles + miles).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: distanceOffsetMiles,
        lastDistanceUpdateMs = nowMs,
        movingTimeMs = movingTimeMs + movingDeltaMs
    )
}

fun ActiveTrip.milesCompleted(location: LocationState, distanceScale: Double): Double {
    return distanceOffsetMiles.coerceIn(0.0, plannedMiles.coerceAtLeast(0.0))
}

fun ActiveTrip.milesRemaining(location: LocationState, distanceScale: Double): Double {
    return (plannedMiles - milesCompleted(location, distanceScale)).coerceAtLeast(0.0)
}

fun ActiveTrip.movingAverageSpeedMph(): Double? {
    val movingHours = movingTimeMs / 3_600_000.0
    if (!active || movingHours <= 0.0 || distanceOffsetMiles <= 0.01) return null
    return (distanceOffsetMiles / movingHours).takeIf { it.isFinite() && it > 1.0 }
}

fun ActiveTrip.etaText(speedMph: Double, distanceScale: Double): String {
    val avgSpeed = movingAverageSpeedMph() ?: return "-"
    val hours = milesRemaining(LocationState(speedMph = speedMph), distanceScale) / avgSpeed
    return formatDuration(hours)
}

fun ActiveTrip.arrivalText(speedMph: Double, distanceScale: Double): String {
    val avgSpeed = movingAverageSpeedMph() ?: return "-"
    val millis = System.currentTimeMillis() + ((milesRemaining(LocationState(speedMph = speedMph), distanceScale) / avgSpeed) * 3_600_000.0).toLong()
    return SimpleDateFormat("h:mm a", Locale.US).format(Date(millis))
}

fun ActiveTrip.averageSpeedMph(): Double? = movingAverageSpeedMph()

fun ActiveTrip.captureArrivalIfNeeded(location: LocationState, distanceScale: Double): ActiveTrip {
    if (!active || arrived || plannedMiles <= 0.0) return this
    if (milesCompleted(location, distanceScale) < plannedMiles) return this

    val avgSpeed = movingAverageSpeedMph() ?: 0.0
    val avgMpg = if (fuelUsedGallons > 0.01) plannedMiles / fuelUsedGallons else 0.0
    return copy(
        arrived = true,
        arrivedTimeMs = System.currentTimeMillis(),
        arrivedFuelUsedGallons = fuelUsedGallons,
        arrivedAverageMpg = avgMpg.takeIf { it.isFinite() } ?: 0.0,
        arrivedAverageSpeedMph = avgSpeed.takeIf { it.isFinite() } ?: 0.0
    )
}

fun ActiveTrip.adjustPlannedMilesFromRemaining(location: LocationState, distanceScale: Double, remainingMiles: Double): ActiveTrip {
    if (!active || remainingMiles < 0.0) return this
    val driven = milesCompleted(location, distanceScale)
    return copy(
        plannedMiles = (driven + remainingMiles).coerceAtLeast(driven),
        arrived = false,
        arrivedTimeMs = 0L,
        arrivedFuelUsedGallons = 0.0,
        arrivedAverageMpg = 0.0,
        arrivedAverageSpeedMph = 0.0
    )
}

fun ActiveTrip.syncDistanceToOdometer(location: LocationState, distanceScale: Double, currentOdometer: Double): ActiveTrip {
    if (!active || currentOdometer < startOdometer) return this
    val actualDriven = currentOdometer - startOdometer
    val currentMiles = distanceOffsetMiles.coerceAtLeast(0.0)
    val adjustedMovingTime = if (currentMiles > 0.1 && movingTimeMs > 0L) {
        (movingTimeMs * (actualDriven.coerceAtLeast(0.0) / currentMiles)).toLong().coerceAtLeast(0L)
    } else {
        movingTimeMs
    }
    return copy(
        distanceOffsetMiles = actualDriven.coerceAtLeast(0.0),
        lastDistanceUpdateMs = System.currentTimeMillis(),
        movingTimeMs = adjustedMovingTime,
        arrived = false,
        arrivedTimeMs = 0L,
        arrivedFuelUsedGallons = 0.0,
        arrivedAverageMpg = 0.0,
        arrivedAverageSpeedMph = 0.0
    )
}

fun ActiveTrip.proposedDistanceScale(location: LocationState, currentScale: Double, currentOdometer: Double): Double? {
    if (!active || startOdometer <= 0.0 || currentOdometer <= startOdometer) return null
    val actualDriven = currentOdometer - startOdometer
    if (actualDriven <= 0.1 || distanceOffsetMiles <= 0.1) return null
    return (currentScale * (actualDriven / distanceOffsetMiles))
        .takeIf { it.isFinite() }
        ?.coerceIn(0.25, 2.0)
        ?: currentScale.takeIf { it.isFinite() }
}

fun ActiveTrip.expectedOdometer(location: LocationState, distanceScale: Double, fallbackOdometer: Double): Double {
    return when {
        active && startOdometer > 0.0 -> startOdometer + milesCompleted(location, distanceScale)
        fallbackOdometer > 0.0 -> fallbackOdometer
        else -> 0.0
    }
}

fun Double.leadingOdometerDigits(): String {
    if (!isFinite() || this <= 0.0) return ""
    val whole = kotlin.math.floor(this).toLong().toString()
    return if (whole.length > 3) whole.dropLast(3) else ""
}

fun List<FuelHistoryPoint>.prunedHistory(nowMs: Long): List<FuelHistoryPoint> {
    val oldest = nowMs - 30L * 24L * 60L * 60L * 1000L
    return filter { it.timestampMs >= oldest }.sortedBy { it.timestampMs }
}
