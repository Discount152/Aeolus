# Aeolus — Build Status

Updated: 2026-10-22

## Current release

| Item | Value |
|------|-------|
| versionName | `1.0.1` |
| versionCode | `2` |
| Branch | `cursor/aeolus-1.0.1-qa-repair-36b5` |
| APK | `dist/Aeolus-v1.0.1.apk` |
| Prior APK preserved | `dist/Aeolus-v1.0.0.apk` ✓ |

## 1.0.1 QA repair

| Fix | Status |
|-----|--------|
| ECMWF ensemble | Fixed (URL-encode timezone; IO dispatcher; per-day ensemble map) |
| Radar maxzoom overscale | Fixed (`/256/` tile path + source maxZoom 7) |
| Temp/cloud continuous rasters | Fixed (bilinear → ImageSource) |
| Location dedupe | Fixed (`LocationIdentity`) |
| Offline cache | Fixed (hydrate-first + shared StateFlow) |
| Cross-tab refresh | Fixed (activity-scoped VM + repository weather flow) |
| Partial-provider tests | Added |

## QA

- Unit tests: **43 passed**
- Lint: **0 errors**, 14 warnings
- `assembleRelease`: **SUCCESS**
- Emulator/device: none attached on Aeolus-PC
