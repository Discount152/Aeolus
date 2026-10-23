# Aeolus Architecture

## Stack

- Kotlin, Jetpack Compose, Material 3
- Navigation Compose
- Coroutines + StateFlow
- Retrofit / OkHttp / kotlinx.serialization
- DataStore (settings) + file JSON cache (forecasts / snapshots)
- Manual DI via `AppContainer`
- MapLibre Native (`AndroidView`)

## Packages

| Area | Path |
|------|------|
| Network | `data/network` |
| Cache | `data/cache` |
| Repository | `data/repository` |
| Settings | `data/prefs` |
| Domain models | `domain/models` |
| Consensus | `domain/consensus` |
| Confidence | `domain/confidence` |
| Units / ensemble / stability | `domain/units`, `domain/weather` |
| Location | `location` |
| Map overlays | `map` |
| UI | `ui/today`, `ui/forecast`, `ui/models`, `ui/map`, `ui/settings` |

## Data flow

1. `WeatherViewModel` selects a `GeoLocation` (search, device, map pin, or default Amsterdam).
2. `WeatherRepository` fetches ECMWF / ICON / GFS deterministic forecasts in parallel and ECMWF ensemble JSON.
3. `ConsensusEngine` builds robust median consensus; outliers use MAD.
4. `ConfidenceEngine` scores Consensus Confidence (deterministic agreement, ensemble, lead time, optional stability).
5. Results are cached atomically; snapshots feed stability analysis on later refreshes.
6. UI screens observe `WeatherUiState` and render phosphor-terminal presentation.

## Map overlays

Overlays fail independently:

- Radar → RainViewer historical frames
- Temperature / clouds → Open-Meteo viewport grid samples on camera idle
