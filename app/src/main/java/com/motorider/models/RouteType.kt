package com.motorider.models

enum class RouteType(val displayName: String) {
    MOTORCYCLE("Motorcycle"),
    TRUCK("Truck"),
    CAR("Car"),
    BIKE("Bike"),
    DIRECT("Direct"),
    FAST("Fast"),
    CURVY("Curvy"),
    EXTRA_CURVY("Extra Curvy");

    fun getCurvatureWeight(): Double = when (this) {
        DIRECT -> 0.0
        FAST -> 0.3
        CURVY -> 0.7
        EXTRA_CURVY -> 1.0
        else -> 0.5
    }

    fun getSpeedFactor(): Double = when (this) {
        DIRECT -> 1.2
        FAST -> 1.0
        CURVY -> 0.8
        EXTRA_CURVY -> 0.6
        else -> 1.0
    }
}
