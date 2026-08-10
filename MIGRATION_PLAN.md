# MotoRider Migration Plan: OSRM → Local MapRouting API

> **STATUS: DONE.** This migration is complete and the document is kept as a record
> of what changed and why. The "Implementation Order" near the end is history, not a
> to-do list. Verified against the code: no OSRM references remain, `RouteType` no
> longer carries vehicle types (MOTORCYCLE/TRUCK/CAR/BIKE), `parseOsrmRoutes` and
> the other deprecated helpers are gone, and `ApiConfig` supplies the base URL from
> `BuildConfig`.
>
> One later change worth noting against the risk table at the foot of this document:
> Nominatim rate-limiting was listed as "optional: could add throttle/delay". A
> throttle now exists — `RouteUtils.throttleNominatim` holds one request per second
> and sends a contact in the User-Agent, as Nominatim's usage policy requires.

## API Summary

| Service | Port | Relevant Endpoint |
|---------|------|-------------------|
| **Routing API** | 8080 | `POST /route` — curviness-aware routing (replaces OSRM) |
| Curvature API | 8000 | Not directly used by the app (curvature data embedded in routing response) |
| GraphHopper | 8989 | Internal — Routing API calls this, app does not |

### New Request Format (POST localhost:8080/route)
```json
{
  "start": { "lat": 51.5074, "lon": -0.1278 },
  "end": { "lat": 50.7120, "lon": -7.4120 },
  "waypoints": [{ "lat": 51.4545, "lon": -2.5879 }],
  "curviness": "curvy",
  "avoidances": ["motorway"]
}
```

### New Response Format
```json
{
  "success": true,
  "curviness_level": "curvy",
  "routes": [{
    "index": 0,
    "score": 0.3,
    "distance": 537600.5,
    "duration": 34200.0,
    "geometry": [[lon, lat], ...],
    "curvature_metadata": {
      "total_curvature": 2450.5,
      "curvature_per_km": 4.56,
      "curviest_segment_way_id": 123456,
      "curviest_segment_curvature": 850.3,
      "segments_analyzed": 45,
      "segments_with_curvature": 38
    }
  }],
  "best_route_index": 0,
  "message": "Found 3 routes. Best route selected based on 'curvy' preference."
}
```

### Key Format Differences from OSRM

| Aspect | OSRM (old) | Routing API (new) |
|--------|-----------|-------------------|
| Method | GET | POST |
| Coords | In URL: `lon,lat;lon,lat;...` | In JSON body: `start`/`end`/`waypoints` objects |
| Geometry | `geometry: { type, coordinates: [[lon,lat]] }` | `geometry: [[lon, lat]]` (flat array, NO coordinates wrapper) |
| Success check | `code == "Ok"` | `success == true` |
| Error body | `{ code: "NoRoute", message: "..." }` | `{ detail: "message" }` |
| Alternatives | `alternatives=true` param | Always returned (multiple routes in array by default) |
| Curvature | Client-side calculation | Server-provided `curvature_metadata` per route |
| Avoidances | `exclude=motorway,toll` in query string | `"avoidances": ["motorway", "toll"]` in JSON body |
| Elevation | Client-side calculation (from waypoint elevations) | Not provided. Elevation data is not in the response. |

---

## Change #1: NEW FILE — `config/ApiConfig.kt`

**File:** `app/src/main/java/com/motorider/config/ApiConfig.kt`

Centralized configuration for all API URLs. No hardcoded URLs anywhere else.

```kotlin
package com.motorider.config

object ApiConfig {
    const val ROUTING_API_BASE_URL = "http://localhost:8080"
    const val CURVATURE_API_BASE_URL = "http://localhost:8000"
    const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
    const val TILE_SERVER_BASE_URL = "https://tile.openstreetmap.org"
}
```

**Rationale:** All runtime URLs in one file. Easy to change per environment (dev/staging/prod). Fixes review issue about scattered hardcoded URLs.

---

## Change #2: `models/RouteType.kt`

