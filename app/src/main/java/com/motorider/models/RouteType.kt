package com.motorider.models

enum class RouteType(val displayName: String, val apiValue: String) {
    DIRECT("Direct", "direct"),
    FAST("Fast", "fast_curvy"),
    CURVY("Curvy", "curvy"),
    EXTRA_CURVY("Extra Curvy", "extra_curvy");

    fun getSpeedFactor(): Double = when (this) {
        DIRECT -> 1.2
        FAST -> 1.0
        CURVY -> 0.8
        EXTRA_CURVY -> 0.6
    }
}
