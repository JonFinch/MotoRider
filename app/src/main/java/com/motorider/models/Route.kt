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
    var routeScore: Double = 0.0
    var routeType: RouteType? = null
    var avoidances: Set<Avoidance> = emptySet()
    var routeGeometry: List<GeoPoint>? = null
    var curvatureMetadata: CurvatureMetadata? = null

    /**
     * True when this is NOT a real route: the routing API could not be reached, so
     * distance/duration are straight-line estimates and the geometry is just the
     * waypoints joined by lines. Must be surfaced to the rider - following one of
     * these would mean riding across whatever lies between the points.
     */
    var isEstimate: Boolean = false
    var estimateReason: String? = null

    /**
     * False when the routing service could not find any route that fully honours
     * the requested avoidances and downgraded them to penalties instead. A ticked
     * "Avoid Ferries" that was silently ignored is the exact failure the avoidance
     * feature exists to prevent, so this must reach the rider.
     */
    var avoidancesHonoured: Boolean = true

    /** False when curvature data was unavailable, making the curvature figures meaningless. */
    var curvatureAvailable: Boolean = true

    /** Server-supplied explanation for either of the above. */
    var serviceWarning: String? = null

    /** Turn-by-turn navigation instructions derived from the route geometry. */
    var turnInstructions: List<TurnInstruction>? = null

    data class CurvatureMetadata(
        val totalCurvature: Double = 0.0,
        val curvaturePerKm: Double = 0.0,
        val curviestSegmentWayId: Long = 0,
        val curviestSegmentCurvature: Double = 0.0,
        val segmentsAnalyzed: Int = 0,
        val segmentsWithCurvature: Int = 0
    )
}
