# Wiring Notes

## Injector signal assumptions

Many Ford EFI systems use low-side injector control:

- One injector pin receives switched battery voltage.
- The ECU pulls the other injector pin low to fire the injector.
- The ECU-side injector wire is high when the injector is off and low when the injector is on.

Verify this on your specific vehicle before connecting the ESP32.

## Preferred input: optocoupler

If an optocoupler is available, use it for the first vehicle-connected prototype.

Concept:

```text
Injector +12 V side -> resistor -> optocoupler LED -> ECU low-side injector wire

Optocoupler transistor -> ESP32 GPIO with 3.3 V pullup
```

Add a reverse diode across the optocoupler LED to protect it from reverse voltage.

## Alternate input: divider and clamps

A divider-and-clamp input can work if it is high impedance and current-limited.

Starting concept:

```text
Injector ECU-side wire
   |
  100k to 220k
   |
   +---- 10k to 47k ---- ESP32 GPIO
   |
  22k to 47k
   |
 Ground

At GPIO:
- Schottky diode to 3.3 V
- Schottky diode to ground
- optional 1 nF to 10 nF capacitor to ground
- optional 3.3 V or 3.6 V TVS/zener to ground
```

The series resistance should keep clamp current very low during spikes. Do not connect the injector wire directly to a GPIO.

## Vehicle power

Use a proper automotive-friendly supply path:

- Fused 12 V feed
- Reverse-polarity protection
- Buck converter to 5 V or 3.3 V as appropriate
- Input filtering
- TVS protection if available
- Shared ground chosen carefully, preferably close to ECU/sensor ground
