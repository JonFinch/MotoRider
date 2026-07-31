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
    fun testParseOsrmResponseWithRealData() {
        val jsonResponse = """{"code":"Ok","routes":[{"geometry":{"coordinates":[[-0.1278,51.5074],[-0.1270,51.5075],[-0.1262,51.5076],[2.3522,48.8566]],"type":"LineString"},"weight":37553.9,"duration":43265.6,"distance":620363.5}],"waypoints":[{"hint":"hint1","location":[-0.127965,51.507478],"name":"","distance":14.36820005},{"hint":"hint2","location":[2.352316,48.857243],"name":"","distance":72.01081235}]}"""

        val result = RouteUtils.parseOsrmResponse(jsonResponse)

        assert(result != null) { "Should parse OSRM response" }
        assert(result!!.geometry != null) { "Geometry should not be null" }
        assert(4 == result.geometry!!.size) { "Should have 4 geometry points" }
        assertEquals("First point longitude", -0.1278, result.geometry!![0].longitude, 0.0001)
        assertEquals("First point latitude", 51.5074, result.geometry!![0].latitude, 0.0001)
        assertEquals("Last point longitude", 2.3522, result.geometry!![3].longitude, 0.0001)
        assertEquals("Last point latitude", 48.8566, result.geometry!![3].latitude, 0.0001)
        assertEquals("Distance in meters", 620363.5, result.distance, 0.1)
        assertEquals("Duration in seconds", 43265.6, result.duration, 0.1)
    }

    @Test
    fun testParseOsrmResponseNestedCoordinates() {
        val jsonResponse = """{"code":"Ok","routes":[{"geometry":{"coordinates":[[1.0,2.0],[3.0,4.0],[5.0,6.0],[7.0,8.0],[9.0,10.0],[11.0,12.0],[13.0,14.0],[15.0,16.0]],"type":"LineString"},"weight":100.0,"duration":60.0,"distance":500.0}],"waypoints":[]}"""

        val result = RouteUtils.parseOsrmResponse(jsonResponse)

        assert(result != null) { "Should parse OSRM response" }
        assert(8 == result!!.geometry!!.size) { "Should have all 8 coordinate points, not just 1" }
    }

    @Test
    fun testParseOsrmResponseUsesRouteLevelDistance() {
        val jsonResponse = """{"code":"Ok","routes":[{"geometry":{"coordinates":[[1.0,2.0],[3.0,4.0],[5.0,6.0]],"type":"LineString"},"legs":[{"steps":[],"summary":"","weight":50.0,"duration":30.0,"distance":250.0},{"steps":[],"summary":"","weight":50.0,"duration":30.0,"distance":250.0}],"weight_name":"routability","weight":100.0,"duration":60.0,"distance":500.0}],"waypoints":[]}"""

        val result = RouteUtils.parseOsrmResponse(jsonResponse)

        assert(result != null) { "Should parse OSRM response" }
        assert(3 == result!!.geometry!!.size) { "Should have all 3 geometry points" }
        assertEquals("Distance should be the route total, not the first leg", 500.0, result.distance, 0.1)
        assertEquals("Duration should be the route total, not the first leg", 60.0, result.duration, 0.1)
    }

    @Test
    fun testParseOsrmResponseNullAndEmpty() {
        assert(RouteUtils.parseOsrmResponse(null) == null) { "Should return null for null input" }
        assert(RouteUtils.parseOsrmResponse("") == null) { "Should return null for empty input" }
        assert(RouteUtils.parseOsrmResponse("not json") == null) { "Should return null for malformed JSON" }
    }

    @Test
    fun testParseOsrmResponseFailureCode() {
        val jsonResponse = """{"code":"NoRoute","routes":[],"waypoints":[]}"""
        val result = RouteUtils.parseOsrmResponse(jsonResponse)
        assert(result == null) { "Should return null for failed routing" }
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
}
