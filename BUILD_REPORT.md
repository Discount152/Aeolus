# Aeolus 1.0.1 — QA Repair Build Report

## Version

- versionName: `1.0.1`
- versionCode: `2`
- Prior artifact preserved: `dist/Aeolus-v1.0.0.apk`

## Root causes (manual QA failures)

### 1. ECMWF ensemble unavailable
- **Cause:** Ensemble request built timezone as a raw query fragment (`Europe/Amsterdam`). The `/` truncated/broke the URL. Failures were swallowed (`getOrNull`). Blocking OkHttp also ran off an unconstrained dispatcher from the UI scope. Models UI only bound `bundle.ensemble` for the first forecast day.
- **Fix:** `HttpUrl` query encoding; `Dispatchers.IO`; parse/store `ensembleByDate` for all days; Models reads `ensembleByDate[selectedDate]`.

### 2. Offline cache blank after restart
- **Cause:** Startup always force-refreshed without hydrating disk cache first; nav-destination-scoped ViewModels did not share restored state; null confidence rendered as `0% VERY_LOW`.
- **Fix:** `hydrateFromCache` before network; repository `StateFlow` as SoT; activity-scoped ViewModel; ConfidenceBadge shows `—` when confidence is null; failed refresh never replaces valid cache.

### 3. Cross-tab refresh
- **Cause:** Each Compose destination created its own `WeatherViewModel` via nav back-stack `viewModel()`.
- **Fix:** `rememberWeatherViewModel()` uses `ComponentActivity` store + repository `weather` flow shared by Today/Forecast/Models.

### 4. Radar “zoom level not supported”
- **Cause:** RainViewer tile URL omitted `/256/`; overscale relied on maxZoom alone with an incorrect template.
- **Fix:** `RadarTiles.tileTemplate` → `{host}{path}/256/{z}/{x}/{y}/…` with TileSet `maxZoom = 7` so MapLibre overscales beyond source zoom.

### 5. Temperature/cloud dot grids
- **Cause:** CircleLayer sample markers.
- **Fix:** Shared `FieldRasterInterpolator` (bilinear) → georeferenced `ImageSource` raster layer; no sample dots.

### 6. Saved-location duplicates
- **Cause:** Append-only save by volatile ids (`device:lat,lon`).
- **Fix:** `LocationIdentity` canonical geocoding ids, GPS tolerance, startup dedupe migration.

## Subsystems / files changed (high level)

- `data/repository/WeatherRepository.kt`, `data/network/ForecastMapper.kt`, `data/cache/ForecastCache.kt`
- `location/LocationIdentity.kt`, `ui/state/WeatherViewModel.kt`, `RememberWeatherViewModel.kt`
- `map/RadarTiles.kt`, `map/FieldRasterInterpolator.kt`, `ui/map/MapScreen.kt`
- `ui/models/ModelsScreen.kt`, `ui/components/WeatherComponents.kt`
- `domain/models/WeatherModels.kt` (`ensembleByDate`)
- Tests under `app/src/test/...` + fixtures in `app/src/test/resources/`
- `app/build.gradle.kts` version bump

## Tests added

- `EnsembleFixtureTest` (Amsterdam + Turkey fixtures, URL encoding)
- `LocationIdentityTest`
- `RadarTilesTest`
- `FieldRasterInterpolatorTest`
- `PartialProviderFailureTest`
- `ForecastCacheRoundTripTest`
- `SharedWeatherStateTest`

## Full test result

`.\gradlew.bat test` → **43 tests, 0 failures, 0 errors** (debug unit suite).

## Lint result

`.\gradlew.bat lint` → **PASS** (0 errors, 14 warnings — dependency version suggestions).

## Release-build result

`.\gradlew.bat assembleRelease` → **SUCCESS** (signed).

## Runtime testing

No emulator AVD / no adb device on Aeolus-PC. Runtime verification of ensemble/radar/rasters/offline left to device QA of this APK.

## Artifacts

- `D:\Project Aeolus\dist\Aeolus-v1.0.1.apk`
- SHA-256: `d797a82cd2afb542d251242144e1ff76d78e610a326ce3d5403ba65e96ff60ac`
- `dist/SHA256.txt` updated for 1.0.1
- `dist/Aeolus-v1.0.0.apk` preserved

## Environment

- JDK Temurin 21, Android SDK API 35, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21
