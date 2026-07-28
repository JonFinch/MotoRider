package com.motorider.models;

public enum Avoidance {
    HIGHWAYS("Highways"),
    TOLLS("Toll Roads"),
    FERRIES("Ferries"),
    UNPAVED_ROADS("Unpaved Roads"),
    NARROW_ROADS("Narrow Roads");

    private final String displayName;

    Avoidance(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
