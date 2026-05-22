package com.example.fuelmonitor.ui

import com.example.fuelmonitor.R

internal enum class AppTab(val label: String, val iconRes: Int) {
    Dashboard("Dash", R.drawable.ic_tab_dashboard),
    Trip("Trip", R.drawable.ic_tab_trip),
    Fillups("Fuel", R.drawable.ic_tab_fillups),
    History("History", R.drawable.ic_tab_history),
    Devices("Devices", R.drawable.ic_tab_bluetooth),
    Settings("Settings", R.drawable.ic_tab_settings)
}
