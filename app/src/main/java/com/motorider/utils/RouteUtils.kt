package com.motorider.utils

import com.motorider.models.Waypoint
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.motorider.config.ApiConfig

object RouteUtils {

    data class LocationSuggestion(
        val displayName: String,
        val lat: Double,
        val lon: Double
    )

    interface GeocodingCallback {
        fun onResult(geoPoint: GeoPoint)
        fun onError(error: String)
    }

    fun reverseGeocode(lat: Double, lon: Double, callback: (String?) -> Unit) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val urlObj = URL("${ApiConfig.NOMINATIM_BASE_URL}/reverse?lat=$lat&lon=$lon&format=json&zoom=18")
                val conn = urlObj.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "MotoRider/1.0")

                val response = try {
                    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                        reader.readLines().joinToString("\n")
                    }
                } finally {
                    conn.disconnect()
                }

                val json = JSONObject(response)
                val displayName = json.optString("display_name", null)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(displayName)
                }
            } catch (e: Exception) {
                android.util.Log.w("RouteUtils", "Reverse geocoding failed", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(null)
                }
            }
            executor.shutdown()
        }
    }

    fun searchLocations(
        query: String,
        centerLat: Double? = null,
        centerLon: Double? = null,
        callback: (List<LocationSuggestion>) -> Unit
    ) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                val baseUrl = "${ApiConfig.NOMINATIM_BASE_URL}/search?q=$encoded&format=json&limit=10&addressdetails=1"

                val urlStr = if (centerLat != null && centerLon != null) {
                    val viewboxMinLon = centerLon - 1.0
                    val viewboxMinLat = centerLat - 1.0
                    val viewboxMaxLon = centerLon + 1.0
                    val viewboxMaxLat = centerLat + 1.0
                    "${baseUrl}&viewbox=${viewboxMinLon},${viewboxMinLat},${viewboxMaxLon},${viewboxMaxLat}"
                } else baseUrl

                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "MotoRider/1.0")

                val response = try {
                    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                        reader.readLines().joinToString("\n")
                    }
                } finally {
                    conn.disconnect()
                }

                val results = JSONArray(response)
                val suggestions = mutableListOf<LocationSuggestion>()
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    suggestions.add(
                        LocationSuggestion(
                            displayName = item.optString("display_name", "Unknown"),
                            lat = item.getDouble("lat"),
                            lon = item.getDouble("lon")
                        )
                    )
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(suggestions)
                }
            } catch (e: Exception) {
                android.util.Log.w("RouteUtils", "Location search failed: $query", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(emptyList())
                }
            }
            executor.shutdown()
        }
    }

    fun geocodeLocation(locationName: String, callback: GeocodingCallback?) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val mainHandler = try {
            android.os.Handler(android.os.Looper.getMainLooper())
        } catch (e: Exception) {
            null
        }

        executor.execute {
            try {
                val encoded = URLEncoder.encode(locationName, StandardCharsets.UTF_8.name())
                val urlObj = URL("${ApiConfig.NOMINATIM_BASE_URL}/search?q=$encoded&format=json&limit=1")

                val conn = urlObj.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "MotoRider/1.0")

                val response = try {
                    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                        reader.readLines().joinToString("\n")
                    }
                } finally {
                    conn.disconnect()
                }

                val parsed = parseGeocodingResponse(response)
                if (parsed != null) {
                    if (mainHandler != null) mainHandler.post { callback?.onResult(parsed) }
                    else callback?.onResult(parsed)
                } else {
                    if (mainHandler != null) mainHandler.post { callback?.onError("Location not found: $locationName") }
                    else callback?.onError("Location not found: $locationName")
                }
            } catch (e: Exception) {
                android.util.Log.w("RouteUtils", "Geocoding failed for: $locationName", e)
                val errorMsg = "Network error: ${e.message}"
                if (mainHandler != null) mainHandler.post { callback?.onError(errorMsg) }
                else callback?.onError(errorMsg)
            }
            executor.shutdown()
        }
    }

    @Deprecated("Use geocodeLocation(String, GeocodingCallback) instead.", ReplaceWith("geocodeLocation(locationName, callback)"))
    fun stringToGeoPoint(locationName: String): GeoPoint? {
        android.util.Log.w("RouteUtils", "stringToGeoPoint is deprecated and blocks the UI thread. Use geocodeLocation with a callback instead.")
        return null
    }

    fun parseGeocodingResponse(jsonResponse: String?): GeoPoint? {
        if (jsonResponse.isNullOrEmpty()) return null

        return try {
            val results = JSONArray(jsonResponse)
            if (results.length() == 0) return null

            val first = results.getJSONObject(0)
            if (!first.has("lat") || !first.has("lon")) return null

            val lat = first.getDouble("lat")
            val lon = first.getDouble("lon")
            GeoPoint(lat, lon)
        } catch (e: Exception) {
            null
        }
    }

    fun parseRouteApiResponse(
        jsonResponse: String?,
        waypoints: List<Waypoint>,
        routePreference: com.motorider.models.RouteType
    ): List<com.motorider.models.Route> {
        if (jsonResponse.isNullOrEmpty()) return emptyList()

        return try {
            val root = org.json.JSONObject(jsonResponse)
            if (!root.optBoolean("success", false)) return emptyList()

            val routes = root.optJSONArray("routes") ?: return emptyList()
            val resultList = mutableListOf<com.motorider.models.Route>()

            // Response-level caveats. The API can succeed while quietly failing to
            // honour an avoidance, or while unable to reach the curvature data; both
            // are reported here and must be carried onto every route so the UI can
            // surface them.
            val avoidancesHonoured = root.optBoolean("avoidances_honoured", true)
            val curvatureAvailable = root.optBoolean("curvature_available", true)
            val serviceMessage = root.optString("message", "").takeIf { it.isNotBlank() }

            for (r in 0 until routes.length()) {
                val routeObj = routes.getJSONObject(r)
                val distance = routeObj.optDouble("distance", 0.0)
                val duration = routeObj.optDouble("duration", 0.0)
                val score = routeObj.optDouble("score", 0.0)

                val geometry = routeObj.optJSONArray("geometry") ?: continue
                if (geometry.length() == 0) continue

                val points = java.util.ArrayList<org.osmdroid.util.GeoPoint>(geometry.length())
                for (i in 0 until geometry.length()) {
                    val pair = geometry.optJSONArray(i) ?: continue
                    if (pair.length() < 2) continue
                    points.add(org.osmdroid.util.GeoPoint(pair.getDouble(1), pair.getDouble(0)))
                }
                if (points.isEmpty()) continue

                val routeName = if (r == 0) routePreference.displayName
                                else "${routePreference.displayName} (Alt ${r + 1})"
                val route = com.motorider.models.Route(routeName, waypoints.toList())
                route.routeGeometry = points
                route.distance = distance / 1000.0
                route.duration = duration / 60.0
                route.routeScore = score

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
                    route.curvatureScore = metadata.curvaturePerKm
                }
                route.elevationGain = 0.0
                route.avoidancesHonoured = avoidancesHonoured
                route.curvatureAvailable = curvatureAvailable
                if (!avoidancesHonoured || !curvatureAvailable) {
                    route.serviceWarning = serviceMessage
                }

                resultList.add(route)
            }
            resultList
        } catch (e: Exception) {
            emptyList()
        }
    }
}
