package com.example.fuelmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.fuelmonitor.ui.FuelMonitorApp
import com.example.fuelmonitor.ui.history.toDayKey
import com.example.fuelmonitor.ui.theme.FuelMonitorTheme
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var fuelBleClient: FuelBleClient
    private lateinit var locationTracker: LocationTracker
    private var bleState by mutableStateOf(FuelBleState())
    private var locationState by mutableStateOf(LocationState())
    private var appSettings by mutableStateOf(AppSettings())
    private var activeTrip by mutableStateOf(ActiveTrip())
    private var permissionStatusMessage by mutableStateOf("")
    private var fuelUsedSinceFullGallons by mutableStateOf(0.0)
    private var calibrationTripEstimatedGallons by mutableStateOf(0.0)
    private var virtualOdometer by mutableStateOf(0.0)
    private var gradeState by mutableStateOf(GradeState())
    private var fillupRecords by mutableStateOf(emptyList<FillupRecord>())
    private var historyPoints by mutableStateOf(emptyList<FuelHistoryPoint>())
    private var rollingEconomySamples by mutableStateOf(emptyList<EconomySample>())
    private var lastFuelSampleMs = 0L
    private var lastHistorySampleMs = 0L
    private var lastHistoryPruneMs = 0L
    private var lastSyncedInjectorFlowLbHr: Double? = null
    private var lastSyncedInjectorCount: Int? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        fuelBleClient.refresh()
        locationTracker.start()
        permissionStatusMessage = if (fuelBleClient.hasPermissions() && locationTracker.hasPermission()) {
            "Permissions ready"
        } else {
            "Some permissions still need attention"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        appSettings = loadSettings()
        applyKeepScreenOn(appSettings.keepScreenOn)
        applyMonitoringKeepAlive(appSettings.keepScreenOn)
        activeTrip = loadActiveTrip()
        fuelUsedSinceFullGallons = loadDouble(PREF_FUEL_USED_SINCE_FULL, 0.0)
        calibrationTripEstimatedGallons = loadDouble(PREF_CAL_ESTIMATED_GALLONS, 0.0)
        virtualOdometer = loadDouble(PREF_VIRTUAL_ODOMETER, 0.0)
        fillupRecords = loadFillupRecords()
        historyPoints = loadHistoryPoints()
        if (virtualOdometer <= 0.0) {
            virtualOdometer = fillupRecords.firstOrNull()?.odometer ?: activeTrip.startOdometer
            if (virtualOdometer > 0.0) saveDouble(PREF_VIRTUAL_ODOMETER, virtualOdometer)
        }
        fuelBleClient = FuelBleClient(this) { acceptBleState(it) }
        locationTracker = LocationTracker(this) { acceptLocationState(it) }
        locationTracker.setSimulation(appSettings.phoneSpeedSimulationEnabled, BenchSpeedMph)
        fuelBleClient.refresh()
        requestPermissions.launch(requiredAppPermissions())
        setContent {
            FuelMonitorTheme {
                FuelMonitorApp(
                    bleState = bleState,
                    locationState = locationState,
                    appSettings = appSettings,
                    fuelUsedSinceFullGallons = fuelUsedSinceFullGallons,
                    calibrationTripEstimatedGallons = calibrationTripEstimatedGallons,
                    virtualOdometer = virtualOdometer,
                    gradeState = gradeState,
                    fillupRecords = fillupRecords,
                    historyPoints = historyPoints,
                    rollingAverageMpg = rollingAverageMpg(),
                    activeTrip = activeTrip,
                    permissionStatusMessage = permissionStatusMessage,
                    onRequestPermissions = {
                        requestAppPermissions()
                    },
                    onScan = { fuelBleClient.startScan() },
                    onDisconnect = { fuelBleClient.disconnect() },
                    onSetEspSimulation = { fuelBleClient.setEspSimulation(it) },
                    onSetEspWifiEnabled = { enabled, onResult -> fuelBleClient.setEspWifiEnabled(enabled, onResult) },
                    onConfigureEspWifi = { ssid, password, onResult ->
                        fuelBleClient.configureEspWifi(ssid, password, onResult)
                    },
                    onSetPhoneSpeedSimulation = { enabled ->
                        appSettings = appSettings.copy(phoneSpeedSimulationEnabled = enabled)
                        saveSettings(appSettings)
                        locationTracker.setSimulation(enabled, BenchSpeedMph)
                    },
                    onPrepareVehicleTest = { applyVehicleTestMode() },
                    onSettingsChanged = { updated ->
                        val injectorCalibrationChanged = updated.injectorFlowLbHr != appSettings.injectorFlowLbHr ||
                            updated.injectorCount != appSettings.injectorCount
                        appSettings = updated
                        rollingEconomySamples = rollingEconomySamples.pruneEconomySamples(System.currentTimeMillis(), updated.dashboardAverageWindowHours)
                        saveSettings(updated)
                        applyKeepScreenOn(updated.keepScreenOn)
                        applyMonitoringKeepAlive(updated.keepScreenOn)
                        locationTracker.setSimulation(updated.phoneSpeedSimulationEnabled, BenchSpeedMph)
                        if (injectorCalibrationChanged) {
                            lastSyncedInjectorFlowLbHr = null
                            lastSyncedInjectorCount = null
                        }
                        maybeSyncInjectorSettingsToEsp()
                    },
                    onResetFullTank = {
                        fuelUsedSinceFullGallons = 0.0
                        saveDouble(PREF_FUEL_USED_SINCE_FULL, fuelUsedSinceFullGallons)
                    },
                    onSetFuelGaugeFraction = { fraction ->
                        fuelUsedSinceFullGallons = (appSettings.tankCapacityGallons * (1.0 - fraction.coerceIn(0.0, 1.0))).coerceIn(0.0, appSettings.tankCapacityGallons)
                        saveDouble(PREF_FUEL_USED_SINCE_FULL, fuelUsedSinceFullGallons)
                    },
                    onStartTrip = { startOdometer, plannedMiles, useForCalibration ->
                        val now = System.currentTimeMillis()
                        activeTrip = ActiveTrip(
                            active = true,
                            startOdometer = startOdometer,
                            plannedMiles = plannedMiles,
                            startTimeMs = now,
                            fuelUsedGallons = 0.0,
                            distanceOffsetMiles = 0.0,
                            lastDistanceUpdateMs = now,
                            movingTimeMs = 0L,
                            arrived = false,
                            arrivedTimeMs = 0L,
                            arrivedFuelUsedGallons = 0.0,
                            arrivedAverageMpg = 0.0,
                            arrivedAverageSpeedMph = 0.0
                        )
                        saveActiveTrip(activeTrip)
                        if (useForCalibration) {
                            appSettings = appSettings.copy(calibrationStartOdometer = startOdometer)
                            calibrationTripEstimatedGallons = 0.0
                            saveSettings(appSettings)
                            saveDouble(PREF_CAL_ESTIMATED_GALLONS, calibrationTripEstimatedGallons)
                        }
                    },
                    onStopTrip = {
                        activeTrip = activeTrip.copy(active = false)
                        saveActiveTrip(activeTrip)
                    },
                    onResetTrip = {
                        activeTrip = ActiveTrip()
                        saveActiveTrip(activeTrip)
                    },
                    onApplyRemainingMiles = { remainingMiles ->
                        activeTrip = activeTrip.adjustPlannedMilesFromRemaining(locationState, appSettings.distanceScale, remainingMiles)
                        saveActiveTrip(activeTrip)
                    },
                    onApplyCurrentOdometer = { odometer ->
                        virtualOdometer = odometer
                        saveDouble(PREF_VIRTUAL_ODOMETER, virtualOdometer)
                        if (activeTrip.active) {
                            activeTrip = activeTrip.syncDistanceToOdometer(locationState, appSettings.distanceScale, odometer)
                            saveActiveTrip(activeTrip)
                        }
                    },
                    onApplyDashboardOdometer = { odometer, applyScale ->
                        val newScale = if (applyScale) {
                            activeTrip.proposedDistanceScale(locationState, appSettings.distanceScale, odometer)
                        } else {
                            null
                        }
                        if (newScale != null) {
                            appSettings = appSettings.copy(distanceScale = newScale)
                            saveSettings(appSettings)
                        }
                        virtualOdometer = odometer
                        saveDouble(PREF_VIRTUAL_ODOMETER, virtualOdometer)
                        if (activeTrip.active) {
                            activeTrip = activeTrip.syncDistanceToOdometer(
                                locationState,
                                newScale ?: appSettings.distanceScale,
                                odometer
                            )
                            saveActiveTrip(activeTrip)
                        }
                    },
                    onStartCalibrationTrip = { odometer ->
                        appSettings = appSettings.copy(calibrationStartOdometer = odometer)
                        calibrationTripEstimatedGallons = 0.0
                        saveSettings(appSettings)
                        saveDouble(PREF_CAL_ESTIMATED_GALLONS, calibrationTripEstimatedGallons)
                    },
                    onFinishCalibrationTrip = { odometer, gallons ->
                        val estimated = calibrationTripEstimatedGallons
                        if (estimated > 0.01 && gallons > 0.01 && odometer > appSettings.calibrationStartOdometer) {
                            appSettings = appSettings.copy(
                                fuelCalibrationMultiplier = (appSettings.fuelCalibrationMultiplier * (gallons / estimated)).coerceIn(0.25, 4.0),
                                calibrationStartOdometer = odometer
                            )
                            calibrationTripEstimatedGallons = 0.0
                            saveSettings(appSettings)
                            saveDouble(PREF_CAL_ESTIMATED_GALLONS, calibrationTripEstimatedGallons)
                        }
                    },
                    onApplyDirectCalibration = { knownMpg ->
                        val rawGph = effectiveFuelGph(bleState.telemetry, appSettings)
                        val targetGph = locationState.speedMph / knownMpg
                        if (rawGph > 0.01 && targetGph > 0.01 && targetGph.isFinite()) {
                            appSettings = appSettings.copy(
                                fuelCalibrationMultiplier = (targetGph / rawGph).coerceIn(0.25, 4.0)
                            )
                            saveSettings(appSettings)
                        }
                    },
                    onAddFillup = { record ->
                        val previous = fillupRecords.firstOrNull()
                        val mpg = previous?.let { prior ->
                            val miles = record.odometer - prior.odometer
                            if (miles > 0.0 && record.gallonsAdded > 0.0) miles / record.gallonsAdded else 0.0
                        } ?: 0.0
                        val savedRecord = record.copy(mpgSinceLastFillup = mpg)
                        fillupRecords = (listOf(savedRecord) + fillupRecords).take(MAX_FILLUP_RECORDS)
                        saveFillupRecords(fillupRecords)
                        fuelUsedSinceFullGallons = if (record.filledToFull) {
                            0.0
                        } else {
                            (fuelUsedSinceFullGallons - record.gallonsAdded).coerceIn(0.0, appSettings.tankCapacityGallons)
                        }
                        saveDouble(PREF_FUEL_USED_SINCE_FULL, fuelUsedSinceFullGallons)
                        virtualOdometer = record.odometer
                        saveDouble(PREF_VIRTUAL_ODOMETER, virtualOdometer)
                        if (activeTrip.active) {
                            activeTrip = activeTrip.syncDistanceToOdometer(locationState, appSettings.distanceScale, record.odometer)
                            saveActiveTrip(activeTrip)
                        }
                    }
                )
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = requiredAppPermissions().toList()
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionStatusMessage = "Permissions already granted"
            fuelBleClient.refresh()
            locationTracker.start()
        } else {
            permissionStatusMessage = "Requesting permissions"
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun applyVehicleTestMode() {
        appSettings = appSettings.copy(phoneSpeedSimulationEnabled = false)
        saveSettings(appSettings)
        locationTracker.setSimulation(false, BenchSpeedMph)
        fuelBleClient.setEspSimulation(false)
    }

    private fun maybeSyncInjectorSettingsToEsp() {
        if (!bleState.connected || !bleState.controlReady) return
        val flow = appSettings.injectorFlowLbHr
        val count = appSettings.injectorCount
        if (lastSyncedInjectorFlowLbHr == flow && lastSyncedInjectorCount == count) return
        lastSyncedInjectorFlowLbHr = flow
        lastSyncedInjectorCount = count
        fuelBleClient.syncInjectorCalibration(flow, count)
    }

    private fun acceptBleState(state: FuelBleState) {
        val now = System.currentTimeMillis()
        val controlJustReady = state.controlReady && !bleState.controlReady
        if (!state.connected) {
            lastSyncedInjectorFlowLbHr = null
            lastSyncedInjectorCount = null
        } else if (controlJustReady) {
            maybeSyncInjectorSettingsToEsp()
        }
        if (lastFuelSampleMs > 0L && state.connected) {
            val elapsedMs = (now - lastFuelSampleMs).coerceAtLeast(0L)
            val integratedMs = minOf(elapsedMs, MaxFuelIntegrationMs)
            val hours = integratedMs / 3_600_000.0
            val fuelGph = effectiveFuelGph(state.telemetry, appSettings)
            val gallons = fuelGph * hours
            if (gallons.isFinite() && gallons > 0.0 && hours > 0.0) {
                val miles = (locationState.speedMph * hours).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                rollingEconomySamples = (rollingEconomySamples + EconomySample(now, miles, gallons))
                    .pruneEconomySamples(now, appSettings.dashboardAverageWindowHours)
                fuelUsedSinceFullGallons += gallons
                calibrationTripEstimatedGallons += gallons
                if (activeTrip.active && !activeTrip.arrived) {
                    activeTrip = activeTrip
                        .copy(fuelUsedGallons = activeTrip.fuelUsedGallons + gallons)
                        .captureArrivalIfNeeded(locationState, appSettings.distanceScale)
                    saveActiveTrip(activeTrip)
                }
                saveDouble(PREF_FUEL_USED_SINCE_FULL, fuelUsedSinceFullGallons)
                saveDouble(PREF_CAL_ESTIMATED_GALLONS, calibrationTripEstimatedGallons)
            }
        }
        lastFuelSampleMs = now
        bleState = state
        maybeRecordHistory(now)
    }

    private fun rollingAverageMpg(): Double? {
        val gallons = rollingEconomySamples.sumOf { it.gallons }
        val miles = rollingEconomySamples.sumOf { it.miles }
        return if (gallons > 0.01 && miles > 0.01) miles / gallons else null
    }

    private fun acceptLocationState(state: LocationState) {
        val now = System.currentTimeMillis()
        if (activeTrip.active && !activeTrip.arrived) {
            activeTrip = activeTrip.addDistanceSample(locationState.speedMph, now, appSettings.distanceScale)
            saveActiveTrip(activeTrip)
        }
        locationState = state
        val currentOdometer = activeTrip.expectedOdometer(state, appSettings.distanceScale, virtualOdometer)
        if (state.altitudeFromCurrentFix) {
            gradeState = gradeState.accept(state.altitudeFt, currentOdometer)
        }
        if (activeTrip.active && !activeTrip.arrived) {
            val updatedTrip = activeTrip.captureArrivalIfNeeded(state, appSettings.distanceScale)
            if (updatedTrip.arrived) {
                activeTrip = updatedTrip
                saveActiveTrip(activeTrip)
            }
        }
    }

    override fun onDestroy() {
        FuelMonitorKeepAliveService.stop(this)
        fuelBleClient.close()
        locationTracker.stop()
        super.onDestroy()
    }

    private fun applySystemBars() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = android.graphics.Color.rgb(13, 22, 34)
        window.navigationBarColor = android.graphics.Color.rgb(13, 22, 34)
        val decorView = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decorView.post {
                decorView.windowInsetsController?.show(WindowInsets.Type.statusBars())
                decorView.windowInsetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility =
                decorView.systemUiVisibility and
                    View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        window.decorView.keepScreenOn = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyMonitoringKeepAlive(keepScreenOn: Boolean) {
        FuelMonitorKeepAliveService.start(this, keepScreenOn)
    }

    private fun requiredAppPermissions(): Array<String> {
        val permissions = (fuelBleClient.requiredPermissions() + locationTracker.requiredPermissions()).toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.distinct().toTypedArray()
    }

    private fun loadSettings(): AppSettings {
        val prefs = getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE)
        return AppSettings(
            tankCapacityGallons = prefs.getFloat(PREF_TANK_GALLONS, 25.0f).toDouble(),
            fuelCalibrationMultiplier = prefs.getFloat(PREF_FUEL_CAL_MULT, 1.0f).toDouble(),
            injectorFlowLbHr = prefs.getFloat(PREF_INJECTOR_FLOW, 19.0f).toDouble(),
            injectorCount = prefs.getInt(PREF_INJECTOR_COUNT, 8),
            altitudeUnit = runCatching {
                AltitudeUnit.valueOf(prefs.getString(PREF_ALT_UNIT, AltitudeUnit.Feet.name) ?: AltitudeUnit.Feet.name)
            }.getOrDefault(AltitudeUnit.Feet),
            gradeUnit = runCatching {
                GradeUnit.valueOf(prefs.getString(PREF_GRADE_UNIT, GradeUnit.Percent.name) ?: GradeUnit.Percent.name)
            }.getOrDefault(GradeUnit.Percent),
            calibrationStartOdometer = prefs.getFloat(PREF_CAL_START_ODO, 0.0f).toDouble(),
            phoneSpeedSimulationEnabled = prefs.getBoolean(PREF_PHONE_SPEED_SIM, true),
            dashboardAverageWindowHours = prefs.getFloat(PREF_DASH_AVG_HOURS, 1.0f).toDouble(),
            distanceScale = prefs.getFloat(PREF_DISTANCE_SCALE, 0.8f).toDouble(),
            instantMpgGoodThreshold = prefs.getFloat(PREF_INSTANT_MPG_GOOD, 7.0f).toDouble(),
            keepScreenOn = prefs.getBoolean(PREF_KEEP_SCREEN_ON, true)
        )
    }

    private fun saveSettings(settings: AppSettings) {
        getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE).edit()
            .putFloat(PREF_TANK_GALLONS, settings.tankCapacityGallons.toFloat())
            .putFloat(PREF_FUEL_CAL_MULT, settings.fuelCalibrationMultiplier.toFloat())
            .putFloat(PREF_INJECTOR_FLOW, settings.injectorFlowLbHr.toFloat())
            .putInt(PREF_INJECTOR_COUNT, settings.injectorCount)
            .putString(PREF_ALT_UNIT, settings.altitudeUnit.name)
            .putString(PREF_GRADE_UNIT, settings.gradeUnit.name)
            .putFloat(PREF_CAL_START_ODO, settings.calibrationStartOdometer.toFloat())
            .putBoolean(PREF_PHONE_SPEED_SIM, settings.phoneSpeedSimulationEnabled)
            .putFloat(PREF_DASH_AVG_HOURS, settings.dashboardAverageWindowHours.toFloat())
            .putFloat(PREF_DISTANCE_SCALE, settings.distanceScale.toFloat())
            .putFloat(PREF_INSTANT_MPG_GOOD, settings.instantMpgGoodThreshold.toFloat())
            .putBoolean(PREF_KEEP_SCREEN_ON, settings.keepScreenOn)
            .apply()
    }

    private fun loadDouble(key: String, fallback: Double): Double {
        return getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE).getFloat(key, fallback.toFloat()).toDouble()
    }

    private fun saveDouble(key: String, value: Double) {
        getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE).edit()
            .putFloat(key, value.toFloat())
            .apply()
    }

    private fun loadFillupRecords(): List<FillupRecord> {
        val raw = getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE).getString(PREF_FILLUP_RECORDS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size < 7) {
                    null
                } else {
                    FillupRecord(
                        timestampMs = parts[0].toLongOrNull() ?: 0L,
                        odometer = parts[1].toDoubleOrNull() ?: 0.0,
                        gallonsAdded = parts[2].toDoubleOrNull() ?: 0.0,
                        pricePerGallon = parts[3].toDoubleOrNull() ?: 0.0,
                        totalCost = parts[4].toDoubleOrNull() ?: 0.0,
                        filledToFull = parts[5] == "1",
                        mpgSinceLastFillup = parts[6].toDoubleOrNull() ?: 0.0
                    )
                }
            }
            .filter { it.timestampMs > 0L && it.odometer > 0.0 }
            .take(MAX_FILLUP_RECORDS)
            .toList()
    }

    private fun saveFillupRecords(records: List<FillupRecord>) {
        val raw = records.take(MAX_FILLUP_RECORDS).joinToString("\n") { record ->
            listOf(
                record.timestampMs,
                record.odometer,
                record.gallonsAdded,
                record.pricePerGallon,
                record.totalCost,
                if (record.filledToFull) 1 else 0,
                record.mpgSinceLastFillup
            ).joinToString("|")
        }
        getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE).edit()
            .putString(PREF_FILLUP_RECORDS, raw)
            .apply()
    }

    private fun loadActiveTrip(): ActiveTrip {
        val prefs = getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE)
        return ActiveTrip(
            active = prefs.getBoolean(PREF_TRIP_ACTIVE, false),
            startOdometer = prefs.getFloat(PREF_TRIP_START_ODO, 0.0f).toDouble(),
            plannedMiles = prefs.getFloat(PREF_TRIP_PLANNED_MILES, 0.0f).toDouble(),
            startTimeMs = prefs.getLong(PREF_TRIP_START_TIME, 0L),
            fuelUsedGallons = prefs.getFloat(PREF_TRIP_FUEL_USED, 0.0f).toDouble(),
            distanceOffsetMiles = prefs.getFloat(PREF_TRIP_DISTANCE_OFFSET, 0.0f).toDouble(),
            lastDistanceUpdateMs = prefs.getLong(PREF_TRIP_LAST_DISTANCE_UPDATE, 0L),
            movingTimeMs = prefs.getLong(PREF_TRIP_MOVING_TIME, 0L),
            arrived = prefs.getBoolean(PREF_TRIP_ARRIVED, false),
            arrivedTimeMs = prefs.getLong(PREF_TRIP_ARRIVED_TIME, 0L),
            arrivedFuelUsedGallons = prefs.getFloat(PREF_TRIP_ARRIVED_FUEL, 0.0f).toDouble(),
            arrivedAverageMpg = prefs.getFloat(PREF_TRIP_ARRIVED_MPG, 0.0f).toDouble(),
            arrivedAverageSpeedMph = prefs.getFloat(PREF_TRIP_ARRIVED_SPEED, 0.0f).toDouble()
        )
    }

    private fun saveActiveTrip(trip: ActiveTrip) {
        getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_TRIP_ACTIVE, trip.active)
            .putFloat(PREF_TRIP_START_ODO, trip.startOdometer.toFloat())
            .putFloat(PREF_TRIP_PLANNED_MILES, trip.plannedMiles.toFloat())
            .putLong(PREF_TRIP_START_TIME, trip.startTimeMs)
            .putFloat(PREF_TRIP_FUEL_USED, trip.fuelUsedGallons.toFloat())
            .putFloat(PREF_TRIP_DISTANCE_OFFSET, trip.distanceOffsetMiles.toFloat())
            .putLong(PREF_TRIP_LAST_DISTANCE_UPDATE, trip.lastDistanceUpdateMs)
            .putLong(PREF_TRIP_MOVING_TIME, trip.movingTimeMs)
            .putBoolean(PREF_TRIP_ARRIVED, trip.arrived)
            .putLong(PREF_TRIP_ARRIVED_TIME, trip.arrivedTimeMs)
            .putFloat(PREF_TRIP_ARRIVED_FUEL, trip.arrivedFuelUsedGallons.toFloat())
            .putFloat(PREF_TRIP_ARRIVED_MPG, trip.arrivedAverageMpg.toFloat())
            .putFloat(PREF_TRIP_ARRIVED_SPEED, trip.arrivedAverageSpeedMph.toFloat())
            .apply()
    }

    private fun maybeRecordHistory(nowMs: Long) {
        if (nowMs - lastHistorySampleMs < HISTORY_SAMPLE_MS) return
        val state = bleState
        val telemetry = state.telemetry
        val fuelUseGph = effectiveFuelGph(telemetry, appSettings)
        val fullLiveMode = state.connected &&
            !telemetry.simulatedTelemetryEnabled &&
            !locationState.speedSimulated
        val historySpeedMph = if (locationState.speedFromCurrentFix && locationState.speedMph.isFinite()) locationState.speedMph else MissingHistoryValue
        val instantMpg = if (historySpeedMph.isHistoryValid()) {
            calculateInstantMpgForLiveFuel(fuelUseGph, historySpeedMph).coerceAtMost(HistoryMpgDisplayCap)
        } else {
            MissingHistoryValue
        }
        if (!fullLiveMode) return

        lastHistorySampleMs = nowMs
        val point = FuelHistoryPoint(
            timestampMs = nowMs,
            dayKey = nowMs.toDayKey(),
            speedMph = historySpeedMph,
            rpm = telemetry.rpm,
            instantMpg = instantMpg,
            gallonsPerHour = fuelUseGph,
            injectorPulseWidthUs = telemetry.widthUs,
            dutyPercent = telemetry.duty * 100.0,
            altitudeFt = if (locationState.altitudeFromCurrentFix && locationState.altitudeFt.isFinite()) locationState.altitudeFt else MissingHistoryValue,
            tankGallons = (appSettings.tankCapacityGallons - fuelUsedSinceFullGallons).coerceIn(0.0, appSettings.tankCapacityGallons),
            espSimulated = telemetry.simulatedTelemetryEnabled,
            phoneSpeedSimulated = locationState.speedSimulated
        )
        appendHistoryPoint(point)
        historyPoints = (historyPoints + point).prunedHistory(nowMs)
        if (nowMs - lastHistoryPruneMs > HISTORY_PRUNE_MS) {
            lastHistoryPruneMs = nowMs
            saveHistoryPoints(historyPoints)
        }
    }

    private fun loadHistoryPoints(): List<FuelHistoryPoint> {
        val file = File(filesDir, HISTORY_FILE)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .mapNotNull { FuelHistoryPoint.fromCsvLine(it) }
            .prunedHistory(System.currentTimeMillis())
    }

    private fun appendHistoryPoint(point: FuelHistoryPoint) {
        File(filesDir, HISTORY_FILE).appendText(point.toCsvLine() + "\n")
    }

    private fun saveHistoryPoints(points: List<FuelHistoryPoint>) {
        File(filesDir, HISTORY_FILE).writeText(points.joinToString("\n") { it.toCsvLine() } + if (points.isNotEmpty()) "\n" else "")
    }

    companion object {
        private const val PREF_APP_SETTINGS = "app_settings"
        private const val PREF_TANK_GALLONS = "tank_gallons"
        private const val PREF_FUEL_CAL_MULT = "fuel_cal_mult"
        private const val PREF_INJECTOR_FLOW = "injector_flow"
        private const val PREF_INJECTOR_COUNT = "injector_count"
        private const val PREF_ALT_UNIT = "altitude_unit"
        private const val PREF_GRADE_UNIT = "grade_unit"
        private const val PREF_CAL_START_ODO = "cal_start_odo"
        private const val PREF_PHONE_SPEED_SIM = "phone_speed_sim"
        private const val PREF_DASH_AVG_HOURS = "dash_avg_hours"
        private const val PREF_DISTANCE_SCALE = "distance_scale"
        private const val PREF_INSTANT_MPG_GOOD = "instant_mpg_good"
        private const val PREF_KEEP_SCREEN_ON = "keep_screen_on"
        private const val PREF_FUEL_USED_SINCE_FULL = "fuel_used_since_full"
        private const val PREF_CAL_ESTIMATED_GALLONS = "cal_estimated_gallons"
        private const val PREF_VIRTUAL_ODOMETER = "virtual_odometer"
        private const val PREF_FILLUP_RECORDS = "fillup_records"
        private const val PREF_TRIP_ACTIVE = "trip_active"
        private const val PREF_TRIP_START_ODO = "trip_start_odo"
        private const val PREF_TRIP_PLANNED_MILES = "trip_planned_miles"
        private const val PREF_TRIP_START_TIME = "trip_start_time"
        private const val PREF_TRIP_FUEL_USED = "trip_fuel_used"
        private const val PREF_TRIP_DISTANCE_OFFSET = "trip_distance_offset"
        private const val PREF_TRIP_LAST_DISTANCE_UPDATE = "trip_last_distance_update"
        private const val PREF_TRIP_MOVING_TIME = "trip_moving_time"
        private const val PREF_TRIP_ARRIVED = "trip_arrived"
        private const val PREF_TRIP_ARRIVED_TIME = "trip_arrived_time"
        private const val PREF_TRIP_ARRIVED_FUEL = "trip_arrived_fuel"
        private const val PREF_TRIP_ARRIVED_MPG = "trip_arrived_mpg"
        private const val PREF_TRIP_ARRIVED_SPEED = "trip_arrived_speed"
        private const val MAX_FILLUP_RECORDS = 50
        private const val HISTORY_FILE = "fuel_history.csv"
        private const val HISTORY_SAMPLE_MS = 30_000L
        private const val HISTORY_PRUNE_MS = 10 * 60_000L
        private const val HISTORY_KEEP_DAYS = 30L
    }
}

