# Engine Monitor Android App

Android client for the Engine Monitor ESP32 BLE telemetry.

The app scans for the ESP32 BLE service, connects to `302 Fuel Monitor`, subscribes to telemetry notifications, parses the text packet, and displays injector pulse width, pulse rate, duty cycle, gallons/hour, GPS speed, and instantaneous MPG. Injector count, flow rate, and fuel calibration are configurable in settings for different multipoint EFI engines.

Open `phone-app` in Android Studio (or build from the command line), then run on a phone with Bluetooth and Location enabled. Android requires location permission for BLE scanning on older releases, and this app also uses GPS speed for MPG.

## Build

From `phone-app`:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install to USB-connected phone

**Use the install script** (build first if needed). It force-stops the app, installs the APK, and launches `MainActivity` so you always run the new build:

```powershell
.\gradlew.bat assembleDebug
.\install-debug.ps1
```

Do not rely on `adb install -r` alone — that updates the APK on disk but leaves a running app on the old process until you swipe it away or reopen it manually.

Equivalent manual steps:

```powershell
adb shell am force-stop com.example.fuelmonitor
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.fuelmonitor/.MainActivity
```

BLE contract:

- Device name: `302 Fuel Monitor`
- Service UUID: `5f6d9f20-6f2d-4f51-a2c7-302000000001`
- Telemetry characteristic UUID: `5f6d9f20-6f2d-4f51-a2c7-302000000002`
- Packet example: `width_us=12178,pps=18.33,duty=0.2232,gph=5.500,flow_lb_hr=19.00,injectors=8,signal=1,sim=1,wifi_on=1,wifi=1,ip=192.168.1.139,wifi_ssid=MyNetwork`

History logging:

- Live history is stored in the app-private file `files/fuel_history.csv`.
- Numeric fields that are unavailable for a given sample use the sentinel value `-999999.0`.
- Future code that reads history values must treat `-999999.0` as missing data, not as a real measurement. Graphs, averages, trip analysis, calibration helpers, and exports should either ignore sentinel values or preserve them explicitly for downstream analysis.
- The sentinel is currently used for missing per-sample values such as altitude, speed, or instant MPG when the rest of the live row is still worth keeping.
