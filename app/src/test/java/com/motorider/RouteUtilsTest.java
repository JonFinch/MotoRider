package com.motorider.utils;

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
}