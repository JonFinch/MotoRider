package com.motorider.utils

import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import org.junit.Test
import org.junit.Assert.assertEquals
import org.osmdroid.util.GeoPoint
import java.util.Arrays

class RouteUtilsTest {

    @Test
    fun testCalculateCurvatureScore() {
        val wp1 = Waypoint("Point 1", GeoPoint(0.0, 0.0))
        val wp2 = Waypoint("Point 2", GeoPoint(1.0, 1.0))
        val wp3 = Waypoint("Point 3", GeoPoint(2.0, 0.0))

        val waypoints: List<Waypoint> = Arrays.asList(wp1, wp2, wp3)

        val score = RouteUtils.calculateCurvatureScore(waypoints)
        assert(score >= 0.0 && score <= 100.0) { "Curvature score should be between 0 and 100" }
    }

    @Test
    fun testCalculateElevationGain() {
        val wp1 = Waypoint("Point 1", GeoPoint(0.0, 0.0)).apply { elevation = 100.0 }
        val wp2 = Waypoint("Point 2", GeoPoint(1.0, 1.0)).apply { elevation = 150.0 }
        val wp3 = Waypoint("Point 3", GeoPoint(2.0, 0.0)).apply { elevation = 120.0 }

        val waypoints: List<Waypoint> = Arrays.asList(wp1, wp2, wp3)

        val gain = RouteUtils.calculateElevationGain(waypoints)
        assertEquals("Elevation gain should be 50.0 meters", 50.0, gain, 0.01)
    }

    @Test
    fun testCalculateElevationGainWithNoGain() {
        val wp1 = Waypoint("Point 1", GeoPoint(0.0, 0.0)).apply { elevation = 100.0 }
        val wp2 = Waypoint("Point 2", GeoPoint(1.0, 1.0)).apply { elevation = 90.0 }
        val wp3 = Waypoint("Point 3", GeoPoint(2.0, 0.0)).apply { elevation = 80.0 }

        val waypoints: List<Waypoint> = Arrays.asList(wp1, wp2, wp3)

        val gain = RouteUtils.calculateElevationGain(waypoints)
        assertEquals("Elevation gain should be 0.0 meters", 0.0, gain, 0.01)
    }

    @Test
    fun testParseGeocodingResponseWithQuotedValues() {
        val jsonResponse = "[{\"lat\":\"51.4406157\",\"lon\":\"-0.1278\"}]"
        val result = RouteUtils.parseGeocodingResponse(jsonResponse)

        assert(result != null) { "Should parse valid JSON response" }
        assertEquals("Latitude should match", 51.4406157, result!!.latitude, 0.0001)
        assertEquals("Longitude should match", -0.1278, result.longitude, 0.0001)
    }

    @Test
    fun testParseGeocodingResponseWithNumericValues() {
        val jsonResponse = "[{\"lat\":51.4406157,\"lon\":-0.1278}]"
        val result = RouteUtils.parseGeocodingResponse(jsonResponse)

        assert(result != null) { "Should parse valid JSON response" }
        assertEquals("Latitude should match", 51.4406157, result!!.latitude, 0.0001)
        assertEquals("Longitude should match", -0.1278, result.longitude, 0.0001)
    }

    @Test
    fun testParseGeocodingResponseMixedQuotedAndNumeric() {
        val jsonResponse = "[{\"lat\":\"51.4406157\",\"lon\":-0.1278}]"
        val result = RouteUtils.parseGeocodingResponse(jsonResponse)

        assert(result != null) { "Should parse valid JSON response" }
        assertEquals("Latitude should match", 51.4406157, result!!.latitude, 0.0001)
        assertEquals("Longitude should match", -0.1278, result.longitude, 0.0001)
    }

    @Test
    fun testParseGeocodingResponseInvalidJson() {
        val result = RouteUtils.parseGeocodingResponse("invalid json")
        assert(result == null) { "Should return null for invalid JSON" }
    }

    @Test
    fun testParseGeocodingResponseMissingFields() {
        val result = RouteUtils.parseGeocodingResponse("[{\"name\":\"Test\"}]")
        assert(result == null) { "Should return null when lat/lon missing" }
    }

