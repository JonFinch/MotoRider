package com.motorider.models;

import org.junit.Test;
import static org.junit.Assert.*;

public class RouteModelTest {

    @Test
    public void testRouteConstructor() {
        // Create a route with name and waypoints
        Route route = new Route("Test Route", null);
        
        // Verify properties
        assertEquals("Route name should match", "Test Route", route.getName());
    }

    @Test
    public void testRouteGettersAndSetters() {
        Route route = new Route("Test Route", null);
        
        // Test setting and getting name
        route.setName("New Route Name");
        assertEquals("Route name should be updated", "New Route Name", route.getName());
        
        // Test setting and getting distance
        route.setDistance(45.5);
        assertEquals("Route distance should be updated", 45.5, route.getDistance(), 0.01);
        
        // Test setting and getting duration
        route.setDuration(1800.0);
        assertEquals("Route duration should be updated", 1800.0, route.getDuration(), 0.01);
        
        // Test setting and getting curvature score
        route.setCurvatureScore(85.0);
        assertEquals("Route curvature score should be updated", 85.0, route.getCurvatureScore(), 0.01);
        
        // Test setting and getting elevation gain
        route.setElevationGain(850.0);
        assertEquals("Route elevation gain should be updated", 850.0, route.getElevationGain(), 0.01);
    }

    @Test
    public void testWaypointConstructor() {
        // Create a waypoint
        Waypoint waypoint = new Waypoint("Test Point", null);
        
        // Verify properties
        assertEquals("Waypoint name should match", "Test Point", waypoint.getName());
    }

    @Test
    public void testWaypointGettersAndSetters() {
        Waypoint waypoint = new Waypoint("Test Point", null);
        
        // Test setting and getting name
        waypoint.setName("New Point Name");
        assertEquals("Waypoint name should be updated", "New Point Name", waypoint.getName());
        
        // Test setting and getting curvature
        waypoint.setCurvature(45.0);
        assertEquals("Waypoint curvature should be updated", 45.0, waypoint.getCurvature(), 0.01);
        
        // Test setting and getting elevation
        waypoint.setElevation(100.0);
        assertEquals("Waypoint elevation should be updated", 100.0, waypoint.getElevation(), 0.01);
        
        // Test setting and getting description
        waypoint.setDescription("Test description");
        assertEquals("Waypoint description should be updated", "Test description", waypoint.getDescription());
    }
}