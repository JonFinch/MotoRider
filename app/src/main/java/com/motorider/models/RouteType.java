package com.motorider.models;

public enum RouteType {
    MOTORCYCLE("Motorcycle"),
    TRUCK("Truck"),
    CAR("Car"),
    BIKE("Bike"),
    DIRECT("Direct"),
    FAST("Fast"),
    CURVY("Curvy"),
    EXTRA_CURVY("Extra Curvy");

    private final String displayName;

    RouteType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getCurvatureWeight() {
        switch (this) {
            case DIRECT:
                return 0.0;
            case FAST:
                return 0.3;
            case CURVY:
                return 0.7;
            case EXTRA_CURVY:
                return 1.0;
            default:
                return 0.5;
        }
    }

    public double getSpeedFactor() {
        switch (this) {
            case DIRECT:
                return 1.2;
            case FAST:
                return 1.0;
            case CURVY:
                return 0.8;
            case EXTRA_CURVY:
                return 0.6;
            default:
                return 1.0;
        }
    }
}
