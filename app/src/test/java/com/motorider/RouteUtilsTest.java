package com.motorider.utils;

import com.motorider.models.RouteType;
import com.motorider.models.Waypoint;
import org.osmdroid.util.GeoPoint;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

public class RouteUtilsTest {

    @Test
    public void testCalculateCurvatureScore() {
        // Create test waypoints
        Waypoint wp1 = new Waypoint("Point 1", new GeoPoint(0, 0));
        Waypoint wp2 = new Waypoint("Point 2", new GeoPoint(1, 1));
        Waypoint wp3 = new Waypoint("Point 3", new GeoPoint(2, 0));
        
        List<Waypoint> waypoints = Arrays.asList(wp1, wp2, wp3);
        
        // Test curvature calculation
        double score = RouteUtils.calculateCurvatureScore(waypoints);
        assertTrue("Curvature score should be between 0 and 100", score >= 0 && score <= 100);
    }

    @Test
    public void testCalculateElevationGain() {
        // Create test waypoints with elevation data
        Waypoint wp1 = new Waypoint("Point 1", new GeoPoint(0, 0));
        wp1.setElevation(100.0);
        
        Waypoint wp2 = new Waypoint("Point 2", new GeoPoint(1, 1));
        wp2.setElevation(150.0);
        
        Waypoint wp3 = new Waypoint("Point 3", new GeoPoint(2, 0));
        wp3.setElevation(120.0);
        
        List<Waypoint> waypoints = Arrays.asList(wp1, wp2, wp3);
        
        // Test elevation gain calculation
        double gain = RouteUtils.calculateElevationGain(waypoints);
        assertEquals("Elevation gain should be 50.0 meters", 50.0, gain, 0.01);
    }

    @Test
    public void testCalculateElevationGainWithNoGain() {
        // Create test waypoints with no elevation gain
        Waypoint wp1 = new Waypoint("Point 1", new GeoPoint(0, 0));
        wp1.setElevation(100.0);
        
        Waypoint wp2 = new Waypoint("Point 2", new GeoPoint(1, 1));
        wp2.setElevation(90.0);
        
        Waypoint wp3 = new Waypoint("Point 3", new GeoPoint(2, 0));
        wp3.setElevation(80.0);
        
        List<Waypoint> waypoints = Arrays.asList(wp1, wp2, wp3);
        
        // Test elevation gain calculation with no gain
        double gain = RouteUtils.calculateElevationGain(waypoints);
        assertEquals("Elevation gain should be 0.0 meters", 0.0, gain, 0.01);
    }

    @Test
    public void testParseGeocodingResponseWithQuotedValues() {
        // Nominatim API returns lat/lon as quoted strings: "lat":"51.4406157"
        String jsonResponse = "[{\"lat\":\"51.4406157\",\"lon\":\"-0.1278\"}]";
        
        GeoPoint result = RouteUtils.parseGeocodingResponse(jsonResponse);
        
        assertNotNull("Should parse valid JSON response", result);
        assertEquals("Latitude should match", 51.4406157, result.getLatitude(), 0.0001);
        assertEquals("Longitude should match", -0.1278, result.getLongitude(), 0.0001);
    }

    @Test
    public void testParseGeocodingResponseWithNumericValues() {
        // Some APIs may return numeric values without quotes
        String jsonResponse = "[{\"lat\":51.4406157,\"lon\":-0.1278}]";
        
        GeoPoint result = RouteUtils.parseGeocodingResponse(jsonResponse);
        
        assertNotNull("Should parse valid JSON response", result);
        assertEquals("Latitude should match", 51.4406157, result.getLatitude(), 0.0001);
        assertEquals("Longitude should match", -0.1278, result.getLongitude(), 0.0001);
    }

    @Test
    public void testParseGeocodingResponseMixedQuotedAndNumeric() {
        // Nominatim sometimes returns lat as quoted string and lon as numeric
        String jsonResponse = "[{\"lat\":\"51.4406157\",\"lon\":-0.1278}]";
        
        GeoPoint result = RouteUtils.parseGeocodingResponse(jsonResponse);
        
        assertNotNull("Should parse valid JSON response", result);
        assertEquals("Latitude should match", 51.4406157, result.getLatitude(), 0.0001);
        assertEquals("Longitude should match", -0.1278, result.getLongitude(), 0.0001);
    }

