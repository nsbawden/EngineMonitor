# Fuel Math

## Basic idea

Measure the on-time of one injector, then estimate total engine fuel use by multiplying by the number of injectors.

For a V8 sequential EFI engine:

```text
fuel_per_second = injector_flow_per_second * injector_on_fraction * 8
```

The phone supplies GPS speed:

```text
mpg = miles_per_hour / gallons_per_hour
```

## Units

Injector flow is often rated in lb/hr or cc/min. The firmware/app should eventually allow either.

Useful approximations:

```text
1 gallon gasoline ~= 6.1 lb
1 gallon ~= 3785.41 cc
```

## Important calibration factors

- Actual injector size
- Fuel pressure
- Injector dead time / opening delay
- Battery voltage
- ECU strategy
- Whether the sampled injector behavior represents all eight injectors

## First-pass calculation

For prototype display, start with:

```text
injector_duty = injector_on_microseconds_per_second / 1000000.0
gallons_per_hour = injector_gph_at_100_percent * injector_duty * 8
mpg = speed_mph / gallons_per_hour
```

At idle, speed is zero, so use gallons/hour instead of MPG.
