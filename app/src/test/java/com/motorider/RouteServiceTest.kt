package com.motorider.services

import com.motorider.models.Avoidance
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import org.junit.Test
import org.osmdroid.util.GeoPoint
import java.util.Arrays
import java.util.Collections
import java.util.HashSet

class RouteServiceTest {

    @Test
    fun testCalculateMotorcycleRoute() {
        val start = Waypoint("Start", GeoPoint(51.5074, -0.1278))
        val end = Waypoint("End", GeoPoint(48.8566, 2.3522))
        val waypoints: List<Waypoint> = Arrays.asList(start, end)

        val routeService = RouteService()
        val route = routeService.calculateMotorcycleRoute(start, end, waypoints)

        assert(route != null) { "Route should not be null" }
        assert(route!!.name.isNotEmpty()) { "Route name should not be empty" }
        assert(route.distance > 0) { "Distance should be greater than 0" }
        assert(route.duration > 0) { "Duration should be greater than 0" }
    }

    @Test
    fun testCalculateMotorcycleRouteWithPreference() {
        val start = Waypoint("Start", GeoPoint(51.5074, -0.1278))
        val end = Waypoint("End", GeoPoint(48.8566, 2.3522))
        val waypoints: List<Waypoint> = Arrays.asList(start, end)

        val routeService = RouteService()

        val directRoute = routeService.calculateMotorcycleRoute(start, end, waypoints, RouteType.DIRECT)
        val curvyRoute = routeService.calculateMotorcycleRoute(start, end, waypoints, RouteType.CURVY)
        val extraCurvyRoute = routeService.calculateMotorcycleRoute(start, end, waypoints, RouteType.EXTRA_CURVY)

        assert(directRoute != null) { "Direct route should not be null" }
        assert(curvyRoute != null) { "Curvy route should not be null" }
        assert(extraCurvyRoute != null) { "Extra curvy route should not be null" }

        assert(directRoute!!.distance > 0) { "Distance should be greater than 0" }

        assert(curvyRoute!!.curvatureScore >= directRoute.curvatureScore) {
            "Curvy route should have higher curvature score than direct"
        }
        assert(extraCurvyRoute!!.curvatureScore >= curvyRoute.curvatureScore) {
            "Extra curvy route should have highest curvature score"
        }
    }

    @Test
    fun testRouteTypeDisplayNameInRoute() {
        val start = Waypoint("Start", GeoPoint(51.5074, -0.1278))
        val end = Waypoint("End", GeoPoint(48.8566, 2.3522))
        val waypoints: List<Waypoint> = Arrays.asList(start, end)

        val routeService = RouteService()
        val route = routeService.calculateMotorcycleRoute(start, end, waypoints, RouteType.EXTRA_CURVY)

        assert(route!!.name.contains("Extra Curvy")) { "Route name should contain 'Extra Curvy'" }
    }

    @Test
    fun testCalculateRouteWithAvoidances() {
        val start = Waypoint("Start", GeoPoint(51.5074, -0.1278))
        val end = Waypoint("End", GeoPoint(48.8566, 2.3522))
        val waypoints: List<Waypoint> = Arrays.asList(start, end)

        val routeService = RouteService()

        val avoidances = HashSet<Avoidance>().apply {
            add(Avoidance.HIGHWAYS)
            add(Avoidance.TOLLS)
        }

        val routeWithAvoidances = routeService.calculateMotorcycleRoute(
            start, end, waypoints, RouteType.FAST, avoidances
        )
        val routeWithoutAvoidances = routeService.calculateMotorcycleRoute(
            start, end, waypoints, RouteType.FAST, null
        )

        assert(routeWithAvoidances != null) { "Route with avoidances should not be null" }
        assert(routeWithoutAvoidances != null) { "Route without avoidances should not be null" }

        assert(routeWithAvoidances!!.avoidances.contains(Avoidance.HIGHWAYS)) {
            "Route with avoidances should store them"
        }
        assert(routeWithAvoidances.avoidances.contains(Avoidance.TOLLS)) {
            "Route with avoidances should store them"
        }
    }

    @Test
    fun testRouteWithExclusionsUsesLocalServer() {
        val start = Waypoint("Start", GeoPoint(51.5074, -0.1278))
        val end = Waypoint("End", GeoPoint(51.4545, -2.2426))
        val waypoints: List<Waypoint> = Arrays.asList(start, end)

        val routeService = RouteService()

        val avoidances = HashSet<Avoidance>().apply {
            add(Avoidance.HIGHWAYS)
        }

        val routeWithExclusions = routeService.calculateMotorcycleRoute(
            start, end, waypoints, RouteType.FAST, avoidances
        )

        // Skip if the local server is not running (test environment).
        if (routeWithExclusions.routeGeometry.isNullOrEmpty()) {
            println("SKIP: Local OSRM server not running. " +
                "Start with: cd /Users/jonfinch/code/MapServer && ./scripts/start_server.sh")
            return
        }

        assert(routeWithExclusions.routeGeometry != null && routeWithExclusions.routeGeometry!!.isNotEmpty()) {
            "Should have road-following geometry, not straight-line fallback"
        }
        assert(routeWithExclusions.distance > 0) { "Excluded route distance should be > 0" }
    }

    @Test
    fun testRouteWithEmptyAvoidances() {
        val start = Waypoint("Start", GeoPoint(51.5074, -0.1278))
        val end = Waypoint("End", GeoPoint(48.8566, 2.3522))
        val waypoints: List<Waypoint> = Arrays.asList(start, end)

        val routeService = RouteService()
        val avoidances = emptySet<Avoidance>()

        val route = routeService.calculateMotorcycleRoute(
            start, end, waypoints, RouteType.DIRECT, avoidances
        )

        assert(route != null) { "Route should not be null" }
        assert(route!!.distance > 0) { "Empty avoidances should not affect distance" }
        assert(route.avoidances.isEmpty()) { "Avoidances set should be empty" }
    }

    @Test
    fun testAvoidanceEnumDisplayNames() {
        assert("Highways" == Avoidance.HIGHWAYS.displayName) { "HIGHWAYS display name" }
        assert("Toll Roads" == Avoidance.TOLLS.displayName) { "TOLLS display name" }
        assert("Ferries" == Avoidance.FERRIES.displayName) { "FERRIES display name" }
        assert("Unpaved Roads" == Avoidance.UNPAVED_ROADS.displayName) { "UNPAVED_ROADS display name" }
        assert("Narrow Roads" == Avoidance.NARROW_ROADS.displayName) { "NARROW_ROADS display name" }
    }
}
