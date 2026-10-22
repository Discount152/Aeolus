# Aeolus — Build Status

Updated: 2026-10-22

## Current release

| Item | Value |
|------|-------|
| versionName | `1.0.2` |
| versionCode | `3` |
| Branch | `cursor/aeolus-1.0.2-qa-repair-36b5` |
| APK | `dist/Aeolus-v1.0.2.apk` |
| Prior APKs preserved | `dist/Aeolus-v1.0.0.apk`, `dist/Aeolus-v1.0.1.apk` ✓ |

## 1.0.2 second QA repair

| Fix | Status |
|-----|--------|
| Radar double-buffer (no flash) | Fixed (`RadarDoubleBuffer`, fade 0) |
| Temp viewport + legend | Fixed (`ViewportSampler` + legends) |
| Cloud nonlinear alpha + legend | Fixed |
| Overlay-independent locate | Fixed (`locateOnMap`) |
| Process-death durable cache | Fixed (`ForecastCache` File API + test) |
| LIVE/REFRESHING/OFFLINE states | Fixed (`ConnectivityPresentation`) |

## 1.0.1 regressions (must stay fixed)

| Fix | Status |
|-----|--------|
| ECMWF ensemble | Preserved |
| Location dedupe | Preserved |
| Cross-tab refresh | Preserved |
| Units | Preserved |
| Radar `/256/` maxZoom 7 | Preserved |

## QA

- Unit tests: **56 passed**
- Lint: **0 errors**, 16 warnings
- assembleRelease: **SUCCESS**