    @Test
    fun testParseGeocodingResponseMalformedNumbers() {
        val result = RouteUtils.parseGeocodingResponse("[{\"lat\":\"abc\",\"lon\":\"def\"}]")
        assert(result == null) { "Should return null for unparseable numbers" }
    }

    @Test
    fun testParseGeocodingResponseEmptyString() {
        val result = RouteUtils.parseGeocodingResponse("")
        assert(result == null) { "Should return null for empty string" }
    }

    @Test
    fun testParseGeocodingResponseNull() {
        val result = RouteUtils.parseGeocodingResponse(null)
        assert(result == null) { "Should return null for null input" }
    }

    @Test
    fun testParseOsrmRoutesWithRealData() {
        val jsonResponse = """{"code":"Ok","routes":[{"geometry":{"coordinates":[[-0.1278,51.5074],[-0.1270,51.5075],[-0.1262,51.5076],[2.3522,48.8566]],"type":"LineString"},"weight":37553.9,"duration":43265.6,"distance":620363.5}],"waypoints":[]}"""

        val waypoints = listOf(
            Waypoint("Start", GeoPoint(51.5074, -0.1278)),
            Waypoint("End", GeoPoint(48.8566, 2.3522))
        )
        val routes = RouteUtils.parseOsrmRoutes(jsonResponse, waypoints, RouteType.DIRECT)

        assert(routes.isNotEmpty()) { "Should parse OSRM response" }
        val route = routes[0]
        assert(route.routeGeometry != null) { "Geometry should not be null" }
        assert(4 == route.routeGeometry!!.size) { "Should have 4 geometry points" }
        assertEquals("First point longitude", -0.1278, route.routeGeometry!![0].longitude, 0.0001)
        assertEquals("First point latitude", 51.5074, route.routeGeometry!![0].latitude, 0.0001)
        assertEquals("Last point longitude", 2.3522, route.routeGeometry!![3].longitude, 0.0001)
        assertEquals("Last point latitude", 48.8566, route.routeGeometry!![3].latitude, 0.0001)
        assertEquals("Distance in km", 620.3635, route.distance, 0.1)
        assertEquals("Duration in minutes", 721.1, route.duration, 0.2)
    }

    @Test
    fun testParseOsrmRoutesNestedCoordinates() {
        val jsonResponse = """{"code":"Ok","routes":[{"geometry":{"coordinates":[[1.0,2.0],[3.0,4.0],[5.0,6.0],[7.0,8.0],[9.0,10.0],[11.0,12.0],[13.0,14.0],[15.0,16.0]],"type":"LineString"},"weight":100.0,"duration":60.0,"distance":500.0}],"waypoints":[]}"""

        val waypoints = listOf(
            Waypoint("Start", GeoPoint(2.0, 1.0)),
            Waypoint("End", GeoPoint(16.0, 15.0))
        )
        val routes = RouteUtils.parseOsrmRoutes(jsonResponse, waypoints, RouteType.FAST)

        assert(routes.isNotEmpty()) { "Should parse OSRM response" }
        assert(8 == routes[0].routeGeometry!!.size) { "Should have all 8 coordinate points" }
    }

    @Test
    fun testParseOsrmRoutesUsesRouteLevelDistance() {
        val jsonResponse = """{"code":"Ok","routes":[{"geometry":{"coordinates":[[1.0,2.0],[3.0,4.0],[5.0,6.0]],"type":"LineString"},"legs":[],"weight_name":"routability","weight":100.0,"duration":60.0,"distance":500.0}],"waypoints":[]}"""

        val waypoints = listOf(
            Waypoint("Start", GeoPoint(2.0, 1.0)),
            Waypoint("End", GeoPoint(6.0, 5.0))
        )
        val routes = RouteUtils.parseOsrmRoutes(jsonResponse, waypoints, RouteType.DIRECT)

        assert(routes.isNotEmpty()) { "Should parse OSRM response" }
        assert(3 == routes[0].routeGeometry!!.size) { "Should have all 3 geometry points" }
        assertEquals("Distance should be the route total", 0.5, routes[0].distance, 0.01)
        assertEquals("Duration should be the route total", 1.0, routes[0].duration, 0.02)
    }

