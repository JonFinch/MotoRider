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
                val urlObj = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&zoom=18")
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
                val baseUrl = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=10&addressdetails=1"

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
                val urlObj = URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1")

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

    /**
     * Calculate curvature score for a route based on turns
     * @param waypoints List of waypoints along the route
     * @return Curvature score between 0 and 100
     */
    fun calculateCurvatureScore(waypoints: List<Waypoint>?): Double {
        if (waypoints == null || waypoints.size < 3) return 0.0

        var totalCurvature = 0.0
        var validPoints = 0

        for (i in 1 until waypoints.size - 1) {
            val prev = waypoints[i - 1]
            val current = waypoints[i]
            val next = waypoints[i + 1]
            val curvature = calculateAngle(prev, current, next)
            totalCurvature += curvature.coerceAtMost(90.0)
            validPoints++
        }

        return if (validPoints > 0) (totalCurvature / validPoints) * 100.0 / 90.0 else 0.0
    }

    /**
     * Calculate the angle between three points
     */
    private fun calculateAngle(prev: Waypoint, current: Waypoint, next: Waypoint): Double {
        val p = prev.location
        val c = current.location
        val n = next.location

        if (p == null || c == null || n == null) return 45.0

        val lat1 = Math.toRadians(p.latitude)
        val lon1 = Math.toRadians(p.longitude)
        val lat2 = Math.toRadians(c.latitude)
        val lon2 = Math.toRadians(c.longitude)
        val lat3 = Math.toRadians(n.latitude)
        val lon3 = Math.toRadians(n.longitude)

        val angle1 = Math.atan2(
            Math.sin(lon1 - lon2) * Math.cos(lat1),
            Math.cos(lat2) * Math.tan(lat1) - Math.sin(lat2) * Math.cos(lon1 - lon2)
        )

        val angle2 = Math.atan2(
            Math.sin(lon3 - lon2) * Math.cos(lat3),
            Math.cos(lat2) * Math.tan(lat3) - Math.sin(lat2) * Math.cos(lon3 - lon2)
        )

        var angle = Math.toDegrees(Math.abs(angle1 - angle2))
        if (angle > 180.0) angle = 360.0 - angle

        return angle
    }

    /**
     * Calculate elevation gain for the route
     */
    fun calculateElevationGain(waypoints: List<Waypoint>?): Double {
        if (waypoints == null || waypoints.size < 2) return 0.0

        var elevationGain = 0.0
        var previousElevation = waypoints[0].elevation

        for (i in 1 until waypoints.size) {
            val currentElevation = waypoints[i].elevation
            if (currentElevation > previousElevation) {
                elevationGain += (currentElevation - previousElevation)
            }
            previousElevation = currentElevation
        }

        return elevationGain
    }

    @Deprecated("Use geocodeLocation(String, GeocodingCallback) instead.", ReplaceWith("geocodeLocation(locationName, callback)"))
    fun stringToGeoPoint(locationName: String): GeoPoint? {
        android.util.Log.w("RouteUtils", "stringToGeoPoint is deprecated and blocks the UI thread. Use geocodeLocation with a callback instead.")
        return null
    }

    /**
     * Parse an OSRM /route/v1 response (requested with
     * geometries=geojson&overview=full) into the full road-following
     * geometry plus the route-level distance and duration.
     */
    fun parseOsrmRoutes(
        jsonResponse: String?,
        waypoints: List<Waypoint>,
        routePreference: com.motorider.models.RouteType
    ): List<com.motorider.models.Route> {
        if (jsonResponse.isNullOrEmpty()) return emptyList()

        return try {
            val root = JSONObject(jsonResponse)
            if (root.optString("code") != "Ok") return emptyList()

            val routes = root.optJSONArray("routes")
            if (routes == null || routes.length() == 0) return emptyList()

            val resultList = mutableListOf<com.motorider.models.Route>()
            for (r in 0 until routes.length()) {
                val routeObj = routes.getJSONObject(r)
                val distance = routeObj.optDouble("distance", 0.0)
                val duration = routeObj.optDouble("duration", 0.0)
                val geometry = routeObj.optJSONObject("geometry") ?: continue
                val coordinates = geometry.optJSONArray("coordinates") ?: continue

                val points = ArrayList<GeoPoint>(coordinates.length())
                for (i in 0 until coordinates.length()) {
                    val pair = coordinates.optJSONArray(i) ?: continue
                    if (pair.length() < 2) continue
                    points.add(GeoPoint(pair.getDouble(1), pair.getDouble(0)))
                }

                if (points.isEmpty()) continue

                val routeName = if (r == 0) routePreference.displayName else "${routePreference.displayName} (Alt ${r + 1})"
                val route = com.motorider.models.Route(routeName, waypoints.toList())
                route.routeGeometry = points
                route.distance = distance / 1000.0
                route.duration = duration / 60.0
                route.curvatureScore = calculateCurvatureScore(waypoints) * routePreference.getCurvatureWeight()
                route.elevationGain = calculateElevationGain(waypoints)
                resultList.add(route)
            }
            resultList
        } catch (e: Exception) {
            try { android.util.Log.w("RouteUtils", "Failed to parse OSRM response", e) } catch (_: Exception) {}
            emptyList()
        }
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
}
