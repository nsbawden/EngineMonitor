# Vehicle test checklist

Use this before logging a drive with real injector input and GPS speed. Bench defaults keep simulation on for desk work; vehicle testing needs both sources live.

## Phone app (Devices tab)

1. Connect to the ESP over BLE (**Scan and Connect**).
2. Tap **Prepare for vehicle test** (or manually):
   - **ESP injector source** → off (`sim=0`, live GPIO input)
   - **Phone speed source** → off (live GPS)
3. Confirm **Settings** match your engine:
   - Injector flow (lb/hr)
   - Injector count (cylinders fed by the sampled bank, often half the engine count on batch-fire systems)
4. Grant **Location** and **Bluetooth** permissions if prompted.

## ESP32

1. Flash firmware if needed (USB or Wi-Fi OTA with **ESP Wi-Fi service** on).
2. Serial or BLE command `sim=0` if not set from the phone.
3. Confirm protected injector input is wired and `signal=1` in telemetry at idle/cruise.

## Quick verification

| Check | Expected |
|-------|----------|
| ESP `sim` in telemetry packet | `0` |
| Phone Devices → ESP injector source | Live protected injector input |
| Phone Devices → Phone speed source | Live GPS speed |
| Instant MPG at steady cruise | Plausible for your engine (not locked to bench ~5.5 GPH @ 55 MPH) |

## OTA Wi-Fi (Devices tab)

1. Connect over BLE.
2. Turn on **ESP Wi-Fi service** and wait until the switch confirms (or shows an error).
3. Enter the router SSID **exactly** as it appears in your phone’s Wi-Fi list.
4. Tap **Send Wi-Fi to ESP** once and watch the status line (trying → joined, or timeout with the SSID you sent).
5. Use the OTA IP shown for firmware upload.

## Return to bench mode

- Devices → turn **ESP injector source** on (`sim=1`) and/or **Phone speed source** on (55 MPH simulation) as needed for desk testing.