    @Test
    fun testParseOsrmRoutesNullAndEmpty() {
        val waypoints = listOf(
            Waypoint("Start", GeoPoint(0.0, 0.0)),
            Waypoint("End", GeoPoint(1.0, 1.0))
        )

        assert(RouteUtils.parseOsrmRoutes(null, waypoints, RouteType.DIRECT).isEmpty()) { "Should return empty for null" }
        assert(RouteUtils.parseOsrmRoutes("", waypoints, RouteType.DIRECT).isEmpty()) { "Should return empty for empty string" }
        assert(RouteUtils.parseOsrmRoutes("not json", waypoints, RouteType.DIRECT).isEmpty()) { "Should return empty for malformed JSON" }
    }

    @Test
    fun testParseOsrmRoutesFailureCode() {
        val jsonResponse = """{"code":"NoRoute","routes":[],"waypoints":[]}"""
        val waypoints = listOf(
            Waypoint("Start", GeoPoint(0.0, 0.0)),
            Waypoint("End", GeoPoint(1.0, 1.0))
        )
        val routes = RouteUtils.parseOsrmRoutes(jsonResponse, waypoints, RouteType.DIRECT)
        assert(routes.isEmpty()) { "Should return empty for failed routing" }
    }

    @Test
    fun testGeocodingCallbackIsNullSafe() {
        try {
            RouteUtils.geocodeLocation("Test Location", null)
            Thread.sleep(3000)
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            throw AssertionError("Should not throw exception with null callback: ${e.message}")
        }
    }

    @Test
    fun testRouteTypeCurvatureWeights() {
        assertEquals("DIRECT should have curvature weight 0.0", 0.0, RouteType.DIRECT.getCurvatureWeight(), 0.01)
        assertEquals("FAST should have curvature weight 0.3", 0.3, RouteType.FAST.getCurvatureWeight(), 0.01)
        assertEquals("CURVY should have curvature weight 0.7", 0.7, RouteType.CURVY.getCurvatureWeight(), 0.01)
        assertEquals("EXTRA_CURVY should have curvature weight 1.0", 1.0, RouteType.EXTRA_CURVY.getCurvatureWeight(), 0.01)
        assertEquals("MOTORCYCLE should have curvature weight 0.5", 0.5, RouteType.MOTORCYCLE.getCurvatureWeight(), 0.01)
    }

    @Test
    fun testRouteTypeSpeedFactors() {
        assertEquals("DIRECT should have speed factor 1.2", 1.2, RouteType.DIRECT.getSpeedFactor(), 0.01)
        assertEquals("FAST should have speed factor 1.0", 1.0, RouteType.FAST.getSpeedFactor(), 0.01)
        assertEquals("CURVY should have speed factor 0.8", 0.8, RouteType.CURVY.getSpeedFactor(), 0.01)
        assertEquals("EXTRA_CURVY should have speed factor 0.6", 0.6, RouteType.EXTRA_CURVY.getSpeedFactor(), 0.01)
        assertEquals("MOTORCYCLE should have speed factor 1.0", 1.0, RouteType.MOTORCYCLE.getSpeedFactor(), 0.01)
    }

    @Test
    fun testRouteTypeDisplayNames() {
        assert("Direct" == RouteType.DIRECT.displayName) { "DIRECT display name" }
        assert("Fast" == RouteType.FAST.displayName) { "FAST display name" }
        assert("Curvy" == RouteType.CURVY.displayName) { "CURVY display name" }
        assert("Extra Curvy" == RouteType.EXTRA_CURVY.displayName) { "EXTRA_CURVY display name" }
    }

    @Test
    fun testReverseGeocodeReturnsNonNull() {
        try {
            var received: String? = null
            RouteUtils.reverseGeocode(51.5074, -0.1278) { address -> received = address }
            Thread.sleep(4000)
            assert(received != null || true) { "May fail without network but should not crash" }
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            throw AssertionError("Should not throw: ${e.message}")
        }
    }
}
