package com.example.fuelmonitor

import kotlin.math.abs

data class InjectorTelemetry(
    val widthUs: Double = 0.0,
    val pulsesPerSecond: Double = 0.0,
    val duty: Double = 0.0,
    val gallonsPerHour: Double = 0.0,
    val injectorFlowLbHr: Double = 19.0,
    val injectorCount: Int = 8,
    val signalActive: Boolean = false,
    val simulatedTelemetryEnabled: Boolean = false,
    val wifiServiceEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val otaIp: String = "0.0.0.0",
    val wifiAttemptSsid: String = "",
    val rawPacket: String = ""
) {
    val rpm: Double get() = pulsesPerSecond * 120.0
    fun mpg(speedMph: Double): Double = calculateInstantMpgForLiveFuel(gallonsPerHour, speedMph)
}

data class FuelBleState(
    val supported: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val hasPermissions: Boolean = false,
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val controlReady: Boolean = false,
    val status: String = "Ready",
    val wifiControlStatus: String = "",
    val telemetry: InjectorTelemetry = InjectorTelemetry()
)

enum class WifiCredentialPhase {
    Idle,
    Sending,
    Sent,
    Failed,
    WaitingJoin,
    Joined,
    TimedOut
}

data class LocationState(
    val hasPermission: Boolean = false,
    val speedMph: Double = BenchSpeedMph,
    val speedFromCurrentFix: Boolean = false,
    val speedSimulated: Boolean = true,
    val altitudeFt: Double = 0.0,
    val hasAltitude: Boolean = false,
    val altitudeFromCurrentFix: Boolean = false,
    val usingFallbackSpeed: Boolean = true,
    val status: String = "Bench speed simulation: 55 MPH"
)

enum class AltitudeUnit(val label: String) {
    Feet("ft"),
    Meters("m")
}

enum class GradeUnit(val label: String) {
    Percent("%"),
    Degrees("deg")
}

data class AppSettings(
    val tankCapacityGallons: Double = 25.0,
    val fuelCalibrationMultiplier: Double = 1.0,
    val injectorFlowLbHr: Double = 19.0,
    val injectorCount: Int = 8,
    val altitudeUnit: AltitudeUnit = AltitudeUnit.Feet,
    val gradeUnit: GradeUnit = GradeUnit.Percent,
    val calibrationStartOdometer: Double = 0.0,
    val phoneSpeedSimulationEnabled: Boolean = true,
    val dashboardAverageWindowHours: Double = 1.0,
    val distanceScale: Double = 0.8,
    val instantMpgGoodThreshold: Double = 7.0,
    val keepScreenOn: Boolean = true
)

data class ActiveTrip(
    val active: Boolean = false,
    val startOdometer: Double = 0.0,
    val plannedMiles: Double = 0.0,
    val startTimeMs: Long = 0L,
    val fuelUsedGallons: Double = 0.0,
    val distanceOffsetMiles: Double = 0.0,
    val lastDistanceUpdateMs: Long = 0L,
    val movingTimeMs: Long = 0L,
    val arrived: Boolean = false,
    val arrivedTimeMs: Long = 0L,
    val arrivedFuelUsedGallons: Double = 0.0,
    val arrivedAverageMpg: Double = 0.0,
    val arrivedAverageSpeedMph: Double = 0.0
)

data class GradeState(
    val acceptedAltitudeFt: Double? = null,
    val acceptedOdometer: Double? = null,
    val gradePercent: Double? = null
) {
    fun accept(altitudeFt: Double, odometer: Double): GradeState {
        if (!altitudeFt.isFinite() || !odometer.isFinite() || odometer <= 0.0) return this
        val startAltitude = acceptedAltitudeFt
        val startOdometer = acceptedOdometer
        if (startAltitude == null || startOdometer == null || odometer < startOdometer) {
            return copy(acceptedAltitudeFt = altitudeFt, acceptedOdometer = odometer)
        }
        val altitudeDelta = altitudeFt - startAltitude
        if (abs(altitudeDelta) < 1.0) return this
        val distanceFeet = (odometer - startOdometer) * 5280.0
        if (distanceFeet < MinGradeDistanceFeet) return this
        val grade = (altitudeDelta / distanceFeet) * 100.0
        return GradeState(
            acceptedAltitudeFt = altitudeFt,
            acceptedOdometer = odometer,
            gradePercent = grade.takeIf { it.isFinite() }?.coerceIn(-MaxGradeDisplayPercent, MaxGradeDisplayPercent)
        )
    }
}

