package com.motorider.models;

public enum RouteType {
    MOTORCYCLE("Motorcycle"),
    TRUCK("Truck"),
    CAR("Car"),
    BIKE("Bike");

    private final String displayName;

    RouteType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
