package com.motorider.models

import org.osmdroid.util.GeoPoint

class Route(
    val name: String,
    waypoints: List<Waypoint>
) {
    private var _waypoints: List<Waypoint> = emptyList<Waypoint>()

    init {
        require(waypoints != null) { "Waypoints cannot be null" }
        _waypoints = ArrayList(waypoints)
    }

    val waypoints: List<Waypoint>
        get() = _waypoints

    var distance: Double = 0.0
    var duration: Double = 0.0
    var curvatureScore: Double = 0.0
    var elevationGain: Double = 0.0
    var routeType: RouteType? = null
    var avoidances: Set<Avoidance> = emptySet()
    var routeGeometry: List<GeoPoint>? = null
}
