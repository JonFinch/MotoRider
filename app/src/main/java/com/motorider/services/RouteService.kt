package com.motorider.services

import com.motorider.config.ApiConfig
import com.motorider.models.Avoidance
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import com.motorider.utils.RouteUtils
import com.motorider.utils.distanceToMeters
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

/**
 * The routing service answered, and its answer was "no".
 *
 * Distinct from being unable to reach it. A straight-line estimate is an honest
 * stand-in for a service that never replied; it is the wrong answer entirely for
 * one that replied "there is no road a motorcycle can use near your destination",
 * because the estimate would draw a confident line across whatever lies between —
 * and label the failure a connectivity problem the rider might try to wait out.
 */
class RouteRejectedException(message: String) : Exception(message)

class RouteService {

    companion object {
        // Singleton executor to avoid leaking threads on every call.
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    }

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
        executor.execute {
            try {
                val routes = fetchRoutesFromApi(start, end, waypoints, routePreference, avoidances)
                callback?.onRouteCalculated(routes)
            } catch (e: Exception) {
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Ask the routing service for a loop back to [start].
     *
     * Not expressible as a route with via points, which is what this used to be:
     * three coordinates on a compass circle, each snapped to whatever road was
     * nearest, so the rider was sent up dead-end lanes and back. The service
     * generates its own via points on the network and picks between candidate
     * loops. See MotoRiderMaps' API_REFERENCE for the selection it applies.
     */
    fun calculateRoundTripAsync(
        start: Waypoint,
        distanceMeters: Double,
        headingDegrees: Double,
        routePreference: RouteType,
        avoidances: Set<Avoidance>?,
        callback: RouteCalculationCallback?
    ) {
        executor.execute {
            try {
                val routes = fetchRoutesFromApi(
                    start = start,
                    end = start,
                    waypoints = null,
                    routePreference = routePreference,
                    avoidances = avoidances,
                    roundTrip = RoundTripRequest(distanceMeters, headingDegrees)
                )
                callback?.onRouteCalculated(routes)
            } catch (e: Exception) {
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Blocking route calculation, for callers that already have their own worker
     * (a coroutine on [Dispatchers.IO], say). Never call this from the main thread.
     */
    fun calculateRoute(
        start: Waypoint,
        end: Waypoint,
        waypoints: List<Waypoint>?,
        routePreference: RouteType,
        avoidances: Set<Avoidance>?
    ): List<Route> = fetchRoutesFromApi(start, end, waypoints, routePreference, avoidances)

    /** Target length and initial heading for a loop. */
    internal data class RoundTripRequest(val distanceMeters: Double, val headingDegrees: Double)

    /**
     * The request body, split out so the contract with the routing API is testable
     * without a network. The units are the trap: [Route] and the Quick Ride UI both
     * work in kilometres, the API takes `distance_m` in metres, and a loop asked for
     * in the wrong unit is 58 metres long rather than 58 km.
     */
    internal fun buildRequestBody(
        start: Waypoint,
        end: Waypoint,
        waypoints: List<Waypoint>?,
        routePreference: RouteType,
        avoidances: Set<Avoidance>?,
        roundTrip: RoundTripRequest? = null
    ): JSONObject = JSONObject().apply {
        put("start", JSONObject().apply {
            put("lat", start.location.latitude)
            put("lon", start.location.longitude)
        })
        put("end", JSONObject().apply {
            put("lat", end.location.latitude)
            put("lon", end.location.longitude)
        })
        // A loop has no via points to send: the service generates its own on the
        // network. Sending the old compass-circle points alongside would drag the
        // route back onto whatever road sat nearest each one.
        if (roundTrip != null) {
            put("round_trip", JSONObject().apply {
                put("distance_m", roundTrip.distanceMeters)
                put("heading_deg", roundTrip.headingDegrees)
            })
        } else if (!waypoints.isNullOrEmpty()) {
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

    private fun fetchRoutesFromApi(
        start: Waypoint,
        end: Waypoint,
        waypoints: List<Waypoint>?,
        routePreference: RouteType,
        avoidances: Set<Avoidance>?,
        roundTrip: RoundTripRequest? = null
    ): List<Route> {
        val fullWaypoints = buildFullWaypointList(start, end, waypoints)
        val fallbackRoute = Route(routePreference.displayName, fullWaypoints)
        fallbackRoute.avoidances = avoidances ?: emptySet()
        var failureReason = "Routing service unreachable"

        try {
            val jsonBody = buildRequestBody(start, end, waypoints, routePreference, avoidances, roundTrip)

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
                        // The parser fills turnInstructions from the service's own
                        // manoeuvres. Only derive them from geometry when it did
                        // not supply any — an older API, say. Geometry cannot tell
                        // a bend from a junction, so that path announces every
                        // corner; it is a fallback, not an equal alternative.
                        // Route.duration is in minutes; instruction times are seconds.
                        for (route in apiRoutes) {
                            if (route.turnInstructions != null) continue
                            val geometry = route.routeGeometry
                            if (geometry != null && geometry.size >= 2) {
                                route.turnInstructions = RouteUtils.generateTurnInstructions(
                                    geometry, fullWaypoints, route.duration * 60.0
                                )
                            }
                        }
                        if (apiRoutes.isNotEmpty()) return apiRoutes
                    } else {
                        val errorMsg = root.optString("message",
                            root.optString("detail", "Routing failed"))
                        try { android.util.Log.w("RouteService", "API routing error: $errorMsg") } catch (_: Exception) {}
                        throw RouteRejectedException(errorMsg)
                    }
                } else {
                    val errorDetail = try {
                        JSONObject(responseText).optString("detail", "HTTP $responseCode")
                    } catch (_: Exception) { "HTTP $responseCode" }
                    try { android.util.Log.w("RouteService", "API HTTP error: $errorDetail") } catch (_: Exception) {}
                    throw RouteRejectedException(errorDetail)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: RouteRejectedException) {
            // The service spoke. Its answer reaches the rider unchanged rather than
            // being dressed up as an offline estimate.
            throw e
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
        val geometry = ArrayList<GeoPoint>()

        for (i in 0 until waypoints.size) {
            val wp = waypoints[i]
            geometry.add(wp.location)
            if (i > 0) {
                totalDistance += waypoints[i - 1].location.distanceToMeters(wp.location)
            }
        }

        // A nominal 60 km/h, scaled by how much the chosen curviness slows a rider down.
        val averageSpeedKmh = 60.0 * routePreference.getSpeedFactor()
        val durationMinutes = if (averageSpeedKmh > 0) {
            (totalDistance / 1000.0) / averageSpeedKmh * 60.0
        } else 0.0

        route.distance = totalDistance / 1000.0
        route.duration = durationMinutes
        route.curvatureScore = 0.0
        route.elevationGain = 0.0
        route.routeScore = 1.0
        route.routeGeometry = geometry
        // Geometry-derived is all there is here: this is the offline estimate, so
        // there was no service to ask. The line is straight between waypoints, so
        // the only manoeuvres are at the waypoints themselves.
        route.turnInstructions = RouteUtils.generateTurnInstructions(
            geometry, route.waypoints, durationMinutes * 60.0
        )
    }
}
