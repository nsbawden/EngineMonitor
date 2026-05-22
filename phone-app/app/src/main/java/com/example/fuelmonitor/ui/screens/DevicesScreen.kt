package com.example.fuelmonitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fuelmonitor.FuelBleState
import com.example.fuelmonitor.LocationState
import com.example.fuelmonitor.WifiCredentialPhase
import com.example.fuelmonitor.WifiJoinConfirmTimeoutMs
import com.example.fuelmonitor.WifiServiceConfirmTimeoutMs
import com.example.fuelmonitor.ui.components.DeviceSwitchRow
import com.example.fuelmonitor.ui.components.StatusCard
import com.example.fuelmonitor.ui.theme.BatteryGreen
import com.example.fuelmonitor.ui.theme.Panel
import com.example.fuelmonitor.ui.theme.TextMuted
import kotlinx.coroutines.delay

@Composable
internal fun DevicesScreen(
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
internal fun OtaWifiCard(
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
