# Phase 2 — Maps & Offline: Detailed Implementation Plan

## Overview

Phase 2 adds offline capabilities to MotoRider, enabling route planning and navigation without internet connectivity. This is critical for motorcycle touring in remote areas.

**Estimated Timeline:** 4-6 weeks
**Complexity:** High (requires significant architectural changes)

---

## 2.1 — Offline Map Downloads (Per Region)

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

### What's Needed

**Core Functionality:**
- Calculate routes without internet connection
- Support same routing options as online (curvature, avoidances)
- Fast route calculation (< 5 seconds for 100km route)
- Use downloaded OSM data for routing

**Technical Requirements:**

1. **Routing Engine Choice**
   - **GraphHopper** (recommended): Pure Java, Android-optimized, supports offline routing
   - Alternative: OSRM embedded (C++ via JNI, more complex)
   - GraphHopper advantages: Well-documented, active development, Android examples available
   - GraphHopper version: 9.x (latest stable)

2. **Routing Data Files**
   - Download OSM extracts per region (e.g., from Geofabrik)
   - Convert to GraphHopper format (`.ghz` files)
   - File size: ~10-20% of original OSM file
   - Example: Germany OSM ≈ 3GB → GraphHopper ≈ 400MB

3. **Route Calculation**
   - Load routing graph into memory (lazy loading per region)
   - Configure vehicle profile (motorcycle, car, bike)
   - Apply weighting (curvature preference, avoidances)
   - Return route geometry (list of coordinates)

4. **Curvature Support**
   - GraphHopper supports custom weightings
   - Implement `CurvatureWeighting` class
   - Prefer roads with high curvature (based on OSM tags or pre-calculated data)
   - Alternative: Use "priority" instead of "weight" for curvature

5. **Avoidance Support**
   - Map avoidances to GraphHopper's `FlagEncoder` options:
     - `highway` → `motorway` tag filtering
     - `toll` → `toll` tag filtering
     - `ferry` → `ferry` tag filtering
     - `unpaved` → `surface` tag filtering
     - `narrow` → `width` or `lanes` tag filtering

6. **Integration with Existing Code**
   - Modify `RouteService` to support offline mode
   - Add `RoutingMode` enum: `ONLINE`, `OFFLINE`, `AUTO`
   - `AUTO` mode: Try offline first, fall back to online if region not downloaded
   - Return same `Route` data class (compatible with existing UI)

**Implementation Steps:**

1. Add GraphHopper dependency to `app/build.gradle`:
   ```gradle
   implementation 'com.graphhopper:graphhopper-core:9.1'
   implementation 'com.graphhopper:graphhopper-reader-osm:9.1'
   ```

2. Create `OfflineRoutingEngine` class:
   ```kotlin
   class OfflineRoutingEngine(context: Context) {
       private var hopper: GraphHopper? = null
       
       fun loadRegion(regionId: String) {
           // Load .ghz file for region
           hopper = GraphHopper().apply {
               setOSMFile(getOsmFilePath(regionId))
               setGraphHopperLocation(getGraphHopperLocation(regionId))
               setProfiles(createMotorcycleProfile())
               load()
           }
       }
       
       fun calculateRoute(
           start: GeoPoint,
           end: GeoPoint,
           waypoints: List<GeoPoint>,
           preferences: RoutingPreferences
       ): Route? {
           val request = GHRequest(start.latitude, start.longitude,
                                   end.latitude, end.longitude)
               .setProfile("motorcycle")
               .setAlgorithm(Parameters.Algorithms.DIJKSTRA_BI)
           
           // Apply curvature weighting
           if (preferences.curvature != RouteType.DIRECT) {
               request.putHint("curvature", preferences.curvature.name)
           }
           
           val response = hopper?.route(request)
           return response?.best?.toMotoRiderRoute()
       }
   }
   ```

3. Create `RoutingDataDownloader` service:
   - Download OSM extracts from Geofabrik API
   - Convert to GraphHopper format using `GraphHopperOSM`
   - Store in app-specific directory
   - Show progress (download + conversion)

4. Create custom `CurvatureWeighting`:
   ```kotlin
   class CurvatureWeighting(private val curvatureLevel: Int) : Weighting {
       override fun calcWeight(edge: EdgeIteratorState, reverse: Boolean, prevOrNextEdge: EdgeIteratorState): Double {
           val baseWeight = edge.getDistance()
           val curvature = edge.get(Curvature.KEY) ?: 1.0
           return when (curvatureLevel) {
               1 -> baseWeight * (1.0 / curvature) // Prefer curvy
               2 -> baseWeight * (1.0 / (curvature * curvature)) // Extra curvy
               else -> baseWeight // Direct
           }
       }
   }
   ```

5. Modify `RouteService` to support offline mode:
   ```kotlin
   suspend fun calculateRouteAsync(...): List<Route> {
       return when (routingMode) {
           RoutingMode.OFFLINE -> offlineEngine.calculateRoute(...)
           RoutingMode.ONLINE -> calculateOnlineRoute(...)
           RoutingMode.AUTO -> {
               offlineEngine.calculateRoute(...) ?: calculateOnlineRoute(...)
           }
       }
   }
   ```

6. Create `RoutingDataManager` UI:
   - List of available routing data files
   - Download/delete buttons
   - Storage usage display
   - Integration with `OfflineMapManagerScreen`

**Dependencies:**
- GraphHopper core library
- OSM data files (Geofabrik downloads)
- Significant storage (400MB-2GB per region)

