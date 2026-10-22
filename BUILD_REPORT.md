# Aeolus 1.0 — Build Report

## Detected build environment

| Component | Value |
|-----------|-------|
| Host | Aeolus-PC (Windows 10 10.0.19045) |
| Repository | `D:\Project Aeolus` → `https://github.com/Discount152/Aeolus.git` |
| Branch | `cursor/aeolus-1.0-full-build-36b5` |
| JDK | Eclipse Temurin 21.0.10 (`JAVA_HOME`) |
| Android SDK | `D:\Android\Sdk` |
| Platform | Android API 35 |
| Build-tools | 35.0.0 |
| Gradle | 8.11.1 (wrapper) |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.10.01 |
| MapLibre | org.maplibre.gl:android-sdk:11.8.0 |

## Completed features

- App id `com.discount152.aeolus`, name Aeolus, version 1.0.0 / versionCode 1, minSdk 35
- Phosphor-terminal dark UI with TODAY / FORECAST / MODELS / MAP + Settings
- Open-Meteo ECMWF / ICON / GFS deterministic forecasts
- ECMWF ensemble analysis with dynamic member counts
- Robust median consensus + MAD outlier detection
- Consensus Confidence (weights renormalized without stability history)
- Forecast snapshot persistence + stability signals
- Location search, device location (foreground), map pin selection, saved locations
- Units (°C/°F, wind, precip, distance) via DataStore
- Offline/stale cached forecast labeling
- MapLibre + OpenFreeMap basemap; RainViewer radar play/scrub/opacity; Open-Meteo temp/cloud sample overlays with failure isolation

## Tests executed

```text
.\gradlew.bat test
```

| Suite | Tests | Failures | Errors |
|-------|-------|----------|--------|
| UnitConverterTest | 4 | 0 | 0 |
| ConsensusEngineTest | 3 | 0 | 0 |
| ConfidenceEngineTest | 6 | 0 | 0 |
| EnsembleAnalyzerTest | 2 | 0 | 0 |
| StabilityAnalyzerTest | 2 | 0 | 0 |
| TimezoneGroupingTest | 3 | 0 | 0 |
| **Total** | **20** | **0** | **0** |

(Same suites executed for debug and release unit-test variants.)

## Lint result

```text
.\gradlew.bat lint
```

**PASS** — 0 errors, 14 warnings (mostly “newer dependency version available”).

Report: `app/build/reports/lint-results-debug.html`

## Release-build result

```text
.\gradlew.bat assembleRelease
```

**SUCCESS** — R8 minify + resource shrink enabled; APK signed with local Aeolus release keystore.

## Runtime / emulator / device testing

**Not performed.** `adb devices` empty; `avdmanager list avd` empty. No physical device attached. Compile, unit tests, lint, and signed release packaging completed.

## Limitations

- No emulator/device smoke test on this machine
- Weather conditions shown as WMO text labels (Meteocons asset pack not bundled in v1)
- Temperature/cloud map layers are Open-Meteo sample grids, not meteorological rasters
- Default location Amsterdam until search/device/map selection
- GitHub push may be unavailable depending on local credentials

## Final APK

Path: `D:\Project Aeolus\dist\Aeolus-v1.0.0.apk`

SHA-256: `7ea078444213f8cc1f460a72cf338cf9f71cba409d0fe9c66772e845190c1685`

Also recorded in `dist/SHA256.txt`.

## Keystore

Local (gitignored):

- `keystore/aeolus-release.jks`
- `keystore/signing.properties`

Preserve for future Aeolus releases. Never commit.

## Git status

Local commits on `cursor/aeolus-1.0-full-build-36b5`. Push attempted if auth works; otherwise commits remain local awaiting push.
