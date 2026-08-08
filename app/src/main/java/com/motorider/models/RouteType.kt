package com.motorider.models

enum class RouteType(val displayName: String, val shortLabel: String, val apiValue: String) {
    DIRECT("Direct", "Direct", "direct"),
    FAST("Fast", "Fast", "fast_curvy"),
    CURVY("Curvy", "Curvy", "curvy"),
    // Short label keeps the segmented ride-style selector readable on narrow phones.
    EXTRA_CURVY("Extra Curvy", "Extra", "extra_curvy");

    fun getSpeedFactor(): Double = when (this) {
        DIRECT -> 1.2
        FAST -> 1.0
        CURVY -> 0.8
        EXTRA_CURVY -> 0.6
    }
}