**Performance Considerations:**
- Load routing graph on-demand (don't keep all regions in memory)
- Use LRU cache for recently used regions
- Route calculation: 1-5 seconds for typical routes
- Memory usage: 200-500MB for loaded graph

---

## 2.3 — Offline Address Search

### What's Needed

**Core Functionality:**
- Search for addresses without internet
- Use downloaded OSM data for place names
- Fast search results (< 1 second)
- Support partial matches and typos

**Technical Requirements:**

1. **Search Index**
   - Build SQLite FTS (Full Text Search) index from OSM data
   - Index place names, addresses, POIs
   - Store coordinates with each entry
   - Index size: ~50-100MB per region

2. **Search Algorithm**
   - Use SQLite FTS5 for fast text search
   - Support prefix matching (e.g., "Lond" → "London")
   - Fuzzy matching for typos (Levenshtein distance)
   - Rank results by:
     - Exact match > prefix match > fuzzy match
     - Proximity to current location
     - Population/importance (if available)

3. **Data Source**
   - Extract place names from OSM data during routing graph creation
   - Parse OSM tags: `name`, `addr:street`, `addr:city`, `addr:housenumber`
   - Include POIs: restaurants, gas stations, hotels, etc.
   - Store in separate SQLite database per region

4. **Integration**
   - Modify `RouteUtils.searchLocations()` to support offline mode
   - Add `SearchMode` enum: `ONLINE`, `OFFLINE`, `AUTO`
   - Return same `SearchResult` data class

**Implementation Steps:**

1. Create `OfflineSearchDatabase` (Room):
   ```kotlin
   @Database(entities = [Place::class], version = 1)
   abstract class OfflineSearchDatabase : RoomDatabase() {
       abstract fun placeDao(): PlaceDao
   }
   
   @Entity(tableName = "places")
   @Fts4
   data class Place(
       @PrimaryKey val id: String,
       val name: String,
       val type: String, // city, street, poi, etc.
       val latitude: Double,
       val longitude: Double,
       val regionId: String
   )
   ```

2. Create `OfflineSearchIndexBuilder`:
   - Parse OSM data file (`.osm.pbf` or `.osm`)
   - Extract place names and coordinates
   - Insert into FTS database
   - Run during routing data download

3. Create `OfflineSearchEngine`:
   ```kotlin
   class OfflineSearchEngine(private val db: OfflineSearchDatabase) {
       fun search(query: String, regionId: String, limit: Int = 10): List<Place> {
           return db.placeDao().searchPlaces(query, regionId, limit)
       }
   }
   ```

4. Modify `RouteUtils.searchLocations()`:
   ```kotlin
   suspend fun searchLocations(query: String, searchMode: SearchMode): List<SearchResult> {
       return when (searchMode) {
           SearchMode.OFFLINE -> offlineEngine.search(query, currentRegion)
           SearchMode.ONLINE -> searchOnline(query)
           SearchMode.AUTO -> {
               val offlineResults = offlineEngine.search(query, currentRegion)
               if (offlineResults.isNotEmpty()) offlineResults
               else searchOnline(query)
           }
       }
   }
   ```

5. Update autocomplete UI:
   - Show indicator when using offline search
   - No changes to UI logic (same data class)

**Dependencies:**
- Room FTS extension
- OSM data parsing library (osmosis or osm4j)

**Storage Considerations:**
- Search index: 50-100MB per region
- Can be combined with routing data storage

---

## 2.4 — Map Tile Caching for Frequently Visited Areas

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
1. **Map tile caching** (2.4) — Quick win, uses existing osmdroid features
2. **Map layer selection** (2.5) — Simple UI, no backend changes

### Week 2-3: Offline Maps
3. **Offline map downloads** (2.1) — Core feature, enables other offline features
4. **Offline address search** (2.3) — Depends on offline map data

### Week 3-5: Offline Routing
5. **Offline routing engine** (2.2) — Most complex, requires GraphHopper integration
6. Test extensively with real-world routes

### Week 5-6: Advanced Features
7. **Weather overlay** (2.6) — Optional, can be deferred to Phase 9

---

## Storage Requirements Summary

| Feature | Storage per Region | Notes |
|---------|-------------------|-------|
| Offline map tiles | 200MB - 2GB | Depends on zoom levels and area size |
| Offline routing data | 400MB - 2GB | GraphHopper graph files |
| Offline search index | 50MB - 100MB | FTS database |
| Map tile cache | 500MB (default) | Shared across all features |
| Weather overlay | 10MB - 50MB | Cached tiles (limited offline) |
| **Total** | **1.2GB - 4.5GB** | Per region, user-configurable |

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

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Large storage requirements | Users run out of space | Clear warnings, storage management UI, configurable limits |
| Slow route calculation | Poor UX | Optimize GraphHopper settings, show progress indicator |
| Outdated map data | Incorrect routes | Auto-update notifications, manual update option |
| GraphHopper complexity | Development delays | Start with basic integration, add features incrementally |
| Weather API costs | Unexpected expenses | Use free tier, cache aggressively, make feature optional |

---

## Success Criteria

- [ ] User can download map tiles for a region (e.g., "Germany")
- [ ] User can calculate routes offline (same options as online)
- [ ] User can search addresses offline
- [ ] Map tiles are cached automatically (configurable size)
- [ ] User can switch between map layers (standard, terrain, satellite)
- [ ] Weather overlay displays correctly (optional)
- [ ] All features work without internet connection
- [ ] Storage usage is clearly communicated to user
- [ ] Performance is acceptable (route calculation < 5s)

---

*Last updated: 2026-08-02*