    @Test
    public void testParseGeocodingResponseInvalidJson() {
        String jsonResponse = "invalid json";
        
        GeoPoint result = RouteUtils.parseGeocodingResponse(jsonResponse);
        
        assertNull("Should return null for invalid JSON", result);
    }

    @Test
    public void testParseGeocodingResponseMissingFields() {
        String jsonResponse = "[{\"name\":\"Test\"}]";
        
        GeoPoint result = RouteUtils.parseGeocodingResponse(jsonResponse);
        
        assertNull("Should return null when lat/lon missing", result);
    }

    @Test
    public void testParseGeocodingResponseMalformedNumbers() {
        // Simulates the original bug: quoted string that can't be parsed as double
        String jsonResponse = "[{\"lat\":\"abc\",\"lon\":\"def\"}]";
        
        GeoPoint result = RouteUtils.parseGeocodingResponse(jsonResponse);
        
        assertNull("Should return null for unparseable numbers", result);
    }

    @Test
    public void testParseGeocodingResponseEmptyString() {
        GeoPoint result = RouteUtils.parseGeocodingResponse("");
        assertNull("Should return null for empty string", result);
    }

    @Test
    public void testParseGeocodingResponseNull() {
        GeoPoint result = RouteUtils.parseGeocodingResponse(null);
        assertNull("Should return null for null input", result);
    }

    @Test
    public void testParseOsrmResponseWithRealData() {
        // Simulate a real OSRM response with nested coordinates array
        String jsonResponse = "{\"code\":\"Ok\",\"routes\":[{\"geometry\":{\"coordinates\":[[-0.1278,51.5074],[-0.1270,51.5075],[-0.1262,51.5076],[2.3522,48.8566]],\"type\":\"LineString\"},\"weight\":37553.9,\"duration\":43265.6,\"distance\":620363.5}],\"waypoints\":[{\"hint\":\"hint1\",\"location\":[-0.127965,51.507478],\"name\":\"\",\"distance\":14.36820005},{\"hint\":\"hint2\",\"location\":[2.352316,48.857243],\"name\":\"\",\"distance\":72.01081235}]}";

        RouteUtils.OsrmResult result = RouteUtils.parseOsrmResponse(jsonResponse);

        assertNotNull("Should parse OSRM response", result);
        assertNotNull("Geometry should not be null", result.geometry);
        assertEquals("Should have 4 geometry points", 4, result.geometry.size());
        assertEquals("First point longitude", -0.1278, result.geometry.get(0).getLongitude(), 0.0001);
        assertEquals("First point latitude", 51.5074, result.geometry.get(0).getLatitude(), 0.0001);
        assertEquals("Last point longitude", 2.3522, result.geometry.get(3).getLongitude(), 0.0001);
        assertEquals("Last point latitude", 48.8566, result.geometry.get(3).getLatitude(), 0.0001);
        assertEquals("Distance in meters", 620363.5, result.distance, 0.1);
        assertEquals("Duration in seconds", 43265.6, result.duration, 0.1);
    }

    @Test
    public void testParseOsrmResponseNestedCoordinates() {
        // Test that nested coordinate arrays are fully parsed, not truncated at first ]
        String jsonResponse = "{\"code\":\"Ok\",\"routes\":[{\"geometry\":{\"coordinates\":[[1.0,2.0],[3.0,4.0],[5.0,6.0],[7.0,8.0],[9.0,10.0],[11.0,12.0],[13.0,14.0],[15.0,16.0]],\"type\":\"LineString\"},\"weight\":100.0,\"duration\":60.0,\"distance\":500.0}],\"waypoints\":[]}";

        RouteUtils.OsrmResult result = RouteUtils.parseOsrmResponse(jsonResponse);

        assertNotNull("Should parse OSRM response", result);
        assertEquals("Should have all 8 coordinate points, not just 1", 8, result.geometry.size());
    }

