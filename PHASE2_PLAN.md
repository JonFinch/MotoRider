# Phase 2 — Maps & Offline: Detailed Implementation Plan

## Overview

Phase 2 adds offline capabilities to MotoRider, enabling route planning and navigation without internet connectivity. This is critical for motorcycle touring in remote areas.

**Estimated Timeline:** 4-6 weeks (reduced to ~2 weeks for active features)
**Complexity:** High (requires significant architectural changes)

### Current Status: 2 of 6 features complete

| Feature | Status | Notes |
|---------|--------|-------|
| 2.1 Offline map downloads | ✅ Complete | Core functionality implemented |
| 2.2 Offline routing engine | ❌ Deferred | Not reasonably implementable on Android (400MB-2GB per region, 200-500MB memory) |
| 2.3 Offline address search | ❌ Deferred | Not feasible without offline routing engine |
| 2.4 Map tile caching | ✅ Complete | Uses existing osmdroid features |
| 2.5 Map layer selection | ⏸️ Saved for later | UI-only feature, low priority |
| 2.6 Weather overlay | ⏸️ Saved for later | Requires external API keys, low priority |

---

## 2.1 — Offline Map Downloads (Per Region)

> **STATUS: COMPLETE**

### What's Needed

**Core Functionality:**
- Define downloadable regions (countries, states, custom bounding boxes)
- Download map tiles for specific zoom levels (e.g., 10-16)
- Store tiles locally in SQLite database or file system
- Manage downloaded regions (view, delete, update)
- Storage space estimation before download

**Technical Requirements:**

1. **Tile Storage Backend**
   - Use osmdroid's `MapTileModuleProviderBase` and `MapTileFileStorageProviderBase`
   - SQLite database for tile metadata (region, zoom level, tile coordinates)
   - File system storage for actual tile images (PNG/JPEG)
   - Alternative: Use `SqliteTileSource` from osmdroid for pure SQLite storage

2. **Region Definition**
   - Predefined regions: countries, US states, European countries
   - Custom regions: user draws bounding box on map
   - Store region metadata: name, bounding box (lat/lon), zoom levels, tile count estimate
   - Use `BoundingBox` from osmdroid for region boundaries

3. **Download Manager**
   - Background service for downloading tiles
   - Progress tracking (tiles downloaded / total tiles)
   - Pause/resume capability
   - Retry logic for failed downloads
   - Network connectivity check (WiFi recommended for large downloads)
   - Calculate tile count: `(maxZoom - minZoom + 1) * tilesPerZoom`

4. **Tile Count Estimation**
   - Formula: For each zoom level z, tiles = `(2^z) * (lonRange / 360) * (latRange / 180)`
   - Example: UK at zoom 10-14 ≈ 50,000 tiles ≈ 500MB
   - Show estimated storage before user confirms download

5. **UI Components**
   - `OfflineMapManagerActivity` — main screen for managing offline maps
   - Region list with download status (not downloaded, downloading, downloaded)
   - Map view for custom region selection (draw rectangle)
   - Download progress dialog with cancel button
   - Storage usage display (total used, available per region)

**Implementation Steps:**

1. Create `OfflineRegion` data class:
   ```kotlin
   data class OfflineRegion(
       val id: String,
       val name: String,
       val boundingBox: BoundingBox,
       val minZoom: Int,
       val maxZoom: Int,
       val tileCount: Long,
       val estimatedSizeMB: Double,
       val downloadStatus: DownloadStatus,
       val downloadedAt: Long?
   )
   ```

2. Create `OfflineMapDatabase` (Room database):
   - `OfflineRegionDao` — CRUD operations for regions
   - `TileMetadataDao` — track downloaded tiles
   - Tables: `offline_regions`, `downloaded_tiles`

3. Create `TileDownloadService` (ForegroundService):
   - Download tiles in batches (e.g., 100 at a time)
   - Use osmdroid's `MapTileDownloader` with custom tile source
   - Save to local storage via `MapTileFilesystemProvider`
   - Update progress in notification

4. Create `OfflineMapManagerViewModel`:
   - Manage region list
   - Start/cancel downloads
   - Calculate storage estimates
   - Delete regions

5. Create `OfflineMapManagerScreen` (Compose):
   - List of available regions (predefined + custom)
   - Download buttons with progress indicators
   - Custom region creation (map with rectangle drawing)
   - Storage usage summary

**Dependencies:**
- Room database (already in project via AndroidX)
- osmdroid tile providers (already available)
- Coroutines for background work

