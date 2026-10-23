# Aeolus 1.0.7 — wrong-location retain + Open-Meteo gate

## Problem

Map-pin selection retained prior city weather under the new pin when live Open-Meteo failed (429 / incoherent). UI also mapped `bundle.stale` to OFFLINE. Open-Meteo bursts had no shared concurrency limit.

## Root cause

`WeatherRepository.getWeather` catch used `findNearest` and retained any `_weather` prior. `AppContainer` used one ungated OkHttp client for all hosts. Connectivity presentation keyed off stale/error instead of validated network.

## Fix

- `OpenMeteoGate` (max 2 + 429 Retry-After/backoff) on forecast/ensemble/geocoding OkHttp; RainViewer stays ungated.
- Ephemeral locations: no `findNearest`; failed live clears cross-location prior; UI filters by `stableId`.
- Connectivity: STALE_WITH_CACHE vs OFFLINE_WITH_CACHE from validated network.

## Verify

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
adb -s 57261FDCQ0011Q install -r dist\Aeolus-v1.0.7.apk
# Offline cold-start: wifi/data off → LKG hydrate + live_refresh_rejected_incoherent; LKG not wiped
```