    @Test
    public void testParseOsrmResponseUsesRouteLevelDistance() {
        // Multi-leg response: the FIRST "distance"/"duration" in the JSON belong
        // to a per-leg summary (250.0 / 30.0). The parser must instead report the
        // route-level totals (500.0 / 60.0).
        String jsonResponse = "{\"code\":\"Ok\",\"routes\":[{\"geometry\":{\"coordinates\":[[1.0,2.0],[3.0,4.0],[5.0,6.0]],\"type\":\"LineString\"},"
            + "\"legs\":[{\"steps\":[],\"summary\":\"\",\"weight\":50.0,\"duration\":30.0,\"distance\":250.0},"
            + "{\"steps\":[],\"summary\":\"\",\"weight\":50.0,\"duration\":30.0,\"distance\":250.0}],"
            + "\"weight_name\":\"routability\",\"weight\":100.0,\"duration\":60.0,\"distance\":500.0}],\"waypoints\":[]}";

        RouteUtils.OsrmResult result = RouteUtils.parseOsrmResponse(jsonResponse);

        assertNotNull("Should parse OSRM response", result);
        assertEquals("Should have all 3 geometry points", 3, result.geometry.size());
        assertEquals("Distance should be the route total, not the first leg", 500.0, result.distance, 0.1);
        assertEquals("Duration should be the route total, not the first leg", 60.0, result.duration, 0.1);
    }

    @Test
    public void testParseOsrmResponseNullAndEmpty() {
        assertNull("Should return null for null input", RouteUtils.parseOsrmResponse(null));
        assertNull("Should return null for empty input", RouteUtils.parseOsrmResponse(""));
        assertNull("Should return null for malformed JSON", RouteUtils.parseOsrmResponse("not json"));
    }

    @Test
    public void testParseOsrmResponseFailureCode() {
        String jsonResponse = "{\"code\":\"NoRoute\",\"routes\":[],\"waypoints\":[]}";

        RouteUtils.OsrmResult result = RouteUtils.parseOsrmResponse(jsonResponse);

        assertNull("Should return null for failed routing", result);
    }

    @Test
    public void testGeocodingCallbackIsNullSafe() {
        // Test that geocodeLocation handles null callback gracefully
        // Uses a short timeout to avoid hanging in test environments
        try {
            RouteUtils.geocodeLocation("Test Location", null);
            Thread.sleep(3000);
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            fail("Should not throw exception with null callback: " + e.getMessage());
        }
    }

    @Test
    public void testRouteTypeCurvatureWeights() {
        assertEquals("DIRECT should have curvature weight 0.0", 0.0, RouteType.DIRECT.getCurvatureWeight(), 0.01);
        assertEquals("FAST should have curvature weight 0.3", 0.3, RouteType.FAST.getCurvatureWeight(), 0.01);
        assertEquals("CURVY should have curvature weight 0.7", 0.7, RouteType.CURVY.getCurvatureWeight(), 0.01);
        assertEquals("EXTRA_CURVY should have curvature weight 1.0", 1.0, RouteType.EXTRA_CURVY.getCurvatureWeight(), 0.01);
        assertEquals("MOTORCYCLE should have curvature weight 0.5", 0.5, RouteType.MOTORCYCLE.getCurvatureWeight(), 0.01);
    }

    @Test
    public void testRouteTypeSpeedFactors() {
        assertEquals("DIRECT should have speed factor 1.2", 1.2, RouteType.DIRECT.getSpeedFactor(), 0.01);
        assertEquals("FAST should have speed factor 1.0", 1.0, RouteType.FAST.getSpeedFactor(), 0.01);
        assertEquals("CURVY should have speed factor 0.8", 0.8, RouteType.CURVY.getSpeedFactor(), 0.01);
        assertEquals("EXTRA_CURVY should have speed factor 0.6", 0.6, RouteType.EXTRA_CURVY.getSpeedFactor(), 0.01);
        assertEquals("MOTORCYCLE should have speed factor 1.0", 1.0, RouteType.MOTORCYCLE.getSpeedFactor(), 0.01);
    }

    @Test
    public void testRouteTypeDisplayNames() {
        assertEquals("DIRECT display name", "Direct", RouteType.DIRECT.getDisplayName());
        assertEquals("FAST display name", "Fast", RouteType.FAST.getDisplayName());
        assertEquals("CURVY display name", "Curvy", RouteType.CURVY.getDisplayName());
        assertEquals("EXTRA_CURVY display name", "Extra Curvy", RouteType.EXTRA_CURVY.getDisplayName());
    }
}