**Storage Considerations:**
- Default storage location: `Context.getExternalFilesDir("osmdroid/tiles")`
- Warn user if storage < 500MB available
- Provide option to move to SD card (if available)

---

## 2.2 — Offline Routing Engine (Embedded)

> **STATUS: DEFERRED — Not reasonably implementable**
>
> Offline routing with embedded GraphHopper or OSRM requires 400MB-2GB per region,
> significant memory (200-500MB per loaded graph), and complex C++ JNI bindings for OSRM.
> The storage and memory requirements are prohibitive for a mobile motorcycle navigation app.
> The online routing API (Phase 1) remains the only viable routing solution for now.

**Original plan (kept for reference):**
- GraphHopper 9.x with OSM extracts from Geofabrik
- Custom curvature weighting
- RoutingMode enum: ONLINE/OFFLINE/AUTO
- Expected: 400MB-2GB per region, 200-500MB memory per loaded graph

---

## 2.3 — Offline Address Search

> **STATUS: DEFERRED — Not feasible without offline routing**
>
> Building an offline search index requires parsing OSM data files, which is tightly
> coupled with the offline routing data pipeline. Without an offline routing engine,
> this feature provides limited value since users can't search for destinations they
> can't route to.

**Original plan (kept for reference):**
- Room FTS5 database with Place entities
- OfflineSearchEngine with query support
- Integration with RouteUtils.searchLocations()
- Expected: 50-100MB per region

---

## 2.4 — Map Tile Caching for Frequently Visited Areas

> **STATUS: COMPLETE**

### What's Needed

**Core Functionality:**
- Automatically cache tiles for areas user frequently views
- Configurable cache size (default: 500MB)
- LRU (Least Recently Used) eviction policy
- Pre-cache tiles along planned routes

**Technical Requirements:**

1. **Cache Storage**
   - Use osmdroid's `MapTileFilesystemProvider` (already built-in)
   - Configure cache size via `Configuration.getInstance().setTileFileSystemCacheMaxBytes()`
   - Default location: `Context.getExternalFilesDir("osmdroid/tiles")`

2. **Cache Management**
   - Track tile access time and frequency
   - Evict oldest tiles when cache is full
   - Provide UI to clear cache manually
   - Show cache usage statistics

3. **Route Pre-Caching**
   - When route is planned, download tiles along route corridor
   - Buffer: 5km on each side of route
   - Zoom levels: 12-16 (typical navigation range)
   - Run in background after route calculation

4. **Smart Caching**
   - Track user's frequently viewed areas (bounding boxes)
   - Pre-cache these areas when WiFi available
   - User can mark areas as "always cache"

**Implementation Steps:**

1. Configure osmdroid cache:
   ```kotlin
   val config = Configuration.getInstance()
   config.tileFileSystemCacheMaxBytes = 500L * 1024 * 1024 // 500MB
   config.tileFileSystemCacheMaxBytes = when (userPreference) {
       CacheSize.SMALL -> 200L * 1024 * 1024
       CacheSize.MEDIUM -> 500L * 1024 * 1024
       CacheSize.LARGE -> 1000L * 1024 * 1024
   }
   ```

2. Create `RouteTilePreCache` service:
   ```kotlin
   class RouteTilePreCache(private val context: Context) {
       fun preCacheRoute(route: Route, zoomLevels: IntRange = 12..16) {
           val corridor = calculateCorridor(route, bufferKm = 5.0)
           val tiles = calculateTiles(corridor, zoomLevels)
           
           CoroutineScope(Dispatchers.IO).launch {
               tiles.forEach { tile ->
                   downloadTile(tile)
               }
           }
       }
   }
   ```

3. Create `CacheManagerScreen` (Compose):
   - Show cache usage (used / total)
   - Clear cache button
   - Configure cache size
   - View cached regions (optional)

4. Integrate with route planning:
   - After route calculation, trigger `RouteTilePreCache`
   - Show small notification: "Caching map tiles for route"

**Dependencies:**
- osmdroid built-in caching (already available)

**Storage Considerations:**
- Default: 500MB (user-configurable: 200MB, 500MB, 1GB)
- Shared with offline map downloads

---

## 2.5 — Map Layer Selection (Standard, Satellite, Terrain)

> **STATUS: SAVED FOR LATER**

### What's Needed

**Core Functionality:**
- Switch between different map styles
- Standard: OpenStreetMap (default)
- Satellite: Aerial imagery (if available offline)
- Terrain: Topographic map with elevation contours
- Persist user's preferred layer

