# Map world-bounds covered-skip and AUTO resume

## Problems

1. First Map style load used MapLibre world bounds (lats ±85 / invalid >90). That footprint was cached and later `coversVisible` every city view, so RainViewer stayed on a coarse mosaic and forecast sampled world-scale points.
2. AUTO pause after pan often never resumed: reload nulled the timeline (play loop STOPped) and the resume effect required `publishedGen == mapGeneration`.

## Causes

1. `reloadOverlays` ran in `setStyle` before the location camera settled. Forecast padding did not clamp lats. Covered-skip checked box containment, not zoom/span adequacy.
2. Play loop treated a brief null timeline as terminal STOP. Resume effect bailed when generation moved during reload.

## Fixes

- Skip overlay reload when visible bounds are unusable (NaN, outside Mercator, lat span > 70°).
- Radar cover requires `zoom >= desiredZoom`. Forecast cover rejects a world-scale raster over a city span.
- Clamp padded forecast lats to ±85.05.
- Retry map forecast after core-in-flight / gate cooldown yield.
- Keep the play loop across a null timeline; resume AUTO after settle without a gen equality gate.
- Log `first_visible_frame`, `reload_skip_invalid_bounds`, `auto_resume`.

## Verify

```powershell
.\gradlew.bat testDebugUnitTest --tests com.discount152.aeolus.map.*
adb -s 57261FDCQ0011Q install -r app\build\outputs\apk\debug\app-debug.apk
# logcat -s MapLoad: first_visible_frame, radar_done, timeline_publish, auto_resume
```

Pixel `57261FDCQ0011Q` run `qa-device/runs/20261023-230136/`.
