# Aeolus 1.0.7 — State Integrity, Map Repair, Rate-Limit Hardening, Map UX

## Version

- versionName: `1.0.7`
- versionCode: `8`
- APK: `dist/Aeolus-v1.0.7.apk`
- Prior APKs preserved: v1.0.0–v1.0.6

## Diagnosis basis

`docs` store report: `aeolus-1.0.6-regression-root-cause.md` (false OFFLINE from stale/429; map cancel stuck preparing; timeline mutation; TopCenter chrome).

## Today / connectivity

| State | When |
|-------|------|
| LIVE | Validated network + fresh bundle |
| REFRESHING_WITH_CACHE | Refresh in progress with matching-location cache |
| STALE_WITH_CACHE | Validated network + stale/error with cache — **not** “OFFLINE” |
| OFFLINE_WITH_CACHE | **No** validated network + cache |
| ONLINE_ERROR_NO_CACHE | Validated network + no cache |
| OFFLINE_NO_CACHE | No network + no cache |

Banner copy: offline → `OFFLINE DATA`; stale online → `SHOWING SAVED DATA · LIVE UPDATE UNAVAILABLE`.

## Open-Meteo coordination

- Shared `OpenMeteoGate` on forecast/ensemble/geocoding OkHttp client
- Max **2** concurrent Open-Meteo HTTP requests
- HTTP **429**: honor `Retry-After` (capped) + exponential backoff + jitter; ≤2 retries; never maps to OFFLINE
- Map-pin selection debounce **500 ms**

## LocationGeneration

- Monotonic generation per authoritative location change
- Prior `weatherLoadJob` cancelled; results applied only if `locationGen == activeLocationGeneration`
- UI bundle filtered to **matching** `LocationIdentity.stableId` only
- Ephemeral map pins: no `findNearest`; failed live never retains another city’s weather

## Map

- **MapGeneration** on viewport reload; pipelines take `generation`; `updateFrame` generation-guarded
- Cancellation clears `preparing` for active gen only; stale gens cannot mutate newer sessions
- Atomic `publishedTimeline` when both pipelines terminal; early radar preview allowed without scrub reset
- **selectedInstant** canonical; scrub/playback/source/time derive from it; preserved across publish
- Duplicate NOW removed (observed preferred; forecast strictly after NOW boundary)
- Bottom weather chrome; attribution edge; opacity behind Tune dialog; no mid-map control stack

## Automated tests

- `testDebugUnitTest`: **108** tests, **0** failures (including connectivity, timeline NOW dedupe, gate concurrency policy, wrong-location identity/retain)
- `assembleRelease` + lintVital: success
- `OpenMeteoGate` interceptor on forecast/ensemble/geocoding + ensemble raw OkHttp; RainViewer ungated

## Physical device (`57261FDCQ0011Q`)

| Check | Result |
|-------|--------|
| Install 1.0.7 (versionCode 8) | Pass (rebuilt APK after gate + wrong-location wiring) |
| Offline-style refresh reject with prior weather | Pass — incoherent live rejected; LKG/same-location cache preserved; wrong-location prior cleared |
| Interactive Today matrix / Map pan-during-load / playback UI | Attempted; keyguard/`DreamActivity` may block — see final QA evidence |

## Preserved 1.0.6 wins

Coherent-bundle LKG gate, ICON precip, sky summaries, Today→Forecast expand, timezone modes, radar mosaics, ensemble.
