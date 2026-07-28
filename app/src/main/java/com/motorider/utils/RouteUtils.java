package com.motorider.utils;

import com.motorider.models.Waypoint;
import java.util.List;

public class RouteUtils {
    
    /**
     * Calculate curvature score for a route based on turns
     * @param waypoints List of waypoints along the route
     * @return Curvature score between 0 and 100
     */
    public static double calculateCurvatureScore(List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.size() < 3) {
            return 0.0;
        }
        
        double totalCurvature = 0.0;
        int validPoints = 0;
        
        // Calculate curvature between consecutive waypoints
        for (int i = 1; i < waypoints.size() - 1; i++) {
            Waypoint prev = waypoints.get(i - 1);
            Waypoint current = waypoints.get(i);
            Waypoint next = waypoints.get(i + 1);
            
            // Simple curvature calculation based on angle between segments
            double curvature = calculateAngle(prev, current, next);
            totalCurvature += Math.min(curvature, 90.0); // Cap at 90 degrees
            validPoints++;
        }
        
        if (validPoints > 0) {
            return (totalCurvature / validPoints) * 100.0 / 90.0;
        }
        
        return 0.0;
    }
    
    /**
     * Calculate the angle between three points
     * @param prev Previous point
     * @param current Current point
     * @param next Next point
     * @return Angle in degrees
     */
    private static double calculateAngle(Waypoint prev, Waypoint current, Waypoint next) {
        // Simplified angle calculation
        // In a real implementation, this would use vector mathematics
        
        // For now, return a sample value
        return 45.0;
    }
    
    /**
     * Calculate elevation gain for the route
     * @param waypoints List of waypoints along the route
     * @return Total elevation gain in meters
     */
    public static double calculateElevationGain(List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.size() < 2) {
            return 0.0;
        }
        
        double elevationGain = 0.0;
        double previousElevation = waypoints.get(0).getElevation();
        
        for (int i = 1; i < waypoints.size(); i++) {
            double currentElevation = waypoints.get(i).getElevation();
            if (currentElevation > previousElevation) {
                elevationGain += (currentElevation - previousElevation);
            }
            previousElevation = currentElevation;
        }
        
        return elevationGain;
    }
}