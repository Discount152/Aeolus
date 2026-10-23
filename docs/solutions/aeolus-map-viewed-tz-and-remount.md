# Map viewed timezone + Today→Map remount

## Problems

1. Timezone chip showed Amsterdam while the camera was over Anamur / southern Turkey.
2. Today→Map return painted `PREPARING…` even when the previous viewport still had a playable timeline.

## Causes

1. `viewedZoneId` was seeded from `location.timezone` (`Europe/Amsterdam` or pin `auto`) and only resolved when null. `parseZone("auto")` failed, so VIEWED mode fell through to the device zone. Panning never re-resolved.
2. `MapScreen` is a NavHost destination. Leaving the tab disposed `remember` pipelines and nulled `publishedTimeline`. Remount always took the network path.

## Fixes

- Resolve VIEWED TZ from the camera (`resolveViewedIana`). Location IANA is used only when the camera is still on that pin/city. `auto` is rejected. Turkey/NL boxes win over a leftover city.
- Do not apply a distant Open-Meteo `lastKnown` zone. OM upgrade only when the local guess is a longitude offset.
- Keep radar/forecast pipelines + last playable timeline in `MapOverlayRetain` across dispose. Do not blank a ready clock during remount or network reload. `PREPARING…` only when no frame is on screen.

## Verify

```powershell
.\gradlew.bat testDebugUnitTest --tests com.discount152.aeolus.map.MapTimezoneTest --tests com.discount152.aeolus.map.MapCoveredSkipTest
# Pixel: Map over Anamur → chip TRT/Istanbul, not Amsterdam. Today → Map → clock, not PREPARING…
```
