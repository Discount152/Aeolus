# Aeolus 1.0.3 — Location, Cache, and Map Simplification

## Version

- versionName: `1.0.3`
- versionCode: `4`
- Prior artifacts preserved: `dist/Aeolus-v1.0.0.apk` … `dist/Aeolus-v1.0.2.apk`

## Root causes

### 1. Stuck / undeletable saved locations
- **Cause:** `selectLocation(persist=true)` auto-upserted every active location (map pins, device GPS, first-launch Amsterdam fallback) into Saved Locations. Fallback used `default:amsterdam` / derived meta ids that felt “built-in”. Delete-by-id could disagree with re-canonicalized ids; deleting the active item did not reassign active location, and re-select paths could re-introduce entries.
- **Fix:** Separate **active location** (DataStore JSON) from **saved list**. Search / map pin / Current Location / fallback set active only. Explicit **SAVE** adds to saved. Migration normalizes ids and strips auto-persisted fallback Amsterdam. Remove matches by stable id and reassigns active location.

### 2. Offline cold-start cache miss
- **Cause:** Cache write used volatile `location.id` (unrounded `device:lat,lon` from LocationService) while cold-start lookup used a different saved/selected id (rounded or Amsterdam fallback when active was not durably stored). Startup also preferred fallback before finishing location resolution, so hydrate ran against the wrong key. Exact-id `cache.read` on failure did not always align with the write key.
- **Fix:** Single `LocationIdentity.stableId` / `cacheKey` (geo provider id or `coord:%.3f,%.3f` / `fallback:amsterdam`). Persist full active location. Write+hydrate always use `cacheKey`. `StartupCoordinator` cold-start order: prefs → active → permission/device/fallback → hydrate → refresh. Failed network retains hydrated state.

### 3. Cache-key / location identity mismatch
- **Yes — primary contributor.** Device/map/default id formats diverged across write vs restart. Unified stable identity removes that class of miss; `StartupCoordinatorTest` proves restore via the production key.

### 4. Amsterdam-first flash
- **Cause:** Bootstrap fell through to `DEFAULT_LOCATION` before permission/device resolution and could persist it.
- **Fix:** Permission → device → fallback. While awaiting permission, UI stays loading without publishing Amsterdam. Fallback is ephemeral (not auto-saved).

## Map simplification

- Removed Temperature and Clouds map layers, legends, viewport sampling, and field raster pipeline.
- MAP is radar-only (playback, scrubber, opacity, locate, map pin → active location).

## Tests added

- `StartupCoordinatorTest` (full cold-start offline / OFFLINE_WITH_CACHE)
- `SavedLocationMigrationTest`
- Expanded `LocationIdentityTest` (stable cache keys)

## Results

- Unit tests: **52 passed**, 0 failed  
- Lint: **0 errors** (warnings only)  
- `assembleRelease`: **SUCCESS**

## Runtime

No emulator/adb on Aeolus-PC this pass. Device QA still required for permission dialog UX and force-stop offline confirmation.

## Artifact

- `D:\Project Aeolus\dist\Aeolus-v1.0.3.apk`
- SHA-256: `12e8929739ffd38e904dc98644fea197e6c76e361844f42405f7b74d77027bd3`
