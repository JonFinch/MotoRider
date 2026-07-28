package com.motorider.services;

import com.motorider.models.Avoidance;
import com.motorider.models.Route;
import com.motorider.models.RouteType;
import com.motorider.models.Waypoint;
import org.junit.Test;
import org.osmdroid.util.GeoPoint;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Test
    public void testCalculateRouteWithAvoidances() {
        Waypoint start = new Waypoint("Start", new GeoPoint(51.5074, -0.1278));
        Waypoint end = new Waypoint("End", new GeoPoint(48.8566, 2.3522));
        List<Waypoint> waypoints = Arrays.asList(start, end);
        
        RouteService routeService = new RouteService();
        
        Set<Avoidance> avoidances = new HashSet<>();
        avoidances.add(Avoidance.HIGHWAYS);
        avoidances.add(Avoidance.TOLLS);
        
        Route routeWithAvoidances = routeService.calculateMotorcycleRoute(
            start, end, waypoints, RouteType.FAST, avoidances);
        Route routeWithoutAvoidances = routeService.calculateMotorcycleRoute(
            start, end, waypoints, RouteType.FAST, null);
        
        assertNotNull("Route with avoidances should not be null", routeWithAvoidances);
        assertNotNull("Route without avoidances should not be null", routeWithoutAvoidances);
        
        assertTrue("Route with avoidances should store them", 
            routeWithAvoidances.getAvoidances().contains(Avoidance.HIGHWAYS));
        assertTrue("Route with avoidances should store them", 
            routeWithAvoidances.getAvoidances().contains(Avoidance.TOLLS));
        
        assertTrue("Avoidances should increase distance",
            routeWithAvoidances.getDistance() >= routeWithoutAvoidances.getDistance());
    }

    @Test
    public void testRouteWithEmptyAvoidances() {
        Waypoint start = new Waypoint("Start", new GeoPoint(51.5074, -0.1278));
        Waypoint end = new Waypoint("End", new GeoPoint(48.8566, 2.3522));
        List<Waypoint> waypoints = Arrays.asList(start, end);
        
        RouteService routeService = new RouteService();
        
        Set<Avoidance> avoidances = new HashSet<>();
        
        Route route = routeService.calculateMotorcycleRoute(
            start, end, waypoints, RouteType.DIRECT, avoidances);
        
        assertNotNull("Route should not be null", route);
        assertTrue("Empty avoidances should not affect distance", route.getDistance() > 0);
        assertTrue("Avoidances set should be empty", route.getAvoidances().isEmpty());
    }

    @Test
    public void testAvoidanceEnumDisplayNames() {
        assertEquals("HIGHWAYS display name", "Highways", Avoidance.HIGHWAYS.getDisplayName());
        assertEquals("TOLLS display name", "Toll Roads", Avoidance.TOLLS.getDisplayName());
        assertEquals("FERRIES display name", "Ferries", Avoidance.FERRIES.getDisplayName());
        assertEquals("UNPAVED_ROADS display name", "Unpaved Roads", Avoidance.UNPAVED_ROADS.getDisplayName());
        assertEquals("NARROW_ROADS display name", "Narrow Roads", Avoidance.NARROW_ROADS.getDisplayName());
    }
}