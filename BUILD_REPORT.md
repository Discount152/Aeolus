# Aeolus 1.0.9 — Stale 429 survival, rate-limit UX, map covered-skip

## Version

- versionName: `1.0.9`
- versionCode: `10`
- APK: `dist/Aeolus-v1.0.9.apk`
- SHA-256: `28af5bce81a98f27ab6751d7abd7caef1eb9187a88ece1c2f93089518c619c5f`
- Baseline: 1.0.8 `a67a042` (history kept)

## Must-ship

1. Live 429 / incoherent + same-location cache, in-memory prior, or matching LKG → stale bundle + `STALE_WITH_CACHE`. Cross-location LKG/cache refused. LKG write-refuse-incoherent unchanged.
2. Validated network + no same-location data → `WEATHER SERVICE TEMPORARILY BUSY` / `Try again shortly.` Retry kept with 2s anti-spam. Not OFFLINE, not `HTTP 429`.
3. Map never `reload_covered` / never empty `timeline_publish` unless unified `playable` or ≥1 READY. Otherwise `needRadar`/`needForecast`. Radar-only timeline valid; total unavailable only when observed READY is also empty.

## Optional (same release)

- Map forecast does not start while core `getWeather` is in flight or gate cooldown is active.
- Subtle `FORECAST TEMPORARILY UNAVAILABLE` when observed READY exists and forecast does not.

## Deferred

Three-class queue/preempt; multi-model HTTP merge; request-count ship gate; CORE/FULL types; confidence rewrite; persisted map-forecast cache; 30–60s global cooldown; full A–F fake-provider matrix.

## Automated tests

`testDebugUnitTest`: **128** pass. `lintDebug`: 0 errors.

## Physical device (`57261FDCQ0011Q`)

One Open-Meteo probe: **HTTP 429**. Did not hammer. Installed 1.0.9 without wipe.

- Today / Forecast: busy copy + hint + Retry. Not OFFLINE. Not `HTTP 429`.
- LKG still missing (`exists=false`); first-run honesty blank is correct.
- Map: `forecast_yield` on cooldown; no empty `reload_covered` / `timeline_publish`. Radar READY not observed in this 429 window. Attribution footer present.

Do not claim healthy-provider Groningen / LKG-created / offline-cold-start QA while the provider is 429.
