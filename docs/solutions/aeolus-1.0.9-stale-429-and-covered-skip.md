# Aeolus 1.0.9 — stale 429 survival and map covered-skip

## Problem

Under Open-Meteo HTTP 429, Today went blank whenever live was incoherent and per-location cache missed — even if a matching LastKnownGood existed. Map could log `reload_covered` / `timeline_publish frames=0` after a failed forecast still marked the viewport covered.

## Root cause

- `getWeather` catch only reused ForecastCache or same-location in-memory prior. LKG was boot-only.
- Map `skipNetwork` required radar frames + both footprints, not unified READY/`playable`, and not `forecastFramesReady`.

## Fix

- Same-location cache, in-memory prior, or **matching** LKG (`stableId`) → stale bundle / `STALE_WITH_CACHE`. Never another location. LKG write still refuses incoherent.
- Validated-network empty state uses `WEATHER SERVICE TEMPORARILY BUSY` + Retry anti-spam (2s). Not OFFLINE, not `HTTP 429`.
- Covered-skip / publish only if unified `playable` or ≥1 READY; otherwise `needRadar`/`needForecast`. Map forecast yields while core `getWeather` is in flight or the gate is cooling down.

## Verify

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease
# StaleOnRateLimitTest + MapCoveredSkipTest
adb -s 57261FDCQ0011Q install -r dist\Aeolus-v1.0.9.apk
# One Open-Meteo probe only. If 429: busy copy, no empty reload_covered. Do not claim healthy-provider QA.
```
