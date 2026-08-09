package com.motorider.models

/**
 * A single manoeuvre along a route.
 *
 * Two different distances live here and they are easy to confuse:
 *  - [distanceAlongRoute] is fixed at route-creation time — how far the manoeuvre
 *    sits from the *start* of the route.
 *  - [distanceToManeuver] is live — how far the *rider* still has to go before
 *    reaching it. It is 0 in the stored list and filled in by NavigationManager
 *    on every GPS fix.
 */
data class TurnInstruction(
    val maneuverType: ManeuverType,
    val instruction: String,
    /** Metres from the rider's current position to this manoeuvre (live). */
    val distanceToManeuver: Double,
    /** Metres from the start of the route to this manoeuvre (fixed). */
    val distanceAlongRoute: Double,
    /** Metres from this manoeuvre to the destination (fixed). */
    val distanceRemaining: Double,
    /** Seconds from this manoeuvre to the destination (fixed). */
    val timeRemaining: Double,
    /** Compass bearing to travel after the manoeuvre, degrees clockwise from north. */
    val bearing: Double,
    /** Index into the route geometry where this manoeuvre occurs. */
    val segmentIndex: Int
)

enum class ManeuverType {
    DEPART,
    CONTINUE,
    TURN_LEFT,
    TURN_RIGHT,
    TURN_SLIGHT_LEFT,
    TURN_SLIGHT_RIGHT,
    UTURN,
    ARRIVE,
    WAYPOINT_ARRIVED
}
