package com.example.fuelmonitor

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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin


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
        requestPermissions.launch((fuelBleClient.requiredPermissions() + locationTracker.requiredPermissions()).distinct().toTypedArray())
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
        val permissions = (fuelBleClient.requiredPermissions() + locationTracker.requiredPermissions()).distinct()
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

private enum class AppTab(val label: String, val iconRes: Int) {
    Dashboard("Dash", R.drawable.ic_tab_dashboard),
    Trip("Trip", R.drawable.ic_tab_trip),
    Fillups("Fuel", R.drawable.ic_tab_fillups),
    History("History", R.drawable.ic_tab_history),
    Devices("Devices", R.drawable.ic_tab_bluetooth),
    Settings("Settings", R.drawable.ic_tab_settings)
}

@Composable
private fun FuelMonitorApp(
    bleState: FuelBleState,
    locationState: LocationState,
    appSettings: AppSettings,
    fuelUsedSinceFullGallons: Double,
    calibrationTripEstimatedGallons: Double,
    virtualOdometer: Double,
    gradeState: GradeState,
    fillupRecords: List<FillupRecord>,
    historyPoints: List<FuelHistoryPoint>,
    rollingAverageMpg: Double?,
    activeTrip: ActiveTrip,
    permissionStatusMessage: String,
    onRequestPermissions: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onSetEspSimulation: (Boolean) -> Unit,
    onSetEspWifiEnabled: (Boolean, (Boolean) -> Unit) -> Unit,
    onConfigureEspWifi: (String, String, (Boolean, String) -> Unit) -> Unit,
    onSetPhoneSpeedSimulation: (Boolean) -> Unit,
    onPrepareVehicleTest: () -> Unit,
    onSettingsChanged: (AppSettings) -> Unit,
    onResetFullTank: () -> Unit,
    onSetFuelGaugeFraction: (Double) -> Unit,
    onStartTrip: (Double, Double, Boolean) -> Unit,
    onStopTrip: () -> Unit,
    onResetTrip: () -> Unit,
    onApplyRemainingMiles: (Double) -> Unit,
    onApplyCurrentOdometer: (Double) -> Unit,
    onApplyDashboardOdometer: (Double, Boolean) -> Unit,
    onStartCalibrationTrip: (Double) -> Unit,
    onFinishCalibrationTrip: (Double, Double) -> Unit,
    onApplyDirectCalibration: (Double) -> Unit,
    onAddFillup: (FillupRecord) -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Dashboard) }
    val dashboardScroll = rememberScrollState()
    val tripScroll = rememberScrollState()
    val fillupsScroll = rememberScrollState()
    val historyScroll = rememberScrollState()
    val devicesScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(painterResource(tab.iconRes), contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            AppTab.Dashboard -> DashboardScreen(
                bleState = bleState,
                location = locationState,
                settings = appSettings,
                fuelUsedSinceFullGallons = fuelUsedSinceFullGallons,
                virtualOdometer = virtualOdometer,
                gradeState = gradeState,
                historyPoints = historyPoints,
                rollingAverageMpg = rollingAverageMpg,
                activeTrip = activeTrip,
                onOpenTrip = { selectedTab = AppTab.Trip },
                onApplyDashboardOdometer = onApplyDashboardOdometer,
                scrollState = dashboardScroll,
                modifier = Modifier.padding(padding)
            )
            AppTab.Trip -> TripScreen(
                bleState = bleState,
                location = locationState,
                settings = appSettings,
                activeTrip = activeTrip,
                virtualOdometer = virtualOdometer,
                fuelUsedSinceFullGallons = fuelUsedSinceFullGallons,
                calibrationTripEstimatedGallons = calibrationTripEstimatedGallons,
                onStartTrip = onStartTrip,
                onStopTrip = onStopTrip,
                onResetTrip = onResetTrip,
                onApplyRemainingMiles = onApplyRemainingMiles,
                onApplyCurrentOdometer = onApplyCurrentOdometer,
                scrollState = tripScroll,
                modifier = Modifier.padding(padding)
            )
            AppTab.Fillups -> FillupsScreen(
                location = locationState,
                settings = appSettings,
                activeTrip = activeTrip,
                fuelUsedSinceFullGallons = fuelUsedSinceFullGallons,
                virtualOdometer = virtualOdometer,
                fillupRecords = fillupRecords,
                onAddFillup = onAddFillup,
                scrollState = fillupsScroll,
                modifier = Modifier.padding(padding)
            )
            AppTab.History -> HistoryScreen(historyPoints = historyPoints, scrollState = historyScroll, modifier = Modifier.padding(padding))
            AppTab.Devices -> DevicesScreen(
                bleState = bleState,
                location = locationState,
                phoneSpeedSimulationEnabled = appSettings.phoneSpeedSimulationEnabled,
                permissionStatusMessage = permissionStatusMessage,
                onRequestPermissions = onRequestPermissions,
                onScan = onScan,
                onDisconnect = onDisconnect,
                onSetEspSimulation = onSetEspSimulation,
                onSetEspWifiEnabled = onSetEspWifiEnabled,
                onConfigureEspWifi = onConfigureEspWifi,
                onSetPhoneSpeedSimulation = onSetPhoneSpeedSimulation,
                onPrepareVehicleTest = onPrepareVehicleTest,
                scrollState = devicesScroll,
                modifier = Modifier.padding(padding)
            )
            AppTab.Settings -> SettingsScreen(
                bleState = bleState,
                location = locationState,
                settings = appSettings,
                fuelUsedSinceFullGallons = fuelUsedSinceFullGallons,
                calibrationTripEstimatedGallons = calibrationTripEstimatedGallons,
                onSettingsChanged = onSettingsChanged,
                onResetFullTank = onResetFullTank,
                onSetFuelGaugeFraction = onSetFuelGaugeFraction,
                onStartCalibrationTrip = onStartCalibrationTrip,
                onFinishCalibrationTrip = onFinishCalibrationTrip,
                onApplyDirectCalibration = onApplyDirectCalibration,
                scrollState = settingsScroll,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun DashboardScreen(
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
private fun StatusCard(bleState: FuelBleState, location: LocationState) {
    val telemetry = bleState.telemetry
    val injectorSignalPresent = bleState.connected && (telemetry.signalActive || telemetry.pulsesPerSecond > 0.1 || telemetry.widthUs > 0.0)
    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatusChip(
                    if (bleState.connected) "ESP live" else if (bleState.scanning) "Scanning" else "ESP offline",
                    if (bleState.connected) BatteryGreen else if (bleState.scanning) VoltageAmber else WarningOrange,
                    Modifier.weight(1f)
                )
                StatusChip(
                    when {
                        !bleState.connected -> "Pulse unknown"
                        telemetry.simulatedTelemetryEnabled -> "ESP sim"
                        injectorSignalPresent -> "Injector live"
                        else -> "No fuel pulse"
                    },
                    when {
                        !bleState.connected -> TextMuted
                        telemetry.simulatedTelemetryEnabled -> VoltageAmber
                        injectorSignalPresent -> BatteryGreen
                        else -> TextMuted
                    },
                    Modifier.weight(1f)
                )
            }
            Text(bleState.status, color = TextMuted, fontSize = 13.sp)
            Text(location.status, color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TripSummaryCard(
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
private fun DashboardTripCard(
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
private fun DashboardTripGraph(activeTrip: ActiveTrip, historyPoints: List<FuelHistoryPoint>) {
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
private fun TripHistoryChart(points: List<FuelHistoryPoint>, tripStartMs: Long) {
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
private fun GaugeCard(
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
private fun DashboardFuelTile(gallons: Double, tankCapacityGallons: Double, modifier: Modifier = Modifier) {
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
private fun GradeTile(gradeState: GradeState, gradeUnit: GradeUnit, modifier: Modifier = Modifier) {
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
private fun OdometerTile(
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
private fun HalfGauge(
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
private fun OdometerQuickSyncDialog(
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
private fun FuelBarGauge(
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

@Composable
private fun TripScreen(
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

@Composable
private fun FillupsScreen(
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
private fun FillupRow(record: FillupRecord) {
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
private fun FuelLevelEstimateTile(gallons: Double, tankCapacityGallons: Double, modifier: Modifier = Modifier) {
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

private enum class HistoryAxis(val label: String, val unit: String) {
    Mpg("MPG", "MPG"),
    Altitude("Altitude", "ft"),
    Tank("Tank", "gal")
}

@Composable
private fun HistoryScreen(
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
private fun HistoryAxisButton(axis: HistoryAxis, selected: Boolean, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
private fun CombinedHistoryChart(points: List<FuelHistoryPoint>, dayKey: Int, axis: HistoryAxis) {
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

@Composable
private fun DevicesScreen(
    bleState: FuelBleState,
    location: LocationState,
    phoneSpeedSimulationEnabled: Boolean,
    permissionStatusMessage: String,
    onRequestPermissions: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onSetEspSimulation: (Boolean) -> Unit,
    onSetEspWifiEnabled: (Boolean, (Boolean) -> Unit) -> Unit,
    onConfigureEspWifi: (String, String, (Boolean, String) -> Unit) -> Unit,
    onSetPhoneSpeedSimulation: (Boolean) -> Unit,
    onPrepareVehicleTest: () -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Devices", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        StatusCard(bleState, location)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ESP32 BLE", fontWeight = FontWeight.SemiBold)
                Text("Bluetooth: ${if (bleState.bluetoothEnabled) "on" else "off"}", color = TextMuted)
                Text("Bluetooth permissions: ${if (bleState.hasPermissions) "ready" else "needed"}", color = TextMuted)
                Text("Location permission: ${if (location.hasPermission) "ready" else "needed"}", color = TextMuted)
                Text("Status: ${bleState.status}", color = TextMuted)
                if (permissionStatusMessage.isNotBlank()) {
                    Text(permissionStatusMessage, color = BatteryGreen, fontSize = 13.sp)
                }
                if (bleState.connected) {
                    DeviceSwitchRow(
                        title = "ESP injector source",
                        detail = if (bleState.controlReady) {
                            if (bleState.telemetry.simulatedTelemetryEnabled) "Simulated injector pulses" else "Live protected injector input"
                        } else {
                            "Control characteristic not found"
                        },
                        checked = bleState.telemetry.simulatedTelemetryEnabled,
                        enabled = bleState.controlReady,
                        onCheckedChange = onSetEspSimulation
                    )
                    DeviceSwitchRow(
                        title = "Phone speed source",
                        detail = if (phoneSpeedSimulationEnabled) "Simulated 55 MPH" else "Live GPS speed",
                        checked = phoneSpeedSimulationEnabled,
                        enabled = true,
                        onCheckedChange = onSetPhoneSpeedSimulation
                    )
                }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onPrepareVehicleTest()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Prepare for vehicle test")
                }
                Text(
                    "Disables phone speed simulation and sends sim=0 to the ESP when BLE control is ready.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { focusManager.clearFocus(); onRequestPermissions() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (bleState.hasPermissions && location.hasPermission) "Check Permissions" else "Grant Permissions")
                    }
                    Button(onClick = { focusManager.clearFocus(); onScan() }, enabled = !bleState.scanning, modifier = Modifier.fillMaxWidth()) {
                        Text(if (bleState.scanning) "Scanning..." else "Scan and Connect")
                    }
                    Button(
                        onClick = { focusManager.clearFocus(); onDisconnect() },
                        enabled = bleState.connected || bleState.connecting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }
        OtaWifiCard(
            bleState = bleState,
            wifiSsid = wifiSsid,
            wifiPassword = wifiPassword,
            onWifiSsidChange = { wifiSsid = it },
            onWifiPasswordChange = { wifiPassword = it },
            onSetEspWifiEnabled = onSetEspWifiEnabled,
            onConfigureEspWifi = onConfigureEspWifi,
            onClearFocus = { focusManager.clearFocus() }
        )
    }
}

@Composable
private fun OtaWifiCard(
    bleState: FuelBleState,
    wifiSsid: String,
    wifiPassword: String,
    onWifiSsidChange: (String) -> Unit,
    onWifiPasswordChange: (String) -> Unit,
    onSetEspWifiEnabled: (Boolean, (Boolean) -> Unit) -> Unit,
    onConfigureEspWifi: (String, String, (Boolean, String) -> Unit) -> Unit,
    onClearFocus: () -> Unit
) {
    var desiredWifiOn by remember { mutableStateOf<Boolean?>(null) }
    var wifiServicePending by remember { mutableStateOf(false) }
    var wifiServiceError by remember { mutableStateOf<String?>(null) }
    var credentialPhase by remember { mutableStateOf(WifiCredentialPhase.Idle) }
    var lastSentSsid by remember { mutableStateOf("") }
    var joinWatchStartedMs by remember { mutableStateOf(0L) }

    val telemetryWifiOn = bleState.telemetry.wifiServiceEnabled
    val displayWifiOn = desiredWifiOn ?: telemetryWifiOn

    LaunchedEffect(telemetryWifiOn, desiredWifiOn) {
        if (desiredWifiOn != null && telemetryWifiOn == desiredWifiOn) {
            desiredWifiOn = null
            wifiServicePending = false
            wifiServiceError = null
        }
    }

    LaunchedEffect(wifiServicePending, desiredWifiOn) {
        if (!wifiServicePending) return@LaunchedEffect
        delay(WifiServiceConfirmTimeoutMs)
        if (wifiServicePending && desiredWifiOn != null && telemetryWifiOn != desiredWifiOn) {
            wifiServicePending = false
            wifiServiceError = "Wi-Fi service did not confirm on the ESP. Try the toggle again."
            desiredWifiOn = null
        }
    }

    LaunchedEffect(bleState.telemetry.wifiConnected, credentialPhase) {
        if (credentialPhase == WifiCredentialPhase.WaitingJoin &&
            bleState.telemetry.wifiConnected &&
            bleState.telemetry.wifiServiceEnabled
        ) {
            credentialPhase = WifiCredentialPhase.Joined
        }
    }

    LaunchedEffect(credentialPhase, joinWatchStartedMs) {
        if (credentialPhase != WifiCredentialPhase.WaitingJoin || joinWatchStartedMs <= 0L) return@LaunchedEffect
        delay(WifiJoinConfirmTimeoutMs)
        if (credentialPhase == WifiCredentialPhase.WaitingJoin) {
            credentialPhase = WifiCredentialPhase.TimedOut
        }
    }

    LaunchedEffect(bleState.connected) {
        if (!bleState.connected) {
            desiredWifiOn = null
            wifiServicePending = false
            wifiServiceError = null
            credentialPhase = WifiCredentialPhase.Idle
            lastSentSsid = ""
            joinWatchStartedMs = 0L
        }
    }

    val joinStatusText = when {
        !bleState.connected -> "Connect over BLE to configure OTA Wi-Fi."
        credentialPhase == WifiCredentialPhase.Sending -> "Sending credentials to ESP…"
        credentialPhase == WifiCredentialPhase.Failed -> bleState.wifiControlStatus.ifBlank { "Could not send credentials." }
        credentialPhase == WifiCredentialPhase.TimedOut && lastSentSsid.isNotBlank() -> {
            val espSsid = bleState.telemetry.wifiAttemptSsid
            if (espSsid.isNotBlank() && !espSsid.equals(lastSentSsid, ignoreCase = true)) {
                "No join yet. ESP is trying \"$espSsid\" but you sent \"$lastSentSsid\". Check SSID spelling."
            } else {
                "No join yet for \"$lastSentSsid\". Check SSID spelling and password, then send again."
            }
        }
        credentialPhase == WifiCredentialPhase.Joined || bleState.telemetry.wifiConnected -> {
            val joined = bleState.telemetry.wifiAttemptSsid.ifBlank { lastSentSsid }
            if (joined.isNotBlank()) {
                "Joined \"$joined\". OTA IP: ${bleState.telemetry.otaIp}"
            } else {
                "Joined Wi-Fi. OTA IP: ${bleState.telemetry.otaIp}"
            }
        }
        credentialPhase == WifiCredentialPhase.WaitingJoin && lastSentSsid.isNotBlank() -> {
            val espSsid = bleState.telemetry.wifiAttemptSsid
            if (espSsid.isNotBlank()) {
                "ESP trying \"$espSsid\"… (may take up to ${WifiJoinConfirmTimeoutMs / 1000}s)"
            } else {
                "Trying to join \"$lastSentSsid\"… (may take up to ${WifiJoinConfirmTimeoutMs / 1000}s)"
            }
        }
        bleState.telemetry.wifiServiceEnabled && lastSentSsid.isNotBlank() && !bleState.telemetry.wifiConnected -> {
            val espSsid = bleState.telemetry.wifiAttemptSsid
            if (espSsid.isNotBlank()) {
                "Wi-Fi on; ESP trying \"$espSsid\"."
            } else {
                "Wi-Fi on; still waiting to join \"$lastSentSsid\"."
            }
        }
        bleState.telemetry.wifiServiceEnabled && bleState.telemetry.wifiAttemptSsid.isNotBlank() && bleState.telemetry.wifiConnected ->
            "Connected to \"${bleState.telemetry.wifiAttemptSsid}\" at ${bleState.telemetry.otaIp}"
        bleState.telemetry.wifiServiceEnabled ->
            "Wi-Fi service on. Enter SSID and password, then send once."
        else -> "Turn on Wi-Fi service before sending credentials."
    }

    val joinStatusColor = when {
        credentialPhase == WifiCredentialPhase.Joined || bleState.telemetry.wifiConnected -> BatteryGreen
        credentialPhase == WifiCredentialPhase.TimedOut ||
            credentialPhase == WifiCredentialPhase.Failed ||
            wifiServiceError != null -> Color(0xFFFF8A65)
        else -> TextMuted
    }

    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("OTA Wi-Fi", fontWeight = FontWeight.SemiBold)
            Text(
                "1) Turn on Wi-Fi service and wait for confirmation.\n" +
                    "2) Enter the network name exactly as your router shows it.\n" +
                    "3) Tap Send once and watch the status line below.",
                color = TextMuted,
                fontSize = 13.sp
            )
            if (bleState.connected) {
                DeviceSwitchRow(
                    title = "ESP Wi-Fi service",
                    detail = when {
                        wifiServicePending && desiredWifiOn == true -> "Starting on ESP (scan/connect can take 10–20 s)…"
                        wifiServicePending && desiredWifiOn == false -> "Stopping on ESP…"
                        bleState.telemetry.wifiConnected -> {
                            val ssid = bleState.telemetry.wifiAttemptSsid
                            if (ssid.isNotBlank()) "Connected to \"$ssid\" at ${bleState.telemetry.otaIp}"
                            else "Connected at ${bleState.telemetry.otaIp}"
                        }
                        bleState.telemetry.wifiServiceEnabled && bleState.telemetry.wifiAttemptSsid.isNotBlank() ->
                            "Trying \"${bleState.telemetry.wifiAttemptSsid}\""
                        bleState.telemetry.wifiServiceEnabled -> "On — send credentials below when ready"
                        else -> "Off until you need a firmware update"
                    },
                    checked = displayWifiOn,
                    enabled = bleState.controlReady && !wifiServicePending,
                    pending = wifiServicePending,
                    onCheckedChange = { enabled ->
                        desiredWifiOn = enabled
                        wifiServicePending = true
                        wifiServiceError = null
                        onSetEspWifiEnabled(enabled) { accepted ->
                            if (!accepted) {
                                wifiServicePending = false
                                desiredWifiOn = null
                                wifiServiceError = "Could not reach ESP to change Wi-Fi service."
                            }
                        }
                    }
                )
                if (wifiServiceError != null) {
                    Text(wifiServiceError!!, color = Color(0xFFFF8A65), fontSize = 13.sp)
                }
                if (bleState.wifiControlStatus.isNotBlank()) {
                    Text(bleState.wifiControlStatus, color = TextMuted, fontSize = 13.sp)
                }
                Text(joinStatusText, color = joinStatusColor, fontSize = 13.sp)
                OutlinedTextField(
                    value = wifiSsid,
                    onValueChange = {
                        onWifiSsidChange(it)
                        if (credentialPhase == WifiCredentialPhase.TimedOut || credentialPhase == WifiCredentialPhase.Failed) {
                            credentialPhase = WifiCredentialPhase.Idle
                        }
                    },
                    label = { Text("Wi-Fi SSID (exact name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = wifiPassword,
                    onValueChange = onWifiPasswordChange,
                    label = { Text("Wi-Fi password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val sendEnabled = bleState.controlReady &&
                    wifiSsid.isNotBlank() &&
                    credentialPhase != WifiCredentialPhase.Sending &&
                    telemetryWifiOn
                Button(
                    onClick = {
                        onClearFocus()
                        val ssid = wifiSsid.trim()
                        lastSentSsid = ssid
                        joinWatchStartedMs = System.currentTimeMillis()
                        credentialPhase = WifiCredentialPhase.Sending
                        onConfigureEspWifi(ssid, wifiPassword) { accepted, _ ->
                            credentialPhase = if (accepted) {
                                WifiCredentialPhase.WaitingJoin
                            } else {
                                WifiCredentialPhase.Failed
                            }
                        }
                    },
                    enabled = sendEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (credentialPhase == WifiCredentialPhase.Sending) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Text("Sending…")
                        }
                    } else {
                        Text(
                            when (credentialPhase) {
                                WifiCredentialPhase.WaitingJoin -> "Credentials sent — waiting for join"
                                WifiCredentialPhase.Joined -> "Joined — send again to change network"
                                WifiCredentialPhase.TimedOut, WifiCredentialPhase.Failed -> "Send again"
                                else -> "Send Wi-Fi to ESP"
                            }
                        )
                    }
                }
                if (!telemetryWifiOn) {
                    Text("Turn on ESP Wi-Fi service before sending credentials.", color = TextMuted, fontSize = 13.sp)
                }
            } else {
                Text(joinStatusText, color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DeviceSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    pending: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextMuted, fontSize = 13.sp)
        }
        if (pending) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun SettingsScreen(
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
                        "Screen stays awake while monitoring."
                    } else {
                        "Phone may sleep normally."
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

@Composable
private fun SettingsNumberField(
    label: String,
    value: String,
    suffix: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun PlaceholderScreen(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(body, color = TextMuted, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun ValueTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = modifier.height(72.dp)) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(6.dp)
        ) {
            Text(label, color = TextMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
            Text(value, color = color, fontSize = value.tileFontSize(), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun SpeedSignTile(value: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.height(72.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(5.dp)
                .background(Color.White, RoundedCornerShape(6.dp))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.dp.toPx()
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    style = Stroke(width = stroke)
                )
            }
            Text(
                text = value,
                color = Color.Black,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = modifier.height(54.dp)) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp)
        ) {
            Text(label, color = TextMuted, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 1)
            Text(value, color = color, fontSize = value.compactMetricFontSize(), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp), modifier = modifier.height(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = color, fontSize = text.chipFontSize(), fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun FuelMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PowerBlue,
            secondary = BatteryGreen,
            tertiary = VoltageAmber,
            background = AppBackground,
            surface = Panel,
            surfaceVariant = PanelAlt,
            onPrimary = Color.White,
            onSecondary = Color.Black,
            onTertiary = Color.Black,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            onSurfaceVariant = TextMuted
        ),
        content = content
    )
}

















private fun List<FillupRecord>.toSheetsTsv(): String {
    val header = "date\todometer\tgallons_added\tprice_per_gallon\ttotal_cost\tfilled_to_full\tmpg_since_last_fillup"
    val rows = sortedBy { it.timestampMs }.map { record ->
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(record.timestampMs))
        listOf(
            date,
            "%.1f".format(record.odometer),
            "%.3f".format(record.gallonsAdded),
            "%.3f".format(record.pricePerGallon),
            "%.2f".format(record.totalCost),
            if (record.filledToFull) "yes" else "no",
            record.mpgSinceLastFillup.takeIf { it > 0.0 }?.let { "%.2f".format(it) } ?: ""
        ).joinToString("\t")
    }
    return (listOf(header) + rows).joinToString("\n")
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistorySeries(
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

private fun FuelHistoryPoint.valueFor(axis: HistoryAxis): Double? {
    return when (axis) {
        HistoryAxis.Mpg -> displayMpg()
        HistoryAxis.Altitude -> altitudeFt.takeIfHistoryValid()
        HistoryAxis.Tank -> tankGallons.takeIfHistoryValid()
    }
}






private fun List<FuelHistoryPoint>.toHistoryTsv(): String {
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


private fun Long.toDayKey(): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = this
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH) + 1
    val day = calendar.get(Calendar.DAY_OF_MONTH)
    return year * 10_000 + month * 100 + day
}

private fun Int.toDayStartMs(): Long {
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

private fun Int.toDayLabel(): String {
    return SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(Date(toDayStartMs()))
}

private fun Long.formatTimeLabel(): String {
    return SimpleDateFormat("h:mm a", Locale.US).format(Date(this))
}

private fun Double.formatAxis(unit: String): String {
    return when (unit) {
        "ft" -> "%.0f".format(this)
        "gal" -> "%.1f".format(this)
        else -> "%.1f".format(this)
    }
}


private fun String.tileFontSize() = when {
    length >= 14 -> 15.sp
    length >= 12 -> 16.sp
    length >= 10 -> 18.sp
    length >= 8 -> 20.sp
    length >= 7 -> 21.sp
    length >= 6 -> 22.sp
    else -> 24.sp
}

private fun String.compactMetricFontSize() = when {
    length >= 11 -> 13.sp
    length >= 9 -> 14.sp
    length >= 7 -> 15.sp
    else -> 16.sp
}

private fun String.chipFontSize() = when {
    length >= 15 -> 15.sp
    length >= 12 -> 16.sp
    length >= 9 -> 17.sp
    else -> 18.sp
}

private val AppBackground = Color(0xFF0D1622)
private val Panel = Color(0xFF172232)
private val PanelAlt = Color(0xFF202D3F)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFFCBD5E1)
private val VoltageAmber = Color(0xFFFFC107)
private val CurrentRose = Color(0xFFFF8A3D)
private val PowerBlue = Color(0xFF1997FB)
private val BatteryGreen = Color(0xFF35D07F)
private val WarningOrange = Color(0xFFFF8A3D)
