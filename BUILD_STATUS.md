# Aeolus — Build Status

Updated: 2026-10-23

## Current release

| Item | Value |
|------|-------|
| versionName | `1.0.6` |
| versionCode | `7` |
| Branch | `cursor/aeolus-1.0.6-full-build-36b5` |
| APK | `dist/Aeolus-v1.0.6.apk` |
| Prior APKs | v1.0.0–v1.0.5 preserved ✓ |

## 1.0.6 jobs

| Item | Status |
|------|--------|
| Unified map timeline | Done |
| ICON precip + dry mask | Done |
| Local clock timezone UX | Done |
| Daily Sky field | Done |
| Today→Forecast expand | Done |
| Real-device offline cold-start | **PASS** on `57261FDCQ0011Q` (Pixel 10 Pro XL) |

## Offline root cause

Class **E**: empty offline “live refresh” overwrote LKG. Fixed via coherent-bundle gate.

## QA

- Unit tests: green (incl. coherent-bundle)
- assembleRelease: SUCCESS
- Physical offline + reconnect: PASS
