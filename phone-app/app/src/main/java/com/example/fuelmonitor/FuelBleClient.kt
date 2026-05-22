package com.example.fuelmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.util.Locale

class FuelBleClient(
    context: Context,
    private val onState: (FuelBleState) -> Unit
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val prefs: SharedPreferences = appContext.getSharedPreferences("fuel_ble", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var scanner = bluetoothManager?.adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var rememberedAddress: String? = prefs.getString(PREF_DEVICE_ADDRESS, null)
    private var rememberedName: String? = prefs.getString(PREF_DEVICE_NAME, null)
    private var scanning = false
    private var autoScan = false
    private var connecting = false
    private var connected = false
    private var userDisconnectRequested = false
    private var autoReconnectRetriesRemaining = 0
    private var autoReconnectAttempt = 0
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var status = "Ready"
    private var wifiControlStatus = ""
    private var telemetry = InjectorTelemetry()

    private val scanTimeout = Runnable {
        val wasAutoScan = autoScan
        stopScan("Scan timed out")
        if (wasAutoScan) scheduleAutoReconnect("ESP not found")
    }

    private val reconnectRunnable = Runnable {
        if (!userDisconnectRequested && rememberedAddress != null && !connected && !connecting && !scanning) {
            startScan(auto = true)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = readName(result)
            val address = readAddress(device)
            val matchesRemembered = rememberedAddress != null && address == rememberedAddress
            val matchesFuelMonitor = name == DeviceName || result.scanRecord?.serviceUuids?.any { it.uuid == ServiceUuid } == true
            if (matchesRemembered || matchesFuelMonitor) {
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val wasAutoScan = autoScan
            scanning = false
            autoScan = false
            publish("Scan failed: $errorCode")
            if (wasAutoScan) scheduleAutoReconnect("Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        scanning = false
                        autoScan = false
                        connecting = false
                        connected = true
                        autoReconnectRetriesRemaining = 0
                        autoReconnectAttempt = 0
                        publish("Connected; requesting MTU")
                        if (hasConnectPermission() && !gatt.requestMtu(185)) {
                            gatt.discoverServices()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val shouldReconnect = !userDisconnectRequested && rememberedAddress != null
                        connecting = false
                        connected = false
                        publish(if (status == BluetoothGatt.GATT_SUCCESS) "Disconnected" else "Disconnected: GATT $status")
                        closeGatt()
                        if (shouldReconnect) scheduleAutoReconnect("Disconnected")
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                publish("MTU $mtu; discovering services")
                if (hasConnectPermission()) gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                val service = gatt.getService(ServiceUuid)
                val characteristic = service?.getCharacteristic(TelemetryUuid)
                controlCharacteristic = service?.getCharacteristic(ControlUuid)
                if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                    publish("Telemetry service not found")
                    connected = false
                    connecting = false
                    closeGatt()
                    scheduleAutoReconnect("Telemetry service not found")
                    return@post
                }
                enableNotifications(gatt, characteristic)
                if (hasConnectPermission()) gatt.readCharacteristic(characteristic)
                publish(if (controlCharacteristic != null) "Receiving telemetry; controls ready" else "Receiving telemetry")
            }
        }

        @Deprecated("Older Android callback")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handleBytes(characteristic.value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleBytes(value)
        }

        @Deprecated("Older Android callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleBytes(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleBytes(value)
        }
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasPermissions(): Boolean = hasScanPermission() && hasConnectPermission()

    fun refresh() {
        scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        publish(status)
        if (hasScanPermission() && hasConnectPermission() && rememberedAddress != null && !connected && !connecting && !scanning) {
            autoReconnectRetriesRemaining = AUTO_RECONNECT_RETRIES
            autoReconnectAttempt = 0
            userDisconnectRequested = false
            startScan(auto = true)
        }
    }

    fun startScan() {
        userDisconnectRequested = false
        autoReconnectRetriesRemaining = AUTO_RECONNECT_RETRIES
        autoReconnectAttempt = 0
        startScan(auto = false)
    }

    @SuppressLint("MissingPermission")
    private fun startScan(auto: Boolean) {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            publish("Bluetooth LE not supported")
            return
        }
        if (!adapter.isEnabled) {
            publish("Bluetooth is off")
            return
        }
        if (!hasScanPermission()) {
            publish("Bluetooth permission needed")
            return
        }
        val scanner = scanner ?: run {
            publish("BLE scanner unavailable")
            return
        }

        closeGatt()
        scanning = true
        autoScan = auto
        connecting = false
        connected = false
        publish(if (auto && rememberedName != null) "Auto-connecting to $rememberedName" else "Scanning for $DeviceName")
        val filter = if (auto && rememberedAddress != null) null else ScanFilter.Builder().setServiceUuid(ParcelUuid(ServiceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filter?.let { listOf(it) }, settings, scanCallback)
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, 20_000L)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            publish("Bluetooth connect permission needed")
            return
        }
        stopScan("Connecting")
        connecting = true
        val name = readDeviceName(device).ifBlank { DeviceName }
        val address = readAddress(device)
        rememberedAddress = address
        rememberedName = name
        prefs.edit()
            .putString(PREF_DEVICE_ADDRESS, address)
            .putString(PREF_DEVICE_NAME, name)
            .apply()
        publish("Connecting to $name")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userDisconnectRequested = true
        autoReconnectRetriesRemaining = 0
        autoReconnectAttempt = 0
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(scanTimeout)
        stopScan("Disconnected")
        if (hasConnectPermission()) gatt?.disconnect()
        closeGatt()
        connecting = false
        connected = false
        wifiControlStatus = ""
        publish("Disconnected")
    }

    fun close() {
        disconnect()
    }

    @SuppressLint("MissingPermission")
    fun setEspSimulation(enabled: Boolean) {
        writeControlCommand(
            if (enabled) "sim=1" else "sim=0",
            statusMessage = { accepted ->
                if (accepted) "ESP ${if (enabled) "simulation" else "live input"} command sent" else "ESP command failed"
            }
        )
    }

    fun syncInjectorCalibration(flowLbHr: Double, injectorCount: Int) {
        val flow = flowLbHr.coerceIn(1.0, 200.0)
        val count = injectorCount.coerceIn(1, 16)
        writeControlCommand(
            "flow=${"%.2f".format(Locale.US, flow)}",
            statusMessage = { accepted ->
                if (accepted) "Injector flow sent to ESP" else "Injector flow command failed"
            }
        )
        writeControlCommand(
            "injectors=$count",
            statusMessage = { accepted ->
                if (accepted) "Injector count sent to ESP" else "Injector count command failed"
            }
        )
    }

    fun setEspWifiEnabled(enabled: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        writeControlCommand(
            if (enabled) "wifi_on=1" else "wifi_on=0",
            updateWifiStatus = true,
            statusMessage = { accepted ->
                if (accepted) {
                    "Wi-Fi service ${if (enabled) "starting on ESP…" else "stopping on ESP…"}"
                } else {
                    "Wi-Fi service command failed"
                }
            },
            onComplete = onResult
        )
    }

    fun configureEspWifi(ssid: String, password: String, onResult: ((Boolean, String) -> Unit)? = null) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isBlank()) {
            publishWifi("Enter a Wi-Fi SSID first")
            onResult?.invoke(false, wifiControlStatus)
            return
        }
        if ('|' in cleanSsid || '|' in password) {
            publishWifi("Wi-Fi SSID/password cannot contain |")
            onResult?.invoke(false, wifiControlStatus)
            return
        }
        writeControlCommand(
            "wifi=$cleanSsid|$password",
            updateWifiStatus = true,
            statusMessage = { accepted ->
                if (accepted) {
                    "Credentials queued for \"$cleanSsid\". Waiting for ESP to join…"
                } else {
                    "Could not send Wi-Fi credentials"
                }
            },
            onComplete = { accepted ->
                onResult?.invoke(accepted, wifiControlStatus)
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun writeControlCommand(
        command: String,
        updateWifiStatus: Boolean = false,
        statusMessage: (Boolean) -> String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val characteristic = controlCharacteristic
        val activeGatt = gatt
        if (!connected || characteristic == null || activeGatt == null) {
            val message = "ESP control not ready"
            if (updateWifiStatus) publishWifi(message) else publish(message)
            onComplete?.invoke(false)
            return
        }
        if (!hasConnectPermission()) {
            val message = "Bluetooth connect permission needed"
            if (updateWifiStatus) publishWifi(message) else publish(message)
            onComplete?.invoke(false)
            return
        }

        val bytes = command.toByteArray(StandardCharsets.UTF_8)
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            activeGatt.writeCharacteristic(characteristic)
        }
        val message = statusMessage(accepted)
        if (updateWifiStatus) {
            publishWifi(message)
        } else {
            publish(message)
        }
        onComplete?.invoke(accepted)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(doneStatus: String) {
        if (scanning && hasScanPermission()) scanner?.stopScan(scanCallback)
        scanning = false
        autoScan = false
        handler.removeCallbacks(scanTimeout)
        status = doneStatus
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        if (hasConnectPermission()) gatt?.close()
        gatt = null
        controlCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasConnectPermission()) return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CccdUuid) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleBytes(bytes: ByteArray?) {
        if (bytes == null) return
        val packet = bytes.toString(StandardCharsets.UTF_8)
        telemetry = parseTelemetry(packet)
        handler.post { publishTelemetry() }
    }

    private fun parseTelemetry(packet: String): InjectorTelemetry {
        val values = packet.split(',')
            .mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .toMap()
        return InjectorTelemetry(
            widthUs = values["width_us"].toDoubleOrZero(),
            pulsesPerSecond = values["pps"].toDoubleOrZero(),
            duty = values["duty"].toDoubleOrZero(),
            gallonsPerHour = values["gph"].toDoubleOrZero(),
            injectorFlowLbHr = values["flow_lb_hr"].toDoubleOrZero().takeIf { it > 0.0 } ?: 19.0,
            injectorCount = values["injectors"]?.toIntOrNull() ?: 8,
            signalActive = values["signal"] == "1",
            simulatedTelemetryEnabled = values["sim"] == "1",
            wifiServiceEnabled = values["wifi_on"] == "1",
            wifiConnected = values["wifi"] == "1",
            otaIp = values["ip"]?.takeIf { it.isNotBlank() } ?: "0.0.0.0",
            wifiAttemptSsid = values["wifi_ssid"]?.takeIf { it.isNotBlank() }.orEmpty(),
            rawPacket = packet
        )
    }

    private fun publish(message: String) {
        status = message
        emitState()
    }

    private fun publishWifi(message: String) {
        wifiControlStatus = message
        emitState()
    }

    private fun publishTelemetry() {
        status = "Receiving telemetry"
        emitState()
    }

    private fun emitState() {
        val adapter = bluetoothManager?.adapter
        onState(
            FuelBleState(
                supported = adapter != null,
                bluetoothEnabled = adapter?.isEnabled == true,
                hasPermissions = hasScanPermission() && hasConnectPermission(),
                scanning = scanning,
                connecting = connecting,
                connected = connected,
                controlReady = controlCharacteristic != null,
                status = status,
                wifiControlStatus = wifiControlStatus,
                telemetry = telemetry
            )
        )
    }

    fun hasScanPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun readName(result: ScanResult): String {
        return result.scanRecord?.deviceName ?: readDeviceName(result.device)
    }

    @SuppressLint("MissingPermission")
    private fun readAddress(device: BluetoothDevice): String {
        return if (hasConnectPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { device.address }.getOrNull().orEmpty()
        } else {
            ""
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceName(device: BluetoothDevice): String {
        return if (hasConnectPermission()) runCatching { device.name }.getOrNull().orEmpty() else ""
    }

    private fun scheduleAutoReconnect(reason: String) {
        if (userDisconnectRequested || rememberedAddress == null || connected || connecting || scanning) return
        autoReconnectAttempt += 1
        val delayMs = if (autoReconnectRetriesRemaining > 0) {
            autoReconnectRetriesRemaining -= 1
            AUTO_RECONNECT_FAST_DELAY_MS
        } else {
            AUTO_RECONNECT_STEADY_DELAY_MS
        }
        publish("$reason; auto-connect retry $autoReconnectAttempt")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    companion object {
        private const val PREF_DEVICE_ADDRESS = "device_address"
        private const val PREF_DEVICE_NAME = "device_name"
        private const val AUTO_RECONNECT_RETRIES = 5
        private const val AUTO_RECONNECT_FAST_DELAY_MS = 2_500L
        private const val AUTO_RECONNECT_STEADY_DELAY_MS = 15_000L
    }
}

