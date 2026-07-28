package com.motorider.services;

import com.motorider.models.Route;
import com.motorider.models.Waypoint;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

public class RouteServiceTest {

    @Test
    public void testCalculateMotorcycleRoute() {
        // Create test waypoints
        Waypoint start = new Waypoint("Start", null);
        Waypoint end = new Waypoint("End", null);
        List<Waypoint> waypoints = Arrays.asList(start, end);
        
        // Create route service
        RouteService routeService = new RouteService();
        
        // Calculate route
        Route route = routeService.calculateMotorcycleRoute(start, end, waypoints);
        
        // Verify route is created
        assertNotNull("Route should not be null", route);
        assertEquals("Route name should match", "Motorcycle Route", route.getName());
    }
}