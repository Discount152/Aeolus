# Aeolus 1.0.2 — Second QA Repair Build Report

## Version

- versionName: `1.0.2`
- versionCode: `3`
- Prior artifacts preserved: `dist/Aeolus-v1.0.0.apk`, `dist/Aeolus-v1.0.1.apk`

## Root causes and repairs

### 1. Radar playback flashing (safety-critical)
- **Cause:** `applyRadarFrame` removed the active RasterSource/RasterLayer before recreating the next frame, leaving a visible empty interval (basemap flash) while tiles loaded. Default raster fade amplified the flicker.
- **Fix:** `RadarDoubleBuffer` two-slot active/staging architecture. Active frame stays opaque; staging loads next template; `commitSwap` atomically swaps opacity. `rasterFadeDuration = 0`.

### 2. Temperature map global/climate-like field
- **Cause:** Sample grid used even latitude spacing (not Web Mercator), coarse cache keys, and missing values were filled with the last sample — producing latitudinal banding. Viewport padding/cache identity were weak; no legend.
- **Fix:** `ViewportSampler` pads ~15%, builds mercator-even adaptive grids (12×8 regional), cache key includes geographic extent. Nullable grid interpolation; 256×256 ImageSource update. On-screen temperature legend with °C/°F.

### 3. Cloud map excessive whitening
- **Cause:** Same viewport/stretch issues as temperature; linear alpha mapped low cloud % to visible white; missing values could fill as cloudy.
- **Fix:** Shared viewport pipeline; nonlinear `cloudCoverAlphaFraction` (0–10% transparent → 80–100% strong); missing → transparent; cloud-cover legend 0–100%.

### 4. Current Location failed on TEMP/CLOUD
- **Cause:** Locate relied only on weather ViewModel location changes + a radar-coupled mental model; camera recenter was not map-owned and independent of overlay.
- **Fix:** `locateOnMap()` animates camera + marker for any overlay, preserves layer selection, then triggers post-idle field resample for TEMP/CLOUD while still syncing weather location.

### 5. Offline cache blank after process death
- **Cause:** Hydration path marked cache `stale=true` indiscriminately; process-death verification only round-tripped raw JSON, not the real `ForecastCache` File API. Location id mismatch risk remained.
- **Fix:** Durable `ForecastCache(cacheDir, json)` atomic write; `hydrateFromCache(markStale=false)` by default; `findNearest` on GPS id jitter; failed refresh never clears cache. Real write→new-instance→read test.

### 6. False OFFLINE flash while online
- **Cause:** Today treated `bundle.stale` / cached source as offline, including during online refresh after hydrate.
- **Fix:** Explicit `ConnectivityPresentation`: LIVE / REFRESHING_WITH_CACHE / OFFLINE_WITH_CACHE / ERROR_NO_CACHE. Refreshing shows `UPDATING…`, not OFFLINE.

## Tests added/updated

- `RadarDoubleBufferTest`
- `ViewportSamplerTest`
- `FieldRasterInterpolatorTest` (nonlinear cloud alpha, nullable bilinear)
- `ForecastCacheRoundTripTest` (real ForecastCache process-death simulation)
- `ConnectivityPresentationTest`

## Full test result

`.\gradlew.bat :app:testDebugUnitTest` → **56 tests, 0 failures, 0 errors**.

## Lint result

`.\gradlew.bat lintDebug` → **PASS** (0 errors, 16 warnings — dependency/version/icon suggestions).

## Release-build result

`.\gradlew.bat assembleRelease` → **SUCCESS** (signed).

## Runtime map / process-death testing

No emulator AVD / no adb device on Aeolus-PC this pass. Map double-buffer, viewport sampling, and connectivity semantics verified via unit tests + code review against the QA matrix. Device QA of `Aeolus-v1.0.2.apk` still required for photosensitive flash visual confirmation and airplane-mode force-stop.

## Artifacts

- `D:\Project Aeolus\dist\Aeolus-v1.0.2.apk`
- SHA-256: `a13bbfcc82ea60f6786ea43cc4195df39aaf9e769185aa0d277825f1564b7dec`
- `dist/SHA256.txt` updated
- `dist/Aeolus-v1.0.0.apk` and `dist/Aeolus-v1.0.1.apk` preserved

## Environment

- JDK Temurin 21, Android SDK API 35, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21
