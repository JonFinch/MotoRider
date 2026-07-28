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