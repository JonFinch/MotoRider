package com.motorider.services

import com.motorider.models.Avoidance
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import com.motorider.utils.RouteUtils
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

    private companion object {
        const val LOCAL_OSRM_URL = "http://localhost:5001"
        const val PUBLIC_OSRM_URL = "https://router.project-osrm.org"
    }

    interface RouteCalculationCallback {
        fun onRouteCalculated(route: Route)
        fun onError(error: String)
    }

    fun calculateMotorcycleRoute(
        start: Waypoint,
        end: Waypoint,
        waypoints: List<Waypoint>? = null
    ): Route = calculateMotorcycleRoute(start, end, waypoints, RouteType.MOTORCYCLE, null)

    fun calculateMotorcycleRoute(
        start: Waypoint,
        end: Waypoint,
        waypoints: List<Waypoint>?,
        routePreference: RouteType
    ): Route = calculateMotorcycleRoute(start, end, waypoints, routePreference, null)

    fun calculateMotorcycleRoute(
        start: Waypoint,
        end: Waypoint,
        waypoints: List<Waypoint>?,
        routePreference: RouteType,
        avoidances: Set<Avoidance>?
    ): Route = fetchRouteFromOsrm(start, end, waypoints, routePreference, avoidances)

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
                val allWaypoints = ArrayList<Waypoint>()
                allWaypoints.add(start)
                if (waypoints != null) {
                    for (wp in waypoints) {
                        if (wp !== start && wp !== end) {
                            allWaypoints.add(wp)
                        }
                    }
                }
                allWaypoints.add(end)

                val route = fetchRouteFromOsrm(start, end, waypoints, routePreference, avoidances)
                callback?.onRouteCalculated(route)
            } catch (e: Exception) {
                callback?.onError(e.message ?: "Unknown error")
            }
            executor.shutdown()
        }
    }

    private fun fetchRouteFromOsrm(
        start: Waypoint,
        end: Waypoint,
        waypoints: List<Waypoint>?,
        routePreference: RouteType,
        avoidances: Set<Avoidance>?
    ): Route {
        val route = Route(routePreference.displayName, buildFullWaypointList(start, end, waypoints))
        route.avoidances = avoidances ?: emptySet()

        val allPoints = ArrayList<GeoPoint>()
        allPoints.add(start.location)
        if (waypoints != null) {
            for (wp in waypoints) {
                if (wp.location !== start.location && wp.location !== end.location) {
                    allPoints.add(wp.location)
                }
            }
        }
        allPoints.add(end.location)

        try {
            val coords = StringBuilder()
            for (i in allPoints.indices) {
                val pt = allPoints[i]
                if (pt != null) {
                    if (i > 0) coords.append(';')
                    coords.append(pt.longitude).append(',').append(pt.latitude)
                }
            }

            val baseUrl = if (avoidances != null && avoidances.isNotEmpty()) LOCAL_OSRM_URL else PUBLIC_OSRM_URL
            val excludeParam = buildExcludeParam(avoidances)

            val query = StringBuilder("geometries=geojson&overview=full&alternatives=false&steps=false")
            if (excludeParam.isNotEmpty()) {
                query.append("&exclude=").append(excludeParam)
            }

            val urlStr = "$baseUrl/route/v1/driving/$coords?$query"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "MotoRider/1.0")

            try {
                val responseCode = conn.responseCode
                val success = responseCode in 200..299
                val stream = if (success) conn.inputStream else conn.errorStream

                val responseText = StringBuilder()
                if (stream != null) {
                    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            responseText.append(line)
                        }
                    }
                }

                if (!success) {
                    try { android.util.Log.w("RouteService", "OSRM returned HTTP $responseCode: $responseText") } catch (_: Exception) {}
                } else {
                    val osrm = RouteUtils.parseOsrmResponse(responseText.toString())
                    if (osrm != null && osrm.geometry != null && osrm.geometry.isNotEmpty()) {
                        route.routeGeometry = osrm.geometry
                        route.distance = osrm.distance / 1000.0
                        route.duration = osrm.duration
                        calculateCurvatureAndElevation(route, routePreference)
                        return route
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            try { android.util.Log.w("RouteService", "OSRM routing failed, using straight-line estimate", e) } catch (_: Exception) {}
        }

        calculateStraightLineMetrics(route, routePreference)
        return route
    }

    private fun buildFullWaypointList(start: Waypoint, end: Waypoint, waypoints: List<Waypoint>?): List<Waypoint> {
        val all = ArrayList<Waypoint>()
        all.add(start)
        if (waypoints != null) {
            for (wp in waypoints) {
                if (wp !== start && wp !== end && !all.contains(wp)) {
                    all.add(wp)
                }
            }
        }
        all.add(end)
        return all
    }

    private fun buildExcludeParam(avoidances: Set<Avoidance>?): String {
        if (avoidances.isNullOrEmpty()) return ""

        val classes = ArrayList<String>()
        for (a in avoidances) {
            when (a) {
                Avoidance.HIGHWAYS -> classes.add("motorway")
                Avoidance.TOLLS -> classes.add("toll")
                Avoidance.FERRIES -> classes.add("ferry")
                Avoidance.UNPAVED_ROADS, Avoidance.NARROW_ROADS -> {}
            }
        }
        return classes.joinToString(",")
    }

    private fun calculateCurvatureAndElevation(route: Route, routePreference: RouteType) {
        val waypoints = route.waypoints
        if (waypoints.size >= 3) {
            val baseCurvature = RouteUtils.calculateCurvatureScore(waypoints)
            route.curvatureScore = baseCurvature * routePreference.getCurvatureWeight()
        }
        route.elevationGain = RouteUtils.calculateElevationGain(waypoints)
    }

    private fun calculateStraightLineMetrics(route: Route, routePreference: RouteType) {
        val waypoints = route.waypoints
        if (waypoints.isNullOrEmpty()) return

        var totalDistance = 0.0
        var totalDuration = 0.0

        for (i in 1 until waypoints.size) {
            val prev = waypoints[i - 1].location
            val current = waypoints[i].location

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

                val speed = 60000.0 * routePreference.getSpeedFactor()
                totalDuration += segmentDistance / speed
            }
        }

        route.distance = totalDistance / 1000.0
        route.duration = totalDuration
        calculateCurvatureAndElevation(route, routePreference)
    }
}
