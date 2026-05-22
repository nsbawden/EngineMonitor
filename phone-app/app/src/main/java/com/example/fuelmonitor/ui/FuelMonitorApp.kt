package com.example.fuelmonitor.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import com.example.fuelmonitor.ActiveTrip
import com.example.fuelmonitor.AppSettings
import com.example.fuelmonitor.FillupRecord
import com.example.fuelmonitor.FuelBleState
import com.example.fuelmonitor.FuelHistoryPoint
import com.example.fuelmonitor.GradeState
import com.example.fuelmonitor.LocationState
import com.example.fuelmonitor.ui.screens.DashboardScreen
import com.example.fuelmonitor.ui.screens.DevicesScreen
import com.example.fuelmonitor.ui.screens.FillupsScreen
import com.example.fuelmonitor.ui.screens.HistoryScreen
import com.example.fuelmonitor.ui.screens.SettingsScreen
import com.example.fuelmonitor.ui.screens.TripScreen

@Composable
fun FuelMonitorApp(
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
