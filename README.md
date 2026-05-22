# 302 Engine Monitor

Instantaneous fuel-use monitor for a 1989 Ford 302 EFI engine.

The first prototype targets an ESP32 reading one injector pulse signal through a protected input circuit, then sending pulse timing data to a phone app. The phone app supplies GPS speed and computes instantaneous fuel economy.

## Layout

- firmware/esp32/injector_pulse_meter: ESP32 firmware sketch for injector pulse measurement and wireless telemetry.
- docs: wiring notes, fuel math, and project planning notes.
- phone-app: Android app (BLE telemetry, GPS speed, MPG, trips, fill-ups, history).

## First milestone

1. Build a protected injector-signal input circuit on the bench.
2. Verify pulse-width measurement using a simulated 12 V square wave.
3. Stream pulse timing over serial.
4. Add BLE telemetry.
5. Combine injector timing with phone GPS speed.
