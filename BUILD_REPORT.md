# Aeolus 1.0.6 — Unified Map, Forecast UX, Offline Cold-Start Fix

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

## Offline cold-start — physical device evidence

### Device

| Field | Value |
|-------|--------|
| Serial | `57261FDCQ0011Q` |
| Model | Pixel 10 Pro XL (`mustang`) |
| Android | 17 |
| ADB state | `device` |

### Pre-fix reproduction (bug)

1. Installed `dist/Aeolus-v1.0.6.apk` with `-r` (data preserved) over 1.0.5.
2. Online launch → LKG write_success **430574** bytes (`daily=16 hourly=48`).
3. `am force-stop` → disabled wifi/data/airplane → Active default network: **none**.
4. Relaunch offline logcat:

```
startup_read: decode_ok ... daily=16 hourly=48 ensemble=true
bootstrap: lkg_hydrated ... bundleDaily=16 weatherFlow=true
write_success: bytes=893 ... daily=0 hourly=0   ← DESTROYED GOOD SNAPSHOT
live_refresh: wrote_lkg=true ... daily=0 hourly=0
```

5. UI blank except local time (`Feels -`, `-%`, no temps).

### Root cause class

**E — Hydration succeeds but another startup state overwrites it.**

Specifically: offline `fetchLive` returned an “empty success” (all models unavailable → empty consensus) without throwing. `getWeather` treated that as a successful live refresh and **atomically overwrote** last-known-good with an 893-byte hollow bundle, then UI bound to the empty state.

### Fix

- `WeatherRepository.isCoherentLiveBundle`: require ≥1 available model with data + non-empty consensus daily/hourly.
- Incoherent live results throw into the existing failure path → retain prior `_weather` / cache; **do not** write LKG.
- `LastKnownGoodStore.write` refuses incoherent bundles (defense in depth).

### Post-fix acceptance (same serial)

Offline relaunch after fix:

```
decode_ok ... daily=16 hourly=48 ensemble=true
lkg_hydrated ... weatherFlow=true
live_refresh_rejected_incoherent: daily=0 hourly=0 availableModels=0 priorWeather=true
live_refresh_failed: ... retained path
```

UI hierarchy (uiautomator):

- Today: `OFFLINE DATA`, `7°C`, confidence `96% · HIGH`, precip `7.1 mm`, hourly strip populated
- Forecast: 15-day + hourly temps populated
- Models: ECMWF/ICON/GFS + ensemble members + confidence preserved
- LKG file size remained **429496** (not shrunk)

Reconnect: network restored → `live_refresh: wrote_lkg=true daily=16 hourly=48`; OFFLINE banner cleared; temps remain.

Evidence artifacts under `dist/qa-1.0.6-offline/` (screenshots + XML + logcat).

## Tests / build

- Unit tests: include `CoherentLiveBundleTest` — full suite green after fix
- assembleRelease: SUCCESS (rebuilt after fix)

## Artifact

- `D:\Project Aeolus\dist\Aeolus-v1.0.6.apk`
- SHA-256: `2f97a926b9356996722c23190a4481136559b94dce36f0e31fc89d89fdb1d258`
