# Project Plan

## Goal

Build an instantaneous fuel utility for a 1989 Ford 302 EFI engine using injector pulse timing and phone GPS speed.

## Recommended first version

- Controller: ESP32
- Vehicle signal: one injector low-side control wire
- Input protection: optocoupler if available; otherwise high-value divider, series resistor, clamp diodes, and small RC filter
- Phone link: BLE first
- Firmware update path: USB initially, Wi-Fi OTA later

## Milestones

1. Bench input circuit
   - Generate a safe 12 V pulse signal.
   - Confirm GPIO voltage stays between 0 V and 3.3 V.
   - Confirm pulse edges are readable.

2. Pulse measurement firmware
   - Use GPIO interrupt edge timing.
   - Track injector on-time in microseconds.
   - Report pulse width, pulse count, duty cycle, and estimated fuel flow over serial.

3. Vehicle smoke test
   - Use fused power.
   - Confirm common ground quality.
   - Observe injector signal without permanently splicing.
   - Compare idle pulse widths against expected values.

4. BLE phone telemetry
   - Publish pulse timing and fuel-flow estimate.
   - Let phone provide GPS speed.

5. Calibration
   - Enter injector flow rate.
   - Estimate injector dead time.
   - Tune against fill-up mileage and steady-road observations.