**Technical Requirements:**

1. **Tile Sources**
   - Standard: `XYTileSource("Mapnik", ...)` (already configured)
   - Terrain: `XYTileSource("OpenTopoMap", ...)`
   - Satellite: `XYTileSource("EsriWorldImagery", ...)` (online only)
   - Custom: Allow user to add custom tile sources (advanced)

2. **Offline Support**
   - Standard: Available offline (downloaded tiles)
   - Terrain: Available offline (downloaded tiles)
   - Satellite: Online only (large file size, licensing issues)

3. **UI Components**
   - Map layer picker dialog
   - Icons for each layer type
   - Show which layers are available offline
   - Remember last selected layer

**Implementation Steps:**

1. Define tile sources:
   ```kotlin
   object MapTileSources {
       val STANDARD = XYTileSource(
           "Mapnik",
           0, 19, 256, ".png",
           arrayOf("https://tile.openstreetmap.org/")
       )
       
       val TERRAIN = XYTileSource(
           "OpenTopoMap",
           0, 17, 256, ".png",
           arrayOf("https://tile.opentopomap.org/")
       )
       
       val SATELLITE = XYTileSource(
           "EsriWorldImagery",
           0, 18, 256, ".jpg",
           arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
       )
   }
   ```

2. Create `MapLayerSelector` composable:
   ```kotlin
   @Composable
   fun MapLayerSelector(
       currentLayer: MapLayer,
       onLayerSelected: (MapLayer) -> Unit
   ) {
       // Dialog with layer options
       // Show icons and availability status
   }
   ```

3. Add layer switcher button to map screen:
   - Floating action button (bottom right)
   - Tap to open layer selector
   - Show current layer icon

4. Persist selected layer:
   ```kotlin
   var selectedLayer by rememberSaveable { mutableStateOf(MapLayer.STANDARD) }
   
   LaunchedEffect(selectedLayer) {
       mapView.setTileSource(selectedLayer.tileSource)
       dataStore.save(LAYER_KEY, selectedLayer.name)
   }
   ```

5. Update offline download UI:
   - Show which layers are available for offline download
   - Allow user to select layer when downloading region

**Dependencies:**
- osmdroid tile sources (already available)
- Internet connection for online layers

**Licensing Considerations:**
- OpenStreetMap: ODbL license (attribution required)
- OpenTopoMap: CC-BY-SA (attribution required)
- Esri: Terms of service (check commercial use)

---

## 2.6 — Weather Overlay on Map

> **STATUS: SAVED FOR LATER**

### What's Needed

**Core Functionality:**
- Display weather data on map (rain, temperature, wind)
- Real-time weather updates
- Forecast along route (optional)
- Toggle overlay on/off

**Technical Requirements:**

1. **Weather Data Source**
   - OpenWeatherMap API (free tier: 60 calls/minute)
   - RainViewer API (radar overlays, free)
   - Windy API (wind data, paid)
   - Recommendation: Start with OpenWeatherMap + RainViewer

2. **Overlay Types**
   - Precipitation radar (animated)
   - Temperature (color-coded regions)
   - Wind speed/direction (arrows)
   - Weather alerts (severe weather warnings)

3. **Tile-Based Overlays**
   - Use weather map tiles (PNG with transparency)
   - Overlay on top of base map
   - Update every 10-15 minutes
   - Cache tiles for offline use (limited)

4. **Integration**
   - Add overlay toggle to map screen
   - Show legend (color scale)
   - Tap on map to see weather at location

**Implementation Steps:**

1. Create `WeatherService`:
   ```kotlin
   class WeatherService(private val apiKey: String) {
       suspend fun getWeatherOverlay(type: WeatherType): WeatherOverlay {
           // Fetch weather tile URLs from API
           return WeatherOverlay(
               tileUrls = listOf(...),
               opacity = 0.6f,
               lastUpdated = System.currentTimeMillis()
           )
       }
   }
   ```

2. Create `WeatherOverlay` class:
   ```kotlin
   class WeatherOverlay(
       private val tileUrls: Map<TileCoordinates, String>
   ) : TilesOverlay(...) {
       override fun loadTile(tile: MapTile): Drawable? {
           val url = tileUrls[tile.toCoordinates()]
           return downloadAndCacheTile(url)
       }
   }
   ```

