package com.motorider.models

enum class Avoidance(val displayName: String, val apiValue: String? = null) {
    HIGHWAYS("Highways", "motorway"),
    TOLLS("Toll Roads", "toll"),
    FERRIES("Ferries", "ferry"),
    UNPAVED_ROADS("Unpaved Roads", "unpaved"),

    // Labelled for what it actually does. It was "Narrow Roads", but the rule
    // behind it blocks tracks and service roads - it cannot avoid the single-track
    // lanes riders mean by "narrow", because those are tagged `unclassified` and
    // `residential` in OSM with no dependable width tag. Promising narrow-road
    // avoidance and delivering track avoidance is worse than not offering it.
    TRACKS_AND_SERVICE("Tracks & Service Roads", "narrow");
}
