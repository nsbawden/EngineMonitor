# Engine Monitor

Instantaneous fuel-use monitor for multipoint EFI engines.

An ESP32 reads one injector pulse signal through a protected input circuit and streams pulse timing over BLE. The Android app supplies GPS speed, configurable injector count and flow calibration, and computes instantaneous fuel economy, trips, fill-ups, and history.

## Layout

- firmware/esp32/injector_pulse_meter: ESP32 firmware sketch for injector pulse measurement and wireless telemetry.
- docs: wiring notes, fuel math, and project planning notes.
- phone-app: Android app (BLE telemetry, GPS speed, MPG, trips, fill-ups, history).

## Vehicle testing

Before a real drive, follow [docs/vehicle-test-checklist.md](docs/vehicle-test-checklist.md). In the app: **Devices → Prepare for vehicle test** (disables ESP and phone simulation).

## First milestone

1. Build a protected injector-signal input circuit on the bench.
2. Verify pulse-width measurement using a simulated 12 V square wave.
3. Stream pulse timing over serial.
4. Add BLE telemetry.
5. Combine injector timing with phone GPS speed.

## License

Released under [The Unlicense](LICENSE) (public domain dedication).
