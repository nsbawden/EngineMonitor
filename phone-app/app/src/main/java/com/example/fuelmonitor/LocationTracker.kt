package com.example.fuelmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat

class LocationTracker(
    context: Context,
    private val onState: (LocationState) -> Unit
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private var state = LocationState()
    private var speedSimulationEnabled = true
    private var simulatedSpeedMph = BenchSpeedMph

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val fixHasSpeed = location.hasSpeed()
            val fixHasAltitude = location.hasAltitude()
            val nextAltitudeFt = if (fixHasAltitude) location.altitude * 3.280839895 else state.altitudeFt
            val nextHasAltitude = state.hasAltitude || fixHasAltitude
            state = state.copy(
                hasPermission = hasPermission(),
                speedMph = if (speedSimulationEnabled) simulatedSpeedMph else if (fixHasSpeed) location.speed * 2.2369362921 else state.speedMph,
                speedFromCurrentFix = !speedSimulationEnabled && fixHasSpeed,
                speedSimulated = speedSimulationEnabled,
                altitudeFt = nextAltitudeFt,
                hasAltitude = nextHasAltitude,
                altitudeFromCurrentFix = fixHasAltitude,
                usingFallbackSpeed = speedSimulationEnabled || !fixHasSpeed,
                status = when {
                    speedSimulationEnabled -> "Bench speed simulation: %.0f MPH".format(simulatedSpeedMph)
                    fixHasSpeed && fixHasAltitude -> "GPS live"
                    fixHasSpeed -> "GPS live, waiting for altitude"
                    nextHasAltitude -> "GPS fix, no speed"
                    else -> "GPS fix, waiting for altitude"
                }
            )
            onState(state)
        }
    }

    fun requiredPermissions(): Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun setSimulation(enabled: Boolean, speedMph: Double) {
        speedSimulationEnabled = enabled
        simulatedSpeedMph = speedMph
        state = state.copy(
            speedMph = if (enabled) speedMph else state.speedMph,
            speedFromCurrentFix = false,
            speedSimulated = enabled,
            usingFallbackSpeed = enabled,
            altitudeFromCurrentFix = false,
            status = if (enabled) "Bench speed simulation: %.0f MPH".format(speedMph) else "Waiting for GPS"
        )
        onState(state)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) {
            state = state.copy(hasPermission = false, status = "Location permission needed")
            onState(state)
            return
        }
        state = state.copy(
            hasPermission = true,
            speedMph = if (speedSimulationEnabled) simulatedSpeedMph else state.speedMph,
            speedFromCurrentFix = false,
            speedSimulated = speedSimulationEnabled,
            usingFallbackSpeed = speedSimulationEnabled,
            altitudeFromCurrentFix = false,
            status = if (speedSimulationEnabled) "Bench speed simulation: %.0f MPH".format(simulatedSpeedMph) else "Waiting for GPS"
        )
        onState(state)
        locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0.0f, listener)
    }

    fun stop() {
        locationManager?.removeUpdates(listener)
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
