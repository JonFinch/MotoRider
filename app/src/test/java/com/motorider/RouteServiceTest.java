package com.motorider.services;

import com.motorider.models.Route;
import com.motorider.models.Waypoint;
import org.junit.Test;
import org.osmdroid.util.GeoPoint;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RouteServiceTest {

    @Test
    public void testCalculateMotorcycleRoute() {
        Waypoint start = new Waypoint("Start", new GeoPoint(40.7128, -74.0060));
        Waypoint end = new Waypoint("End", new GeoPoint(41.8781, -87.6298));
        List<Waypoint> waypoints = Arrays.asList(start, end);
        
        RouteService routeService = new RouteService();
        
        Route route = routeService.calculateMotorcycleRoute(start, end, waypoints);
        
        assertNotNull("Route should not be null", route);
        assertEquals("Route name should match", "Motorcycle Route", route.getName());
        assertTrue("Distance should be greater than 0", route.getDistance() > 0);
        assertTrue("Duration should be greater than 0", route.getDuration() > 0);
    }
}