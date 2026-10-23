# Aeolus 1.0.6 — Unified Map, Forecast UX, Offline Instrumentation

## Version

- versionName: `1.0.6`
- versionCode: `7`
- Prior APKs preserved: v1.0.0–v1.0.5

## Unified Map

- Removed OBSERVED / FORECAST tabs.
- One timeline: past RainViewer (~10 min) → NOW → ICON Seamless hourly forecast (24h).
- Scrub/playback by absolute timestamps; source label on current frame (`OBSERVED · RainViewer` / `FORECAST · ICON`).
- Observed mosaic architecture unchanged (≤4 tiles/frame, READY-only ImageSource).

## Forecast precipitation renderer

- Model: Open-Meteo **`icon_seamless`** (coherent viewport; not best_match).
- Adaptive sample grid (~18×12 … 24×16, cap 384), batched multi-location requests.
- Rate bilinear interpolation then dry mask `< 0.1 mm/h` → transparent.
- Blue/cyan intensity scale; 512×512 rasters; no debug mosaic/tile strings in UI.

## Timezones

- Wall-clock primary labels (`18:40 TRT`), not `UTC+3` arithmetic.
- VIEWED resolves IANA via Open-Meteo `timezone=auto` with spatial cache; offline uses last known / device — no longitude invention.
- Midnight crossing appends date context.

## Daily Forecast

- `SkyCondition` daylight cloud-cover categories on Forecast cards.
- Today daily card tap → Forecast route + auto-expand matching date.

## Offline cold-start

- `adb devices` at task start and before release: **no authorized physical device attached**.
- Per §33: offline defect is **not** marked fixed; 1.0.6 is **not** release-complete for that job.
- Instrumentation added: process_start / write_attempt / write_success / startup_read / bootstrap / live_refresh logs (`LastKnownGood` tag); fsync on write; retain existing bundle on failed refresh; prefer LKG location on bootstrap.

## Tests / build

- Unit tests: **94 passed**, 0 failures
- Lint debug: SUCCESS (0 errors)
- assembleRelease: SUCCESS

## Artifact

- `D:\Project Aeolus\dist\Aeolus-v1.0.6.apk`
- SHA-256: `2e0f5cea0f2f952295d24f920bde09a6017b62bc1ce6057e3baeb1783c38ece5`
