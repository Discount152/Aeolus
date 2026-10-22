# Data Sources

## Forecast gateway

[Open-Meteo](https://open-meteo.com/) — no API key for non-commercial personal use.

### Deterministic models (`/v1/forecast`)

| Display | Open-Meteo model id |
|---------|---------------------|
| ECMWF | `ecmwf_ifs025` |
| ICON | `icon_seamless` |
| GFS | `gfs_seamless` |

### Ensemble (`ensemble-api.open-meteo.com/v1/ensemble`)

- ECMWF ensemble: `ecmwf_ifs025`
- Member count is derived dynamically from returned `*_memberNN` fields
- Precipitation member threshold constant: `5.0 mm` (`ConfidenceEngine.PRECIP_MEMBER_THRESHOLD_MM`)

### Geocoding

`https://geocoding-api.open-meteo.com/v1/search`

## Radar

[RainViewer](https://www.rainviewer.com/) public Weather Maps API:

`https://api.rainviewer.com/public/weather-maps.json`

Historical past frames only (~2 hours). Nowcast frames are not used.

## Map basemap

OpenFreeMap dark style via MapLibre:

`https://tiles.openfreemap.org/styles/dark`

OSM data © OpenStreetMap contributors.

## Temperature / cloud map layers

Generated from Open-Meteo current conditions sampled on a viewport grid after camera idle (no keyed tile provider).
