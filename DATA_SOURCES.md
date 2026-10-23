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

## Radar (Observed map mode)

[RainViewer](https://www.rainviewer.com/) public Weather Maps API:

`https://api.rainviewer.com/public/weather-maps.json`

Historical past frames only (~2 hours). Nowcast frames are not used.

Aeolus downloads 512 px XYZ tiles (`{path}/512/{z}/{x}/{y}/2/1_1.png`, max zoom 7),
stitches a complete mosaic (≤ ~4 tiles/frame), and shows it via MapLibre ImageSource.
MapLibre does not fetch animated radar tiles.

## Forecast precipitation (unified map future segment)

Open-Meteo `/v1/forecast` with coherent `models=icon_seamless` (DWD ICON Seamless)
across the viewport, adaptive batched multi-coordinate sampling, hourly precipitation
for the next 24 hours. Local rate interpolation + dry mask — labeled as forecast, not radar.

## Map basemap

OpenFreeMap dark style via MapLibre:

`https://tiles.openfreemap.org/styles/dark`

OSM data © OpenStreetMap contributors.
