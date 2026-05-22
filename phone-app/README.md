# 302 Fuel Monitor Android App

Simple native Android app for the first ESP32 BLE telemetry milestone.

The app scans for the ESP32 BLE service, connects to `302 Fuel Monitor`, subscribes to telemetry notifications, parses the text packet, and displays injector pulse width, pulse rate, duty cycle, gallons/hour, GPS speed, and instantaneous MPG.

Open `phone-app` in Android Studio, let Gradle sync, then run it on an Android phone with Bluetooth and Location enabled. Android requires location permission for BLE scanning on older releases, and this app also uses GPS speed for MPG.

The debug APK builds at `phone-app/app/build/outputs/apk/debug/app-debug.apk`.

BLE contract:

- Device name: `302 Fuel Monitor`
- Service UUID: `5f6d9f20-6f2d-4f51-a2c7-302000000001`
- Telemetry characteristic UUID: `5f6d9f20-6f2d-4f51-a2c7-302000000002`
- Packet example: `width_us=12178,pps=18.33,duty=0.2232,gph=5.500,flow_lb_hr=19.00,injectors=8,signal=1,sim=1`

History logging:

- Live history is stored in the app-private file `files/fuel_history.csv`.
- Numeric fields that are unavailable for a given sample use the sentinel value `-999999.0`.
- Future code that reads history values must treat `-999999.0` as missing data, not as a real measurement. Graphs, averages, trip analysis, calibration helpers, and exports should either ignore sentinel values or preserve them explicitly for downstream analysis.
- The sentinel is currently used for missing per-sample values such as altitude, speed, or instant MPG when the rest of the live row is still worth keeping.