3. Add weather toggle to map screen:
   ```kotlin
   var weatherOverlayVisible by remember { mutableStateOf(false) }
   
   LaunchedEffect(weatherOverlayVisible) {
       if (weatherOverlayVisible) {
           val overlay = weatherService.getWeatherOverlay(WeatherType.PRECIPITATION)
           mapView.overlays.add(overlay)
       } else {
           mapView.overlays.removeAll { it is WeatherOverlay }
       }
   }
   ```

4. Create `WeatherLegend` composable:
   - Show color scale for current overlay
   - Update when overlay type changes

5. Add route weather forecast (optional):
   - Fetch weather along route polyline
   - Show forecast at waypoints
   - Display in route info card

**Dependencies:**
- OpenWeatherMap API key (free tier)
- RainViewer API (no key required)
- Internet connection (limited offline support)

**Cost Considerations:**
- Free tier: 60 calls/minute (sufficient for most users)
- Paid tier: $40/month for 6000 calls/minute (if needed)

**Licensing Considerations:**
- Weather data attribution required
- Display "Weather data © OpenWeatherMap" on map

---

## Implementation Order & Dependencies

### Week 1-2: Foundation
1. **Map tile caching** (2.4) — Quick win, uses existing osmdroid features ✅ COMPLETE
2. **Offline map downloads** (2.1) — Core feature, enables other offline features ✅ COMPLETE

### Week 2-3: Deferred
3. ~~**Offline address search** (2.3)~~ — Deferred: not feasible without offline routing

### Week 3-5: Deferred
4. ~~**Offline routing engine** (2.2)~~ — Deferred: not reasonably implementable on Android

### Week 5-6: Saved for Later
5. ~~**Weather overlay** (2.6)~~ — Saved for later
6. **Map layer selection** (2.5) — Saved for later

---

## Storage Requirements Summary (Updated)

| Feature | Storage per Region | Notes |
|---------|-------------------|-------|
| Offline map tiles | 200MB - 2GB | Depends on zoom levels and area size ✅ COMPLETE |
| ~~Offline routing data~~ | ~~400MB - 2GB~~ | ~~Deferred~~ |
| ~~Offline search index~~ | ~~50MB - 100MB~~ | ~~Deferred~~ |
| Map tile cache | 500MB (default) | Shared across all features ✅ COMPLETE |
| Weather overlay | 10MB - 50MB | Cached tiles (limited offline) — saved for later |
| **Total (active features)** | **~700MB - 2.5GB** | Per region, user-configurable |

---

## Testing Strategy

1. **Offline functionality testing:**
   - Disable internet, verify routing works
   - Verify address search works offline
   - Verify map tiles display correctly

2. **Storage management testing:**
   - Download large region, verify no crashes
   - Fill storage to capacity, verify graceful handling
   - Delete regions, verify storage freed

3. **Performance testing:**
   - Route calculation time < 5 seconds
   - Address search results < 1 second
   - Map scrolling smooth (60 FPS)

4. **Edge cases:**
   - Region partially downloaded (network interruption)
   - Routing data corrupted (re-download option)
   - Multiple regions loaded (memory management)

---

## Risks & Mitigations (Updated)

| Risk | Impact | Mitigation |
|------|--------|------------|
| Large storage requirements | Users run out of space | Clear warnings, storage management UI, configurable limits |
| ~~Slow route calculation~~ | ~~Poor UX~~ | ~~N/A — offline routing deferred~~ |
| Outdated map data | Incorrect routes | Auto-update notifications, manual update option |
| ~~GraphHopper complexity~~ | ~~Development delays~~ | ~~N/A — offline routing deferred~~ |
| Weather API costs | Unexpected expenses | Use free tier, cache aggressively, make feature optional — saved for later |

---

## Success Criteria (Updated)

- [x] User can download map tiles for a region (e.g., "Germany") ✅ COMPLETE
- [ ] User can calculate routes offline (same options as online) — *Deferred*
- [ ] User can search addresses offline — *Deferred*
- [x] Map tiles are cached automatically (configurable size) ✅ COMPLETE
- [ ] User can switch between map layers (standard, terrain, satellite) — *Saved for later*
- [ ] Weather overlay displays correctly — *Saved for later*
- [ ] All features work without internet connection — *Partial: maps only, no offline routing*
- [ ] Storage usage is clearly communicated to user
- [ ] Performance is acceptable (route calculation < 5s)

---

*Last updated: 2026-08-08 — Phase 2 status: 2 of 6 features complete (2.1, 2.4)*
