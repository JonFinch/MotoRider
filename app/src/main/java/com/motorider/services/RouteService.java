package com.motorider.services;

import com.motorider.models.Avoidance;
import com.motorider.models.Route;
import com.motorider.models.RouteType;
import com.motorider.models.Waypoint;
import com.motorider.utils.RouteUtils;
import org.osmdroid.util.GeoPoint;
import java.util.List;
import java.util.Set;

public class RouteService {
    
    public Route calculateMotorcycleRoute(Waypoint start, Waypoint end, List<Waypoint> waypoints) {
        return calculateMotorcycleRoute(start, end, waypoints, RouteType.MOTORCYCLE, null);
    }
    
    public Route calculateMotorcycleRoute(Waypoint start, Waypoint end, List<Waypoint> waypoints, RouteType routePreference) {
        return calculateMotorcycleRoute(start, end, waypoints, routePreference, null);
    }
    
    public Route calculateMotorcycleRoute(Waypoint start, Waypoint end, List<Waypoint> waypoints, RouteType routePreference, Set<Avoidance> avoidances) {
        Route route = new Route(routePreference.getDisplayName() + " Route", waypoints);
        route.setAvoidances(avoidances);
        
        calculateRouteMetrics(route, routePreference);
        
        return route;
    }
    
    private void calculateRouteMetrics(Route route, RouteType routePreference) {
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
                
                double speed = 60000.0 * routePreference.getSpeedFactor();
                totalDuration += segmentDistance / speed;
            }
        }
        
        double avoidancePenalty = 0.0;
        Set<Avoidance> avoidances = route.getAvoidances();
        if (avoidances != null && !avoidances.isEmpty()) {
            double penaltyPerItem = 0.05 * avoidances.size();
            avoidancePenalty = Math.min(penaltyPerItem, 0.25);
        }
        
        route.setDistance((totalDistance / 1000.0) * (1.0 + avoidancePenalty));
        route.setDuration(totalDuration * (1.0 + avoidancePenalty));
        
        double baseCurvatureScore = RouteUtils.calculateCurvatureScore(waypoints);
        route.setCurvatureScore(baseCurvatureScore * routePreference.getCurvatureWeight());
        route.setElevationGain(RouteUtils.calculateElevationGain(waypoints));
    }
}