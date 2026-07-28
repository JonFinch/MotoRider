package com.motorider.models;

import java.util.List;

public class Route {
    private String name;
    private List<Waypoint> waypoints;
    private double distance; // in kilometers
    private double duration; // in seconds
    private double curvatureScore; // 0-100 score for curvy roads
    private double elevationGain; // in meters

    public Route(String name, List<Waypoint> waypoints) {
        this.name = name;
        if (waypoints == null) {
            throw new IllegalArgumentException("Waypoints cannot be null");
        }
        this.waypoints = waypoints;
    }

    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<Waypoint> getWaypoints() { return waypoints; }
    public void setWaypoints(List<Waypoint> waypoints) { this.waypoints = waypoints; }

    public double getDistance() { return distance; }
    public void setDistance(double distance) { this.distance = distance; }

    public double getDuration() { return duration; }
    public void setDuration(double duration) { this.duration = duration; }

    public double getCurvatureScore() { return curvatureScore; }
    public void setCurvatureScore(double curvatureScore) { this.curvatureScore = curvatureScore; }

    public double getElevationGain() { return elevationGain; }
    public void setElevationGain(double elevationGain) { this.elevationGain = elevationGain; }

    private RouteType routeType;

    public RouteType getRouteType() { return routeType; }
    public void setRouteType(RouteType routeType) { this.routeType = routeType; }
}