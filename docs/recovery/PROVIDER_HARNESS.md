# Debug weather provider harness

Branch: `recovery/provider-harness`  
Base: `cursor/aeolus-1.0.9-must-ship-2996` @ `8ad7dc7`  
Debug APK only. Release `createAppContainer` keeps live Open-Meteo + gate.

## What it is

OkHttp interceptor installed **after** `OpenMeteoGate` on the shared Open-Meteo client. Real `WeatherRepository`, Retrofit, mapper, cache, LKG, and UI still run. RainViewer is not intercepted.

Default scenario is `LIVE` (passthrough).

## Scenarios

| Scenario | Transport | Expected core path |
|----------|-----------|--------------------|
| `SUCCESS_FULL` | 200 all models + ensemble + geocode (Groningen / Side by query) | Live coherent; writes cache + LKG |
| `SUCCESS_CORE_ONLY` | 200 ICON only; ECMWF/GFS/ensemble 500 | Live coherent; `missingModels` ECMWF+GFS |
| `HTTP_429` | 429 + `Retry-After: 1` | Gate retries then stale/LKG or busy |
| `HTTP_500` | 500 all OM | Incoherent → stale/LKG or throw |
| `TIMEOUT` | `SocketTimeoutException` | Same catch as IO failure |
| `EMPTY_200` | 200 `{}` | Incoherent (availableModels=0) |
| `PARTIAL_MODEL_FAILURE` | ECMWF 500; ICON+GFS+ensemble 200 | Live; ECMWF missing |
| `SLOW_SUCCESS` | 2.5s delay then `SUCCESS_FULL` | Refreshing UI, then live |

## Device switch (debug APK)

```powershell
adb -s 57261FDCQ0011Q shell am broadcast -a com.discount152.aeolus.debug.SET_WEATHER_SCENARIO -n com.discount152.aeolus/.debug.DebugWeatherHarnessReceiver --es scenario SUCCESS_FULL
```

Persisted in `aeolus_debug_harness` so a force-stop / cold start keeps the scenario. Log tag: `AeolusHarness`.

Reset to live:

```powershell
adb -s 57261FDCQ0011Q shell am broadcast -a com.discount152.aeolus.debug.SET_WEATHER_SCENARIO -n com.discount152.aeolus/.debug.DebugWeatherHarnessReceiver --es scenario LIVE
```

## Tests

```powershell
.\gradlew.bat testDebugUnitTest --tests com.discount152.aeolus.debug.*
```
