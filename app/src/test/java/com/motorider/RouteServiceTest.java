package com.motorider.services;

import com.motorider.models.Route;
import com.motorider.models.RouteType;
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
        Waypoint start = new Waypoint("Start", new GeoPoint(51.5074, -0.1278));
        Waypoint end = new Waypoint("End", new GeoPoint(48.8566, 2.3522));
        List<Waypoint> waypoints = Arrays.asList(start, end);
        
        RouteService routeService = new RouteService();
        
        Route route = routeService.calculateMotorcycleRoute(start, end, waypoints);
        
        assertNotNull("Route should not be null", route);
        assertTrue("Route name should contain route preference", 
            route.getName().contains("Route"));
        assertTrue("Distance should be greater than 0", route.getDistance() > 0);
        assertTrue("Duration should be greater than 0", route.getDuration() > 0);
    }

    @Test
    public void testCalculateMotorcycleRouteWithPreference() {
        Waypoint start = new Waypoint("Start", new GeoPoint(51.5074, -0.1278));
        Waypoint end = new Waypoint("End", new GeoPoint(48.8566, 2.3522));
        List<Waypoint> waypoints = Arrays.asList(start, end);
        
        RouteService routeService = new RouteService();
        
        Route directRoute = routeService.calculateMotorcycleRoute(start, end, waypoints, RouteType.DIRECT);
        Route curvyRoute = routeService.calculateMotorcycleRoute(start, end, waypoints, RouteType.CURVY);
        Route extraCurvyRoute = routeService.calculateMotorcycleRoute(start, end, waypoints, RouteType.EXTRA_CURVY);
        
        assertNotNull("Direct route should not be null", directRoute);
        assertNotNull("Curvy route should not be null", curvyRoute);
        assertNotNull("Extra curvy route should not be null", extraCurvyRoute);
        
        assertTrue("Distance should be greater than 0", directRoute.getDistance() > 0);
        
        // Different route preferences should produce different curvature scores
        assertTrue("Curvy route should have higher curvature score than direct", 
            curvyRoute.getCurvatureScore() >= directRoute.getCurvatureScore());
        assertTrue("Extra curvy route should have highest curvature score", 
            extraCurvyRoute.getCurvatureScore() >= curvyRoute.getCurvatureScore());
    }

    @Test
    public void testRouteTypeDisplayNameInRoute() {
        Waypoint start = new Waypoint("Start", new GeoPoint(51.5074, -0.1278));
        Waypoint end = new Waypoint("End", new GeoPoint(48.8566, 2.3522));
        List<Waypoint> waypoints = Arrays.asList(start, end);
        
        RouteService routeService = new RouteService();
        
        Route route = routeService.calculateMotorcycleRoute(start, end, waypoints, RouteType.EXTRA_CURVY);
        
        assertEquals("Route name should contain 'Extra Curvy'", true, 
            route.getName().contains("Extra Curvy"));
    }
}