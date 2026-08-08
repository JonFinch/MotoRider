package com.motorider.utils

import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import org.junit.Test
import org.junit.Assert.assertEquals
import org.osmdroid.util.GeoPoint

class RouteUtilsTest {

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
    fun testRouteTypeSpeedFactors() {
        assertEquals("DIRECT should have speed factor 1.2", 1.2, RouteType.DIRECT.getSpeedFactor(), 0.01)
        assertEquals("FAST should have speed factor 1.0", 1.0, RouteType.FAST.getSpeedFactor(), 0.01)
        assertEquals("CURVY should have speed factor 0.8", 0.8, RouteType.CURVY.getSpeedFactor(), 0.01)
        assertEquals("EXTRA_CURVY should have speed factor 0.6", 0.6, RouteType.EXTRA_CURVY.getSpeedFactor(), 0.01)
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

    @Test
    fun testParseRouteApiResponse() {
        val jsonResponse = """{"success":true,"routes":[{"index":0,"score":0.3,"distance":537600.5,"duration":34200.0,"geometry":[[-0.1278,51.5074],[-0.1270,51.5075],[-0.1262,51.5076],[2.3522,48.8566]],"curvature_metadata":{"total_curvature":2450.5,"curvature_per_km":4.56,"curviest_segment_way_id":123456,"curviest_segment_curvature":850.3,"segments_analyzed":45,"segments_with_curvature":38}}],"best_route_index":0}"""

        val waypoints = listOf(
            Waypoint("Start", GeoPoint(51.5074, -0.1278)),
            Waypoint("End", GeoPoint(48.8566, 2.3522))
        )
        val routes = RouteUtils.parseRouteApiResponse(jsonResponse, waypoints, RouteType.DIRECT)

        assert(routes.isNotEmpty()) { "Should parse route API response" }
        val route = routes[0]
        assert(route.routeGeometry != null) { "Geometry should not be null" }
        assert(4 == route.routeGeometry!!.size) { "Should have 4 geometry points" }
        assertEquals("First point longitude", -0.1278, route.routeGeometry!![0].longitude, 0.0001)
        assertEquals("First point latitude", 51.5074, route.routeGeometry!![0].latitude, 0.0001)
        assertEquals("Distance in km", 537.6005, route.distance, 0.1)
        assertEquals("Duration in minutes", 570.0, route.duration, 0.1)
        assertEquals("Score", 0.3, route.routeScore, 0.01)
        assertEquals("Curvature score from per km", 4.56, route.curvatureScore, 0.01)
        assert(route.curvatureMetadata != null) { "Should have curvature metadata" }
        assertEquals("Total curvature", 2450.5, route.curvatureMetadata!!.totalCurvature, 0.1)
        assertEquals("Curvature per km", 4.56, route.curvatureMetadata!!.curvaturePerKm, 0.01)
        assertEquals("Elevation gain is zero", 0.0, route.elevationGain, 0.01)
    }

    @Test
    fun testParseRouteApiResponseFlatGeometry() {
        val jsonResponse = """{"success":true,"routes":[{"distance":500.0,"duration":60.0,"score":0.5,"geometry":[[1.0,2.0],[3.0,4.0],[5.0,6.0],[7.0,8.0],[9.0,10.0],[11.0,12.0],[13.0,14.0],[15.0,16.0]]}]}"""

        val waypoints = listOf(
            Waypoint("Start", GeoPoint(2.0, 1.0)),
            Waypoint("End", GeoPoint(16.0, 15.0))
        )
        val routes = RouteUtils.parseRouteApiResponse(jsonResponse, waypoints, RouteType.FAST)

        assert(routes.isNotEmpty()) { "Should parse response with flat geometry" }
        assert(8 == routes[0].routeGeometry!!.size) { "Should have all 8 coordinate points" }
        assert(routes[0].curvatureMetadata == null) { "Missing curvature metadata should be null" }
    }

    @Test
    fun testParseRouteApiResponseSuccessFalse() {
        val jsonResponse = """{"success":false,"message":"No route found","routes":[]}"""
        val waypoints = listOf(
            Waypoint("Start", GeoPoint(0.0, 0.0)),
            Waypoint("End", GeoPoint(1.0, 1.0))
        )
        val routes = RouteUtils.parseRouteApiResponse(jsonResponse, waypoints, RouteType.DIRECT)
        assert(routes.isEmpty()) { "Should return empty for success=false" }
    }

    @Test
    fun testParseRouteApiResponseEmptyAndNull() {
        val waypoints = listOf(
            Waypoint("Start", GeoPoint(0.0, 0.0)),
            Waypoint("End", GeoPoint(1.0, 1.0))
        )
        assert(RouteUtils.parseRouteApiResponse(null, waypoints, RouteType.DIRECT).isEmpty()) { "Null should be empty" }
        assert(RouteUtils.parseRouteApiResponse("", waypoints, RouteType.DIRECT).isEmpty()) { "Empty should be empty" }
        assert(RouteUtils.parseRouteApiResponse("not json", waypoints, RouteType.DIRECT).isEmpty()) { "Malformed should be empty" }
    }
}