**Changes:**
1. **Remove** `MOTORCYCLE`, `TRUCK`, `CAR`, `BIKE` enum values. They are vehicle types from the old OSRM system. The new API always uses a motorcycle-optimized GraphHopper profile. The UI only ever used `DIRECT`, `FAST`, `CURVY`, `EXTRA_CURVY` (confirmed: PlanPanel defaults to CURVY, PreferenceDialog lists only those four, LegChip handles only those four visually).
2. **Add** `apiValue: String` property mapping RouteType to the API's curviness string.
3. **Remove** `getCurvatureWeight()`. It is called only by `calculateCurvatureAndElevation()` (deleted in step 7) and the deprecated `parseOsrmRoutes()` in RouteUtils.kt. **NOTE:** Keep `getCurvatureWeight()` during steps 6-10 since the deprecated `parseOsrmRoutes()` still references it. Delete it in step 11 along with the deprecated functions.
4. **Keep** `getSpeedFactor()`. It is used by `calculateStraightLineMetrics()` for fallback speed estimation. Remove the `else -> 1.0` branch since all enum values will be explicitly covered.

**Final enum:**
```kotlin
enum class RouteType(val displayName: String, val apiValue: String) {
    DIRECT("Direct", "direct"),
    FAST("Fast", "fast_curvy"),
    CURVY("Curvy", "curvy"),
    EXTRA_CURVY("Extra Curvy", "extra_curvy");

    fun getSpeedFactor(): Double = when (this) {
        DIRECT -> 1.2
        FAST -> 1.0
        CURVY -> 0.8
        EXTRA_CURVY -> 0.6
    }
}
```

**Addresses review issues:** #4 (vehicle type removal), #10 (getCurvatureWeight removal), #18 (getSpeedFactor simplification), #16 (PreferenceDialog confirmed safe).

---

## Change #3: `models/Avoidance.kt`

**Changes:**
Add `apiValue: String?` property. `UNPAVED_ROADS` and `NARROW_ROADS` return `null` because the new API does not support them. The RouteService will filter out nulls when building the JSON body.

```kotlin
enum class Avoidance(val displayName: String, val apiValue: String?) {
    HIGHWAYS("Highways", "motorway"),
    TOLLS("Toll Roads", "toll"),
    FERRIES("Ferries", "ferry"),
    UNPAVED_ROADS("Unpaved Roads", null),
    NARROW_ROADS("Narrow Roads", null);
}
```

**Rationale:** Keeping the enum values rather than deleting them means the AvoidanceDialog UI (`Avoidance.entries` at MapScreen.kt:814) works without changes. Users can still select them, they just get silently filtered out before the API call. If the API later supports unpaved/narrow road avoidance, we add the `apiValue` without a migration. Note: there is no user-facing indication that "Unpaved Roads" and "Narrow Roads" are not supported by the new API — a future UX improvement could show a Toast or disable those checkboxes when the local API is in use. For this migration, the priority is functional correctness.

**Addresses review issue:** #6 (UNPAVED_ROADS/NARROW_ROADS mapping).

---

## Change #4: `models/Route.kt`

**Changes:**
1. Add `CurvatureMetadata` inner data class to hold the server-provided curvature data.
2. Add `curvatureMetadata: CurvatureMetadata? = null` field to Route.
3. **Keep** `curvatureScore: Double = 0.0` and `elevationGain: Double = 0.0` as legacy fields. When `curvatureMetadata` is set, `curvatureScore` will be populated with `curvatureMetadata.curvaturePerKm` so the RouteInfoCard display code works with minimal changes.
4. Add `routeScore: Double = 0.0` for the new `score` field from the API response.

```kotlin
class Route(
    val name: String,
    waypoints: List<Waypoint>
) {
    // ... existing fields ...
    var distance: Double = 0.0
    var duration: Double = 0.0
    var curvatureScore: Double = 0.0      // populated from curvaturePerKm
    var elevationGain: Double = 0.0        // kept at 0, not provided by new API
    var routeScore: Double = 0.0           // new: from API's "score" field
    var curvatureMetadata: CurvatureMetadata? = null  // new

    data class CurvatureMetadata(
        val totalCurvature: Double = 0.0,
        val curvaturePerKm: Double = 0.0,
        val curviestSegmentWayId: Long = 0,
        val curviestSegmentCurvature: Double = 0.0,
        val segmentsAnalyzed: Int = 0,
        val segmentsWithCurvature: Int = 0
    )
}
```

**Addresses review issues:** #7 (curvature display format), #14 (underspecified Route.kt changes).

---

## Change #5: `services/RouteService.kt` — COMPLETE REWRITE of routing logic

**Import to add:** `import com.motorider.config.ApiConfig`
**Additional imports needed:** `import org.json.JSONArray` and `import org.json.JSONObject` (RouteService.kt currently has no org.json imports — the new method builds JSON objects directly).

