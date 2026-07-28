package com.motorider.services;

import com.motorider.models.Route;
import com.motorider.models.Waypoint;
import com.motorider.utils.RouteUtils;
import org.osmdroid.util.GeoPoint;
import java.util.List;

public class RouteService {
    
    /**
     * Calculate a motorcycle-friendly route with custom weights
     * @param start Starting waypoint
     * @param end Destination waypoint
     * @param waypoints Intermediate waypoints
     * @return Optimized motorcycle route
     */
    public Route calculateMotorcycleRoute(Waypoint start, Waypoint end, List<Waypoint> waypoints) {
        // Implementation of motorcycle-specific routing algorithm
        // This would include custom weights for curvature, elevation, and road types
        
        Route route = new Route("Motorcycle Route", waypoints);
        
        // Calculate route metrics
        calculateRouteMetrics(route);
        
        return route;
    }
    
    /**
     * Apply motorcycle-specific weights to routing calculations
     * @param route The route to optimize
     */
    private void calculateRouteMetrics(Route route) {
        List<Waypoint> waypoints = route.getWaypoints();
        if (waypoints == null || waypoints.isEmpty()) {
            return;
        }
        
        double totalDistance = 0.0;
        double totalDuration = 0.0;
        
        for (int i = 1; i < waypoints.size(); i++) {
            GeoPoint prev = waypoints.get(i - 1).getLocation();
            GeoPoint current = waypoints.get(i).getLocation();
            
            if (prev != null && current != null) {
                double lat1 = Math.toRadians(prev.getLatitude());
                double lat2 = Math.toRadians(current.getLatitude());
                double dLat = Math.toRadians(current.getLatitude() - prev.getLatitude());
                double dLon = Math.toRadians(current.getLongitude() - prev.getLongitude());
                
                double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(lat1) * Math.cos(lat2) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2);
                double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
                
                double segmentDistance = 6371000.0 * c;
                totalDistance += segmentDistance;
                
                double speed = 60000.0;
                totalDuration += segmentDistance / speed;
            }
        }
        
        route.setDistance(totalDistance / 1000.0);
        route.setDuration(totalDuration);
        route.setCurvatureScore(RouteUtils.calculateCurvatureScore(waypoints));
        route.setElevationGain(RouteUtils.calculateElevationGain(waypoints));
    }
}