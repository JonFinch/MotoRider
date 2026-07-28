package com.motorider.models;

import org.junit.Test;
import org.osmdroid.util.GeoPoint;
import static org.junit.Assert.*;

public class RouteModelTest {

    @Test
    public void testRouteConstructor() {
        Route route = new Route("Test Route", java.util.Collections.emptyList());
        
        assertEquals("Route name should match", "Test Route", route.getName());
    }

    @Test
    public void testRouteGettersAndSetters() {
        Route route = new Route("Test Route", java.util.Collections.emptyList());
        
        route.setName("New Route Name");
        assertEquals("Route name should be updated", "New Route Name", route.getName());
        
        route.setDistance(45.5);
        assertEquals("Route distance should be updated", 45.5, route.getDistance(), 0.01);
        
        route.setDuration(1800.0);
        assertEquals("Route duration should be updated", 1800.0, route.getDuration(), 0.01);
        
        route.setCurvatureScore(85.0);
        assertEquals("Route curvature score should be updated", 85.0, route.getCurvatureScore(), 0.01);
        
        route.setElevationGain(850.0);
        assertEquals("Route elevation gain should be updated", 850.0, route.getElevationGain(), 0.01);
    }

    @Test
    public void testWaypointConstructor() {
        Waypoint waypoint = new Waypoint("Test Point", new GeoPoint(40.7128, -74.0060));
        
        assertEquals("Waypoint name should match", "Test Point", waypoint.getName());
        assertNotNull("Location should not be null", waypoint.getLocation());
    }

    @Test
    public void testWaypointGettersAndSetters() {
        Waypoint waypoint = new Waypoint("Test Point", new GeoPoint(40.7128, -74.0060));
        
        waypoint.setName("New Point Name");
        assertEquals("Waypoint name should be updated", "New Point Name", waypoint.getName());
        
        waypoint.setCurvature(45.0);
        assertEquals("Waypoint curvature should be updated", 45.0, waypoint.getCurvature(), 0.01);
        
        waypoint.setElevation(100.0);
        assertEquals("Waypoint elevation should be updated", 100.0, waypoint.getElevation(), 0.01);
        
        waypoint.setDescription("Test description");
        assertEquals("Waypoint description should be updated", "Test description", waypoint.getDescription());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testWaypointWithNullLocation() {
        new Waypoint("Test Point", null);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testRouteWithNullWaypoints() {
        new Route("Test Route", null);
    }
}