data class FillupRecord(
    val timestampMs: Long = 0L,
    val odometer: Double = 0.0,
    val gallonsAdded: Double = 0.0,
    val pricePerGallon: Double = 0.0,
    val totalCost: Double = 0.0,
    val filledToFull: Boolean = true,
    val mpgSinceLastFillup: Double = 0.0
)

data class EconomySample(
    val timestampMs: Long,
    val miles: Double,
    val gallons: Double
)

data class FuelHistoryPoint(
    val timestampMs: Long,
    val dayKey: Int,
    val speedMph: Double,
    val rpm: Double,
    val instantMpg: Double,
    val gallonsPerHour: Double,
    val injectorPulseWidthUs: Double,
    val dutyPercent: Double,
    val altitudeFt: Double,
    val tankGallons: Double,
    val espSimulated: Boolean,
    val phoneSpeedSimulated: Boolean
) {
    fun toCsvLine(): String = listOf(
        timestampMs,
        dayKey,
        speedMph,
        rpm,
        instantMpg,
        gallonsPerHour,
        injectorPulseWidthUs,
        dutyPercent,
        altitudeFt,
        tankGallons,
        if (espSimulated) 1 else 0,
        if (phoneSpeedSimulated) 1 else 0
    ).joinToString(",")

    companion object {
        fun fromCsvLine(line: String): FuelHistoryPoint? {
            val parts = line.split(',')
            if (parts.size < 5) return null
            if (parts.size < 12) {
                return FuelHistoryPoint(
                    timestampMs = parts[0].toLongOrNull() ?: return null,
                    dayKey = parts[1].toIntOrNull() ?: return null,
                    speedMph = 0.0,
                    rpm = 0.0,
                    instantMpg = parts[2].toDoubleOrNull() ?: return null,
                    gallonsPerHour = 0.0,
                    injectorPulseWidthUs = 0.0,
                    dutyPercent = 0.0,
                    altitudeFt = parts[3].toDoubleOrNull() ?: return null,
                    tankGallons = parts[4].toDoubleOrNull() ?: return null,
                    espSimulated = false,
                    phoneSpeedSimulated = false
                ).takeIf { it.isUsable() }
            }
            return FuelHistoryPoint(
                timestampMs = parts[0].toLongOrNull() ?: return null,
                dayKey = parts[1].toIntOrNull() ?: return null,
                speedMph = parts[2].toDoubleOrNull() ?: return null,
                rpm = parts[3].toDoubleOrNull() ?: return null,
                instantMpg = parts[4].toDoubleOrNull() ?: return null,
                gallonsPerHour = parts[5].toDoubleOrNull() ?: return null,
                injectorPulseWidthUs = parts[6].toDoubleOrNull() ?: return null,
                dutyPercent = parts[7].toDoubleOrNull() ?: return null,
                altitudeFt = parts[8].toDoubleOrNull() ?: return null,
                tankGallons = parts[9].toDoubleOrNull() ?: return null,
                espSimulated = parts[10] == "1",
                phoneSpeedSimulated = parts[11] == "1"
            ).takeIf { it.isUsable() }
        }
    }
}

fun FuelHistoryPoint.isUsable(): Boolean {
    return timestampMs > 0L &&
        speedMph.isFinite() &&
        rpm.isFinite() &&
        instantMpg.isFinite() &&
        gallonsPerHour.isFinite() &&
        injectorPulseWidthUs.isFinite() &&
        dutyPercent.isFinite() &&
        altitudeFt.isFinite() &&
        tankGallons.isFinite()
}
