package com.motorider.models;

import org.osmdroid.util.GeoPoint;

public class Waypoint {
    private String name;
    private GeoPoint location;
    private double curvature; // degree of curve at this point
    private double elevation; // in meters
    private String description;

    public Waypoint(String name, GeoPoint location) {
        this.name = name;
        if (location == null) {
            throw new IllegalArgumentException("Location cannot be null");
        }
        this.location = location;
    }

    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public GeoPoint getLocation() { return location; }
    public void setLocation(GeoPoint location) { this.location = location; }

    public double getCurvature() { return curvature; }
    public void setCurvature(double curvature) { this.curvature = curvature; }

    public double getElevation() { return elevation; }
    public void setElevation(double elevation) { this.elevation = elevation; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}