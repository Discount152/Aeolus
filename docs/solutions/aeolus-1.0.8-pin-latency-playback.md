# Aeolus 1.0.8 — pin UNAVAILABLE, map latency, playback fps

## Problems

1. Map pins → Today `WEATHER DATA TEMPORARILY UNAVAILABLE` while city search worked.
2. Map load ~15s on device (prior ~2–4s).
3. Play advanced ~8+ fps; pan did not auto-resume.
4. Basemap attribution overlapped Play.

## Causes

1. Ephemeral pin + no cache + Open-Meteo stampede (map multi-batch + 3 models) → 429/empty → unavailable; pin TZ borrowed viewport/system.
2. Blocking `ViewedTimezoneResolver` on 429 before overlays; sequential radar→forecast; oversized sample grid; empty “covered” skip.
3. `LaunchedEffect(..., selectedInstant)` relaunched playback every frame.
4. Independent `BottomStart` attribution with `bottom = 118.dp`.

## Fixes

- Pin: `timezone=auto`, ICON-first ephemeral fetch, stable `coord:` id.
- Map: parallel pipelines, ≤2 batches, non-blocking TZ, radar-first publish, frames required for covered-skip.
- Playback: one clock @ 650/1000/750 ms; AUTO_PAUSED resume on playable gen.
- Attribution inside bottom chrome footer.

## Verify

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
adb -s 57261FDCQ0011Q install -r dist\Aeolus-v1.0.8.apk
# logcat -s MapLoad LastKnownGood
```
