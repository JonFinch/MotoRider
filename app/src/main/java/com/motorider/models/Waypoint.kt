package com.motorider.models

import org.osmdroid.util.GeoPoint

data class Waypoint(
    val name: String,
    private var _location: GeoPoint
) {
    init {
        require(_location != null) { "Location cannot be null" }
    }

    var location: GeoPoint
        get() = _location
        set(value) {
            _location = value ?: throw IllegalArgumentException("Location cannot be null")
        }

    var curvature: Double = 0.0
    var elevation: Double = 0.0
    var description: String? = null
}