**Changes:**
1. **Update the call site** in `calculateRouteAsync()` line 41: change `fetchRoutesFromOsrm(...)` to `fetchRoutesFromApi(...)`.
2. **Remove** `LOCAL_OSRM_URL` and `PUBLIC_OSRM_URL` companion constants.
3. **Remove unused import** `java.nio.charset.StandardCharsets` (was used by the old OSRM parser's `BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))`; the new parser uses `kotlin.text.Charsets` which is auto-imported).
4. **Replace** `fetchRoutesFromOsrm()` with a new `fetchRoutesFromApi()` method.
5. **Delete** `buildExcludeParam()` (dead code — avoidances now sent as JSON array, mapping lives on the Avoidance enum). **Addresses review issue #12.**
6. **Keep** `buildFullWaypointList()` — it builds the waypoint list used by the fallback Route object. Still needed.
7. **Keep** `calculateStraightLineMetrics()` — used for fallback when API is unreachable. **Update** to set `elevationGain = 0.0` and `curvatureScore = 0.0` instead of calling the deleted `calculateCurvatureAndElevation()`.
8. **Delete** `calculateCurvatureAndElevation()` — entirely dead code after parser rewrite.

### New `fetchRoutesFromApi()` method:

```kotlin
private fun fetchRoutesFromApi(
    start: Waypoint, end: Waypoint, waypoints: List<Waypoint>?,
    routePreference: RouteType, avoidances: Set<Avoidance>?
): List<Route> {
    val fullWaypoints = buildFullWaypointList(start, end, waypoints)
    val fallbackRoute = Route(routePreference.displayName, fullWaypoints)
    fallbackRoute.avoidances = avoidances ?: emptySet()

    try {
        val jsonBody = JSONObject().apply {
            put("start", JSONObject().apply {
                put("lat", start.location.latitude)
                put("lon", start.location.longitude)
            })
            put("end", JSONObject().apply {
                put("lat", end.location.latitude)
                put("lon", end.location.longitude)
            })
            if (!waypoints.isNullOrEmpty()) {
                put("waypoints", JSONArray().apply {
                    waypoints.forEach { wp ->
                        put(JSONObject().apply {
                            put("lat", wp.location.latitude)
                            put("lon", wp.location.longitude)
                        })
                    }
                })
            }
            put("curviness", routePreference.apiValue)
            // Filter out avoidances with null apiValue (UNPAVED_ROADS, NARROW_ROADS)
            val validAvoidances = avoidances?.mapNotNull { it.apiValue }
            if (!validAvoidances.isNullOrEmpty()) {
                put("avoidances", JSONArray(validAvoidances))
            }
        }

        val url = URL("${ApiConfig.ROUTING_API_BASE_URL}/route")
        val conn = url.openConnection() as HttpURLConnection
        conn.doOutput = true                            // REQUIRED for POST
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "MotoRider/1.0")

        // Write JSON body
        conn.outputStream.use { os ->
            os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
        }

        try {
            val responseCode = conn.responseCode
            val success = responseCode in 200..299
            val stream = if (success) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""

            if (success) {
                val root = JSONObject(responseText)
                // Check app-level success (even HTTP 200 can have success: false)
                if (root.optBoolean("success", false)) {
                    val apiRoutes = RouteUtils.parseRouteApiResponse(
                        responseText, fullWaypoints, routePreference
                    )
                    if (apiRoutes.isNotEmpty()) return apiRoutes
                } else {
                    val errorMsg = root.optString("message",
                        root.optString("detail", "Routing failed"))
                    android.util.Log.w("RouteService", "API routing error: $errorMsg")
                }
            } else {
                // HTTP error — try to parse error detail
                val errorDetail = try {
                    JSONObject(responseText).optString("detail", "HTTP $responseCode")
                } catch (_: Exception) { "HTTP $responseCode" }
                android.util.Log.w("RouteService", "API HTTP error: $errorDetail")
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        android.util.Log.w("RouteService", "API routing failed, fallback to straight-line", e)
    }

    // Fallback: straight-line distance estimation
    calculateStraightLineMetrics(fallbackRoute, routePreference)
    return listOf(fallbackRoute)
}
```

### Updated `calculateStraightLineMetrics()`:

```kotlin
private fun calculateStraightLineMetrics(route: Route, routePreference: RouteType) {
    val waypoints = route.waypoints
    if (waypoints.isNullOrEmpty()) return

    var totalDistance = 0.0
    var totalDuration = 0.0
    for (i in 1 until waypoints.size) {
        val prev = waypoints[i - 1].location
        val current = waypoints[i].location
        if (prev != null && current != null) {
            // ... existing Haversine calculation ...
            totalDistance += segmentDistance
            val speed = 60.0 * routePreference.getSpeedFactor()
            totalDuration += segmentDistance / speed
        }
    }
    route.distance = totalDistance / 1000.0
    route.duration = totalDuration * 60.0
    route.curvatureScore = 0.0       // No curvature in fallback
    route.elevationGain = 0.0         // No elevation in fallback
    route.routeScore = 1.0            // Neutral score for fallback
}
```

**Addresses review issues:** #1 (geometry format), #2 (response success check), #3 (three error paths), #5 (fallback compilation), #6 (null apiValue filtering), #9 (alternatives always on), #11 (POST doOutput+Content-Type), #12 (dead code cleanup), #13 (URL→JSON body), #17 (improved error logging).

---

## Change #6: `utils/RouteUtils.kt`

**Import to add:** `import com.motorider.config.ApiConfig`

**Changes:**
1. **Add** `parseRouteApiResponse()` — new parser for the POST /route response.
2. **Keep** all geocoding functions (`reverseGeocode`, `searchLocations`, `geocodeLocation`, `parseGeocodingResponse`) but update their URLs to use `ApiConfig.NOMINATIM_BASE_URL`.
3. **Mark as deprecated (keep for now):** `parseOsrmRoutes()`, `calculateCurvatureScore()`, `calculateElevationGain()`. Add `@Deprecated` annotations pointing to the new parser. These are kept during the transition period only so tests don't break while the test plan (Change #9) is being applied. Delete them in Change #10.

### New `parseRouteApiResponse()`:

```kotlin
fun parseRouteApiResponse(
    jsonResponse: String?,
    waypoints: List<Waypoint>,
    routePreference: com.motorider.models.RouteType
): List<com.motorider.models.Route> {
    if (jsonResponse.isNullOrEmpty()) return emptyList()

    return try {
        val root = JSONObject(jsonResponse)
        if (!root.optBoolean("success", false)) return emptyList()

        val routes = root.optJSONArray("routes") ?: return emptyList()
        val resultList = mutableListOf<com.motorider.models.Route>()

        for (r in 0 until routes.length()) {
            val routeObj = routes.getJSONObject(r)
            val distance = routeObj.optDouble("distance", 0.0)
            val duration = routeObj.optDouble("duration", 0.0)
            val score = routeObj.optDouble("score", 0.0)

            // NEW API: geometry is a flat JSONArray of [lon, lat] pairs
            // (NOT wrapped in { type, coordinates } like OSRM GeoJSON)
            val geometry = routeObj.optJSONArray("geometry") ?: continue
            if (geometry.length() == 0) continue

            val points = ArrayList<GeoPoint>(geometry.length())
            for (i in 0 until geometry.length()) {
                val pair = geometry.optJSONArray(i) ?: continue
                if (pair.length() < 2) continue
                points.add(GeoPoint(pair.getDouble(1), pair.getDouble(0)))
            }
            if (points.isEmpty()) continue

            val routeName = if (r == 0) routePreference.displayName
                            else "${routePreference.displayName} (Alt ${r + 1})"
            val route = com.motorider.models.Route(routeName, waypoints.toList())
            route.routeGeometry = points
            route.distance = distance / 1000.0
            route.duration = duration / 60.0
            route.routeScore = score

            // Parse curvature_metadata from server
            val metaObj = routeObj.optJSONObject("curvature_metadata")
            if (metaObj != null) {
                val metadata = com.motorider.models.Route.CurvatureMetadata(
                    totalCurvature = metaObj.optDouble("total_curvature", 0.0),
                    curvaturePerKm = metaObj.optDouble("curvature_per_km", 0.0),
                    curviestSegmentWayId = metaObj.optLong("curviest_segment_way_id", 0),
                    curviestSegmentCurvature = metaObj.optDouble("curviest_segment_curvature", 0.0),
                    segmentsAnalyzed = metaObj.optInt("segments_analyzed", 0),
                    segmentsWithCurvature = metaObj.optInt("segments_with_curvature", 0)
                )
                route.curvatureMetadata = metadata
                // Populate legacy curvatureScore with curvaturePerKm for RouteInfoCard compat
                route.curvatureScore = metadata.curvaturePerKm
            }
            route.elevationGain = 0.0  // Not provided by new API
            route.avoidances = emptySet() // Avoidances not echoed back by API (set from caller)

            resultList.add(route)
        }
        resultList
    } catch (e: Exception) {
        android.util.Log.w("RouteUtils", "Failed to parse route API response", e)
        emptyList()
    }
}
```

### Updated geocoding URLs:

Replace the three hardcoded Nominatim URLs with `ApiConfig.NOMINATIM_BASE_URL`:

| Line | Old URL | New |
|------|---------|-----|
| 33 | `https://nominatim.openstreetmap.org/reverse?...` | `"${ApiConfig.NOMINATIM_BASE_URL}/reverse?..."` |
| 72 | `https://nominatim.openstreetmap.org/search?...` | `"${ApiConfig.NOMINATIM_BASE_URL}/search?..."` |
| 131 | `https://nominatim.openstreetmap.org/search?...` | `"${ApiConfig.NOMINATIM_BASE_URL}/search?..."` |

**Addresses review issues:** #1 (flat geometry parsing — `optJSONArray("geometry")` not `optJSONObject("geometry").optJSONArray("coordinates")`), #2 (check `success` not `code`), #14 (CurvatureMetadata population).

---

## Change #7: `services/TileStorageManager.kt`

**Import to add:** `import com.motorider.config.ApiConfig`

**Changes:** Replace hardcoded tile URLs with `ApiConfig.TILE_SERVER_BASE_URL`.

- Line 17: Replace `arrayOf("https://tile.openstreetmap.org/")` with `arrayOf(ApiConfig.TILE_SERVER_BASE_URL + "/")`
- Line 82: Replace `"https://tile.openstreetmap.org/$zoom/$x/$y.png"` with `"${ApiConfig.TILE_SERVER_BASE_URL}/$zoom/$x/$y.png"`

---

## Change #8: `ui/screen/MapScreen.kt`

**Changes:**
1. **Update `LegChip` composable** (line 755-768): Remove the `else` branch in the `when` block since all RouteType values are now explicitly covered after vehicle type removal. **Addresses review issue #15.**
2. **Update `RouteInfoCard` curvature StatItem** (line 872): `route.curvatureScore` now holds `curvaturePerKm`. Remove the `"%"` suffix. Update `R.string.curvature_label` in `strings.xml` from `"Curvature"` to `"Curves/km"`.
3. **Replace `RouteInfoCard` elevation StatItem** (line 873) with a score StatItem. `elevationGain` will always be `0.0` since the new API does not provide elevation data. Replace with `route.routeScore` (the new `score` field from the API).
4. **Keep `generateRoundTrip`** with `start == end` (line 1030): The new API wraps GraphHopper which supports round trips. Keep passing `start` as both start and end. If the API rejects it, the fallback to straight-line will still work. Add a TODO comment noting this assumption.

### Updated LegChip (no else branch):
```kotlin
when (pref) {
    RouteType.DIRECT -> Icons.Outlined.Speed to AccentOrange
    RouteType.FAST -> Icons.Outlined.Navigation to BrandBlue
    RouteType.CURVY -> Icons.Outlined.Timeline to BrandBlueLight
    RouteType.EXTRA_CURVY -> Icons.Outlined.Landscape to ErrorRed
}
```

### Updated RouteInfoCard StatItems (lines 870-874):
Replace the existing four StatItems with:
```kotlin
StatItem(Icons.Outlined.Navigation, stringResource(R.string.distance_label), formatDistance(route.distance))
StatItem(Icons.Outlined.Schedule, stringResource(R.string.duration_label), "${"%.0f".format(route.duration)} min")
StatItem(Icons.Outlined.Straighten, stringResource(R.string.curvature_label), "${"%.1f".format(route.curvatureScore)}")  // curvaturePerKm
StatItem(Icons.Outlined.Star, stringResource(R.string.score_label), "${"%.2f".format(route.routeScore)}")                // new
```
Note: `Icons.Outlined.Star` replaces `Icons.Outlined.Terrain` (elevation). A new string resource `R.string.score_label` with value `"Score"` must be added to `strings.xml`.

### String resource changes in `res/values/strings.xml`:
- Change `curvature_label`: `"Curvature"` → `"Curves/km"`
- Add `score_label`: `"Score"`

**Addresses review issues:** #7 (curvature display format), #15 (LegChip else branch), #4 (round trip assumption documented).

> **Since superseded.** The line numbers above are long stale, and two of these
> decisions were later reversed: the `Score` StatItem was dropped from
> `RouteInfoCard` (a bare `0.87` told a rider nothing), leaving distance, duration
> and curves/km; and `LegChip` now lives in `PlanPanel.kt`, not `MapScreen.kt`.
> `R.string.score_label` is still defined but no longer used.

---

## Change #9: Test Files

### `RouteUtilsTest.kt`
**File:** `app/src/test/java/com/motorider/RouteUtilsTest.kt`

Changes needed:
1. **Delete** `testCalculateCurvatureScore` (lines 12-22) — function is deprecated, no longer called by production code.
2. **Delete** `testCalculateElevationGain` (lines 24-34) and `testCalculateElevationGainWithNoGain` (lines 36-46) — function is deprecated.
3. **Delete** `testRouteTypeCurvatureWeights` (lines 195-202) — tests `RouteType.MOTORCYCLE.getCurvatureWeight()`, both enum value and function are removed.
4. **Update** `testRouteTypeSpeedFactors` (lines 204-211): Remove line 210 (`assertEquals("MOTORCYCLE should have speed factor 1.0", 1.0, RouteType.MOTORCYCLE.getSpeedFactor(), 0.01)`) since `RouteType.MOTORCYCLE` is removed. Keep the remaining assertions (lines 206-209) which test DIRECT/FAST/CURVY/EXTRA_CURVY.
5. **Rewrite** `testParseOsrmRoutesWithRealData` (lines 108-128), `testParseOsrmRoutesNestedCoordinates` (lines 130-142), `testParseOsrmRoutesUsesRouteLevelDistance` (lines 144-158), `testParseOsrmRoutesNullAndEmpty` (lines 160-170), `testParseOsrmRoutesFailureCode` (lines 172-181) — replace with equivalent `parseRouteApiResponse` tests using the new response format.
6. **Add** new test: `testParseRouteApiResponse` — validates parsing of a complete new-API response including curvature_metadata.
7. **Add** new test: `testParseRouteApiResponseFlatGeometry` — validates the flat geometry array (no coordinates wrapper).
8. **Add** new test: `testParseRouteApiResponseSuccessFalse` — validates `success: false` returns empty list.
9. **Add** new test: `testParseRouteApiResponseEmptyAndNull` — validates empty/null JSON.

### `RouteServiceTest.kt`
**File:** `app/src/test/java/com/motorider/RouteServiceTest.kt`

Changes needed:
1. **Delete** `testRouteTypeCurvatureWeights` (lines 19-24) — tests `getCurvatureWeight()` which is removed. `testRouteTypeSpeedFactors` (lines 27-32) needs no changes since it does not reference MOTORCYCLE.
2. Tests referencing `Avoidance.UNPAVED_ROADS` and `Avoidance.NARROW_ROADS` still work since enum values are kept. Optionally add assertions that their `apiValue` is null.

---

## Change #10: Delete functions (after tests are updated)

After all tests pass with the new code:
1. **Delete** `RouteUtils.parseOsrmRoutes()` — fully replaced by `parseRouteApiResponse()`.
2. **Delete** `RouteUtils.calculateCurvatureScore()` — curvature is now server-side.
3. **Delete** `RouteUtils.calculateElevationGain()` — elevation not provided by new API.
4. **Delete** `RouteUtils.calculateAngle()` — only called by `calculateCurvatureScore()`.
5. **Delete** `RouteService.calculateCurvatureAndElevation()` — dead code.
6. **Delete** `RouteService.buildExcludeParam()` — replaced by enum-based mapping.

---

## Files NOT Changed

| File | Reason |
|------|--------|
| `maps/MotorcycleMapRenderer.kt` | Same `List<GeoPoint>` input. No changes needed. |
| `activities/MainActivity.kt` | No routing logic. No changes needed. |
| `services/NavigationService.kt` | No routing logic. No changes needed. |
| `models/Waypoint.kt` | No changes needed. |
| `models/OfflineRegion.kt` | No changes needed. |
| `ui/screen/OfflineMapManagerScreen.kt` | No routing logic. No changes needed. |
| `ui/component/OsmMapView.kt` | No changes needed. |
| `ui/viewmodel/OfflineMapManagerViewModel.kt` | References TileStorageManager (unchanged API). No changes needed. |
| `data/OfflineRegionRepository.kt` | No changes needed. |
| `services/TileDownloadService.kt` | Uses TileStorageManager tile URLs (updated in Change #7). No direct URL changes. |
| `ui/theme/*.kt` | No changes needed. |
| `MotoRiderApplication.kt` | No changes needed. |
| **All XML files** | Android namespace URLs are boilerplate. No changes needed. |
| **Gradle files** | Toolchain URLs are build-time. No changes needed. |

---

## Implementation Order

**IMPORTANT:** The order below ensures the project compiles at every step. Dependencies are: `RouteService.kt` needs `RouteType.apiValue` (step 6) and `RouteUtils.parseRouteApiResponse` (step 5); `RouteType.kt`'s `getCurvatureWeight()` must remain available until the deprecated `parseOsrmRoutes()` is deleted in step 11; `MapScreen.kt` needs `R.string.score_label` from step 8.

1. **Create** `config/ApiConfig.kt`
2. **Update** `models/Route.kt` — add `CurvatureMetadata`, `routeScore` (additive, no breakage)
3. **Update** `models/Avoidance.kt` — add `apiValue` (additive, no breakage)
4. **Update** `services/TileStorageManager.kt` — config-based tile URLs
5. **Update** `utils/RouteUtils.kt` — add `parseRouteApiResponse`, deprecate old functions, update Nominatim URLs. Keep `getCurvatureWeight()` call in deprecated `parseOsrmRoutes()` for now.
6. **Update** `models/RouteType.kt` — add `apiValue` property. **Keep** vehicle-type enum values (MOTORCYCLE/TRUCK/CAR/BIKE) and `getCurvatureWeight()` for now — the deprecated `parseOsrmRoutes()` in RouteUtils.kt still calls it. Keep the `else` branches in `getSpeedFactor()` and `getCurvatureWeight()` since all vehicle-type values still exist.
7. **Rewrite** `services/RouteService.kt` — new `fetchRoutesFromApi`, delete `calculateCurvatureAndElevation` and `buildExcludeParam`, update call site and imports
8. **Update** `res/values/strings.xml` — change `curvature_label` to `"Curves/km"`, add `score_label` = `"Score"`. Must happen BEFORE MapScreen changes.
9. **Update** `ui/screen/MapScreen.kt` — RouteInfoCard stats update only (references new string resources from step 8). Do NOT remove the LegChip `else` branch yet — the vehicle-type enum values still exist until step 10.
10. **Update** tests, **finalize** `models/RouteType.kt`, **and** `ui/screen/MapScreen.kt` — in this single step: delete deprecated tests from RouteUtilsTest.kt and RouteServiceTest.kt, add new parser tests, remove vehicle-type enum values (MOTORCYCLE/TRUCK/CAR/BIKE) from RouteType.kt, remove the `else` branch from `getSpeedFactor()`, **and** remove the now-unnecessary `else` branch from the LegChip `when` expression. Keep `getCurvatureWeight()` (its `else` branch becomes unreachable dead code but the method still compiles and is needed by the deprecated `parseOsrmRoutes()`).
11. **Delete** deprecated functions from `RouteUtils.kt` (`parseOsrmRoutes`, `calculateCurvatureScore`, `calculateElevationGain`, `calculateAngle`) **and** `getCurvatureWeight()` from `RouteType.kt` — now safe: all callers (production code deleted in steps 5-7, tests deleted in step 10) are gone.
12. **Run** `./gradlew testDebugUnitTest` to verify
13. **Run** `./gradlew build` to verify full compilation

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Round trip (start==end) rejected by API | Low | Medium | Same as OSRM behavior — if API rejects, fallback to straight-line kick in. User still gets a route. |
| API server not running | Medium | Low | Same fallback behavior as current OSRM — straight-line estimation. Better: we now log clearly which error occurred. |
| Geometry array empty (API returns no route) | Low | Medium | Handled — empty geometry check returns empty routes list, falls back to straight-line. |
| nominatim rate-limiting | Low | Low | ~~Unchanged from current behavior. Optional: could add throttle/delay.~~ **Resolved:** `RouteUtils.throttleNominatim` enforces one request per second and sets a contactable User-Agent. |
