# Aeolus 1.0.4 — Radar Architecture Replacement

## Version

- versionName: `1.0.4`
- versionCode: `5`
- Prior APKs preserved: `dist/Aeolus-v1.0.0.apk` … `dist/Aeolus-v1.0.3.apk`

## Architecture replacement

Removed animated XYZ `RasterSource` / `RadarDoubleBuffer` (fixed 320 ms swap, partial tile covers, staging teardown cancels).

Replaced with RainViewer **coordinate-centered complete images**:

`{host}{path}/512/{z}/{lat}/{lon}/2/1_1.png`

Pipeline:

1. Viewport → padded square mercator footprint + zoom (`RadarViewport`)
2. Newest frame downloaded first → shown via `ImageSource` + `RasterLayer`
3. Remaining historical frames with bounded concurrency
4. READY only after full HTTP download + decode
5. Playback iterates READY bitmaps only; loops do not redownload
6. Camera idle debounce; reuse set while padded footprint still covers viewport

≈ **1 request per timestamp per viewport** (not tiles × frames).

## Obsolete pieces removed

- `RadarDoubleBuffer.kt` (+ test)
- XYZ `RadarTiles.tileTemplate` usage / tiled MapOverlayController radar path
- 320 ms `commitSwap` readiness timer
- Per-frame RasterSource destroy/recreate animation

## Tests added

- `RadarViewportTest`
- `RadarImagesTest`
- `RadarFramePipelineTest` (newest-first, failed-frame honesty, cache reuse, timeline)

## Results

- Unit tests: see test report (all passed)
- Lint: 0 errors
- `assembleRelease`: SUCCESS

## Runtime

No emulator/adb on Aeolus-PC. Live coordinate URL probed HTTP 200 (512px Universal Blue). Device QA recommended for playback/pan.

## Artifact

- `D:\Project Aeolus\dist\Aeolus-v1.0.4.apk`
- SHA-256: `1605067b198f681d3a0a5cb3d6f663ef6f66509b3bd54751d932b563a3886d10`
