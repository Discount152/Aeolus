# Aeolus

Aeolus is a confidence-first Android weather application that combines deterministic and ensemble forecast data to show not only what weather is expected, but how strongly the available forecast models agree.

## Highlights

- **TODAY** — current conditions, Consensus Confidence, 48-hour hourly strip, daily outlook
- **FORECAST** — 15-day expandable daily cards with model coverage notes
- **MODELS** — ECMWF / ICON / GFS comparison, outliers, ECMWF ensemble summary, stability signals
- **MAP** — Unified precipitation timeline (RainViewer past → NOW → ICON Seamless forecast); selectable map timezones with local wall-clock labels

## Requirements

- Android 15 (API 35)+
- Application ID: `com.discount152.aeolus`
- Version: 1.0.7 (versionCode 8)

## Build

```powershell
$env:ANDROID_HOME = "D:\Android\Sdk"
.\gradlew.bat test lint assembleRelease
```

Signed release APK (after local keystore setup):

`dist/Aeolus-v1.0.7.apk` (prior v1.0.0–v1.0.6 APKs retained alongside)

## Privacy

No analytics, telemetry, accounts, cloud sync, or background location. Coordinates are sent only to weather/geocoding services needed for the request.

## License / attribution

See [ATTRIBUTIONS.md](ATTRIBUTIONS.md), [DATA_SOURCES.md](DATA_SOURCES.md), and [ARCHITECTURE.md](ARCHITECTURE.md).
