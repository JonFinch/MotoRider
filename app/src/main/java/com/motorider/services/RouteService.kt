package com.motorider.services

import com.motorider.config.ApiConfig
import com.motorider.models.Avoidance
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import com.motorider.utils.RouteUtils
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RouteService {

    interface RouteCalculationCallback {
        fun onRouteCalculated(routes: List<Route>)
        fun onError(error: String)
    }

    fun calculateRouteAsync(
        start: Waypoint,
        end: Waypoint,
        waypoints: List<Waypoint>?,
        routePreference: RouteType,
        avoidances: Set<Avoidance>?,
        callback: RouteCalculationCallback?
    ) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val routes = fetchRoutesFromApi(start, end, waypoints, routePreference, avoidances)
                callback?.onRouteCalculated(routes)
            } catch (e: Exception) {
                callback?.onError(e.message ?: "Unknown error")
            }
            executor.shutdown()
        }
    }

    private fun fetchRoutesFromApi(
        start: Waypoint,
        end: Waypoint,
        waypoints: List<Waypoint>?,
        routePreference: RouteType,
        avoidances: Set<Avoidance>?
    ): List<Route> {
        val fullWaypoints = buildFullWaypointList(start, end, waypoints)
        val fallbackRoute = Route(routePreference.displayName, fullWaypoints)
        fallbackRoute.avoidances = avoidances ?: emptySet()
        var failureReason = "Routing service unreachable"

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
                val validAvoidances = avoidances?.mapNotNull { it.apiValue }
                if (!validAvoidances.isNullOrEmpty()) {
                    put("avoidances", JSONArray(validAvoidances))
                }
            }

            val url = URL("${ApiConfig.ROUTING_API_BASE_URL}/route")
            val conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "MotoRider/1.0")

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
                    if (root.optBoolean("success", false)) {
                        val apiRoutes = RouteUtils.parseRouteApiResponse(
                            responseText, fullWaypoints, routePreference
                        )
                        if (apiRoutes.isNotEmpty()) return apiRoutes
                    } else {
                        val errorMsg = root.optString("message",
                            root.optString("detail", "Routing failed"))
                        failureReason = errorMsg
                        try { android.util.Log.w("RouteService", "API routing error: $errorMsg") } catch (_: Exception) {}
                    }
                } else {
                    val errorDetail = try {
                        JSONObject(responseText).optString("detail", "HTTP $responseCode")
                    } catch (_: Exception) { "HTTP $responseCode" }
                    failureReason = errorDetail
                    try { android.util.Log.w("RouteService", "API HTTP error: $errorDetail") } catch (_: Exception) {}
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            failureReason = e.message ?: "Routing service unreachable"
            try { android.util.Log.w("RouteService", "API routing failed, fallback to straight-line", e) } catch (_: Exception) {}
        }

        // Everything below this point is a straight-line ESTIMATE, not a route.
        // It is flagged so the UI can say so: previously this was returned as an
        // ordinary success, which meant a backend outage silently drew a line
        // across country and the rider had no way to tell.
        calculateStraightLineMetrics(fallbackRoute, routePreference)
        fallbackRoute.isEstimate = true
        fallbackRoute.estimateReason = failureReason
        return listOf(fallbackRoute)
    }

    private fun buildFullWaypointList(start: Waypoint, end: Waypoint, waypoints: List<Waypoint>?): List<Waypoint> {
        val all = ArrayList<Waypoint>()
        all.add(start)
        if (waypoints != null) {
            for (wp in waypoints) {
                if (wp != start && wp != end && !all.contains(wp)) {
                    all.add(wp)
                }
            }
        }
        all.add(end)
        return all
    }

    private fun calculateStraightLineMetrics(route: Route, routePreference: RouteType) {
        val waypoints = route.waypoints
        if (waypoints.isNullOrEmpty()) return

        var totalDistance = 0.0
        var totalDuration = 0.0
        val geometry = ArrayList<GeoPoint>()

        for (i in 0 until waypoints.size) {
            val wp = waypoints[i]
            wp.location?.let { geometry.add(it) }
            if (i > 0) {
                val prev = waypoints[i - 1].location
                val current = wp.location
                if (prev != null && current != null) {
                    val lat1 = Math.toRadians(prev.latitude)
                    val lat2 = Math.toRadians(current.latitude)
                    val dLat = Math.toRadians(current.latitude - prev.latitude)
                    val dLon = Math.toRadians(current.longitude - prev.longitude)

                    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                        Math.cos(lat1) * Math.cos(lat2) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2)
                    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

                    val segmentDistance = 6371000.0 * c
                    totalDistance += segmentDistance

                    val speed = 60.0 * routePreference.getSpeedFactor()
                    totalDuration += segmentDistance / speed
                }
            }
        }

        route.distance = totalDistance / 1000.0
        route.duration = totalDuration * 60.0
        route.curvatureScore = 0.0
        route.elevationGain = 0.0
        route.routeScore = 1.0
        route.routeGeometry = geometry
    }
}
