# Aeolus 1.0.5 — Radar Mosaic, Forecast Precip, Timezones, Last-Known-Good

## Version

- versionName: `1.0.5`
- versionCode: `6`
- Prior APKs preserved: `dist/Aeolus-v1.0.0.apk` … `dist/Aeolus-v1.0.4.apk`

## Radar

- Architecture: Aeolus downloads RainViewer **512 px XYZ tiles**, stitches a complete mosaic, then displays via MapLibre `ImageSource`/`RasterLayer`. MapLibre never fetches animated radar tiles.
- Tile budget: **≤ 4 tiles/frame** (`RadarViewport.MAX_TILES_PER_FRAME`); source zoom reduced until cover fits.
- Padding: ~20% mercator margin for pan reuse.
- Mosaic: row-major stitch; READY only when every required tile decodes.
- Cache: per-tile LRU + per-frame mosaic LRU; second playback loop issues no new tile requests.
- Retry: first attempt + short backoff + second attempt; respect HTTP 429.
- Expected first-load requests: ~4 × ~13 past frames ≈ **52** (under RainViewer’s 100/min budget).

## Forecast map

- Modes: `[ OBSERVED ] [ FORECAST ]` (default OBSERVED).
- Source: Open-Meteo `v1/forecast` with **`models=best_match`**, batched multi-lat/lon (~12×8 grid) in one request.
- Horizon: next **24 hourly** frames; local bilinear raster (256×256); legend in user precip units.
- Clearly labeled as model precip — not radar.

## Playback auto-resume

- `PLAYING` → camera move → `AUTO_PAUSED_FOR_CAMERA` → camera idle → auto-resume when set ready.
- `USER_PAUSED` never auto-resumes.

## Map timezones

- Modes: VIEWED LOCATION (default), DEVICE, HOME, UTC, CUSTOM.
- Display: `Instant` → selected `ZoneId`; frame/cache identity unchanged.
- VIEWED: viewport/location zone (offline approx from longitude when needed).

## Offline / last-known-good

- `LastKnownGoodStore` atomic JSON snapshot (`filesDir/cache/last_known_good.json`, schema v1).
- Written only after coherent successful live refresh.
- Cold-start: read LKG first → restore location + Today/Forecast/Models → then connectivity/location resolve.
- Root cause addressed: per-location cache-key hydrate alone failed when active identity/GPS path diverged after force-stop; LKG embeds location + full bundle independently.

## Tests / build

- Unit tests: **82 passed**, 0 failures
- Lint debug: **SUCCESS** (0 errors)
- `assembleRelease`: **SUCCESS**

## Runtime

No emulator/adb on Aeolus-PC this run. Manual device QA recommended for mosaic sharpness, forecast overlay, timezone labels, and Airplane Mode → Force Stop → Relaunch.

## Artifact

- `D:\Project Aeolus\dist\Aeolus-v1.0.5.apk`
- SHA-256: `2afdfb434c46581dc05aa49350530796f139c23511f263c1fefca452d3258cfc`
