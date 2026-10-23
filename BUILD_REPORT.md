# Aeolus 1.0.8 — Map pin weather, latency, playback, attribution

## Version

- versionName: `1.0.8`
- versionCode: `9`
- APK: `dist/Aeolus-v1.0.8.apk`
- Baseline: 1.0.7 (PR #8)

## 1. Map-pin weather — diagnosed root cause

**Demonstrated cause (city OK vs pin UNAVAILABLE):**

1. Ephemeral map pins have no per-coordinate cache; on live failure UI shows `WEATHER DATA TEMPORARILY UNAVAILABLE` (correct no-cross-location rule from 1.0.7).
2. Pin selection while Map is active competed with multi-batch Open-Meteo forecast grid (up to ~5×80-point batches) through the shared max-2 gate → 429 / empty models / incoherent reject.
3. Pin `GeoLocation.timezone` borrowed viewport/`ZoneId.systemDefault()` instead of Open-Meteo `timezone=auto` for WGS84 points.
4. Device logs under load: `live_fetch_begin … tz=auto ephemeral=true` then `availableModels=0` when provider returns 429.

**Repair:**

- Pin / ephemeral fetch uses `timezone=auto`; resolved IANA TZ written back from API response.
- Ephemeral first paint: **ICON only** (no 3-model+ensemble stampede).
- Map forecast grid reduced to ≤2 batches; pipelines parallel; cancelled gate work releases promptly.
- Stable `coord:` identity unchanged by later TZ enrichment.

## 2. Map ~15s latency — profiled bottleneck

| Stage | 1.0.7-ish observation | Fix |
|-------|----------------------|-----|
| ViewedTimezone resolve (blocking) | ~9s on 429 retries before overlays | Non-blocking; skip if TZ known |
| Radar then forecast sequential | Sum of both | `async` parallel |
| Forecast grid batches | ~3–5 × 80 pts | ≤2 × 100 pts (12×8 default) |
| Empty covered-skip | gen2 skip with 0 frames | Require radar frames before skip |
| Early usable | Waited both pipelines | Publish when radar ready |

Device sample (pre-TZ-fix): `radar_done gen=2 ms=3528` then `timeline_publish … frames=13 playable=true`; full pipelines `17888` ms with TZ 429 ahead of overlays.

## 3. Playback 8+ fps — root cause

`LaunchedEffect(playback, ready, selectedInstant)` restarted the playback loop on **every** `selectedInstant` update → overlapping advances ≈ multi-fps.

**Repair:** single `LaunchedEffect(playback)` clock; cadence **650 ms** / NOW **1000 ms** / loop **750 ms**; `USER_PAUSED` vs `AUTO_PAUSED_FOR_CAMERA` with resume on playable active generation only.

## 4. Attribution

Removed floating `BottomStart` overlay (`bottom = 118.dp`) that could cover Play.

Footer inside bottom chrome:

`Map © OpenStreetMap/OpenFreeMap · Weather RainViewer/Open-Meteo`

## 5. Automated tests

`testDebugUnitTest`: **113** pass (grid/batch, pin identity, playback cadence, prior suites).

## 6. Physical device notes (`57261FDCQ0011Q`)

- Attribution in chrome + Play content-desc verified when Map UI accessible.
- MapLoad instrumentation confirms radar-first publish.
- Open-Meteo **HTTP 429** from shared egress IP blocked live pin/city success later in the session; keyguard/DreamActivity interrupted final interactive matrix.
- Offline LKG path preserved (1.0.7); fresh install without LKG correctly shows unavailable when all models empty.

## Preserve 1.0.7

Validated connectivity STALE vs OFFLINE, LocationGeneration, OpenMeteoGate, MapGeneration, coherent LKG, selectedInstant, NOW dedupe.
