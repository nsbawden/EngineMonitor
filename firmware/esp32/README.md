# ESP32 Firmware

Board: ACEBOTT QA007/QA008/QA009 ESP32 Max V1.0, which ACEBOTT documents as an ESP-WROOM-32 board. Use the Arduino ESP32 core board target `ESP32 Dev Module` (`esp32:esp32:esp32`) unless testing shows the DOIT target works better.

Current sketch:

- Reads protected injector pulse input on GPIO 27.
- Assumes active-low injector pulses.
- Prints telemetry over serial at 115200 baud.
- Starts in bench simulator mode, equivalent to about 55 MPH at 10 MPG.
- Advertises BLE device name `ESP Engine Monitor`.
- Telemetry notify is open; control writes require a bonded BLE connection.
- Remembered OTA Wi-Fi passwords are encrypted in NVS.
- Notifies telemetry on service `5f6d9f20-6f2d-4f51-a2c7-302000000001`, characteristic `5f6d9f20-6f2d-4f51-a2c7-302000000002`.

CLI build/upload from the repo root.

Important: this project uses BLE plus Wi-Fi OTA, so the default ESP32 partition
scheme is too small. Use `PartitionScheme=no_fs` every time. It keeps OTA support
and gives two 2 MB app slots by removing the filesystem partition, which this
firmware does not use.

```powershell
$buildPath = Join-Path (Get-Location) "firmware\esp32\injector_pulse_meter\build-no-fs"
& "C:\Program Files\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe" compile -j 2 --build-path $buildPath --fqbn esp32:esp32:esp32:PartitionScheme=no_fs --library "$env:USERPROFILE\AppData\Local\Arduino15\packages\esp32\hardware\esp32\3.2.0\libraries\BLE" firmware\esp32\injector_pulse_meter
```

The explicit `--library` keeps the ESP32 core BLE library ahead of the user-installed `ArduinoBLE` library, which also contains a `BLEDevice.h` header.

OTA upload:

1. In the Android app, connect over BLE.
2. Devices -> OTA Wi-Fi -> turn `ESP Wi-Fi service` on.
3. Wait for the app to show the OTA IP.
4. Upload directly with `espota.exe`; this has been more reliable than `arduino-cli upload` for network ports.

Replace `$espIp` with the IP shown in the app, for example `10.87.228.117`.

```powershell
$espIp = "10.87.228.117"
$buildPath = Join-Path (Get-Location) "firmware\esp32\injector_pulse_meter\build-no-fs"
& "$env:USERPROFILE\AppData\Local\Arduino15\packages\esp32\hardware\esp32\3.2.0\tools\espota.exe" -r -i $espIp -p 3232 "--auth=" -f "$buildPath\injector_pulse_meter.ino.bin"
```

Optional discovery check:

```powershell
& "C:\Program Files\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe" board list --format json
```

USB upload fallback:

```powershell
$buildPath = Join-Path (Get-Location) "firmware\esp32\injector_pulse_meter\build-no-fs"
& "C:\Program Files\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe" upload -p COM5 --fqbn esp32:esp32:esp32:PartitionScheme=no_fs --input-dir $buildPath firmware\esp32\injector_pulse_meter
```

Serial-only compile for injector bench work:

```powershell
& "C:\Program Files\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe" compile --fqbn esp32:esp32:esp32:PartitionScheme=no_fs --build-property "compiler.cpp.extra_flags=-DENABLE_BLE=0" firmware\esp32\injector_pulse_meter
```

Serial commands:

- `flow=19` sets injector flow in lb/hr.
- `injectors=8` sets injector count.
- `density=6.17` sets fuel density in lb/gal.
- `sim=1` enables bench simulator telemetry.
- `sim=0` uses the real GPIO 27 injector input.
- `wifi_on=1` enables OTA Wi-Fi service.
- `wifi_on=0` disables OTA Wi-Fi service.
- `cal` prints the current calibration.
- `help` prints commands.

Bench-test input values:

- 12 V or simulated injector signal -> 150k resistor -> divider node.
- Divider node -> 33k resistor -> ground.
- Divider node -> 22k series resistor -> ESP32 GPIO 27.
- GPIO 27 -> BAT85/1N5819 Schottky clamp to 3.3 V.
- GPIO 27 -> BAT85/1N5819 Schottky clamp to ground.
- GPIO 27 -> 2.2 nF capacitor -> ground.

This divider gives about 2.16 V at the divider node from a 12 V signal before clamps, with high source impedance and additional GPIO series resistance. For real vehicle testing, prefer the optocoupler input and add automotive transient protection.
