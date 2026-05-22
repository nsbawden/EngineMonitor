package com.example.fuelmonitor

import java.util.UUID

internal val ServiceUuid: UUID = UUID.fromString("5f6d9f20-6f2d-4f51-a2c7-302000000001")
internal val TelemetryUuid: UUID = UUID.fromString("5f6d9f20-6f2d-4f51-a2c7-302000000002")
internal val ControlUuid: UUID = UUID.fromString("5f6d9f20-6f2d-4f51-a2c7-302000000003")
internal val CccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

internal const val DeviceName = "ESP Engine Monitor"
internal const val LegacyDeviceName = "302 Fuel Monitor"
internal const val BenchSpeedMph = 55.0
internal const val MinimumFuelFlowForMpgGph = 0.01
internal const val MinimumSpeedForMpgMph = 1.0
internal const val HistoryMpgDisplayCap = 60.0
internal const val DashboardMpgDisplayCap = 60.0
internal const val MissingHistoryValue = -999999.0
internal const val MinGradeDistanceFeet = 100.0
internal const val MaxGradeDisplayPercent = 20.0
internal const val MaxDistanceSampleMs = 10_000L
internal const val MovingSpeedThresholdMph = 2.0
internal const val MaxFuelIntegrationMs = 5_000L
internal const val FuelDensityLbPerGallon = 6.17
internal const val WifiServiceConfirmTimeoutMs = 18_000L
internal const val WifiJoinConfirmTimeoutMs = 35_000L
