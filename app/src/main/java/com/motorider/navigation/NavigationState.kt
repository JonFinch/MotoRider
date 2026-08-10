package com.motorider.navigation

import com.motorider.models.TurnInstruction
import com.motorider.models.NavigationWarning
import org.osmdroid.util.GeoPoint

enum class NavigationState { IDLE, NAVIGATING, PAUSED, ARRIVED }

/**
 * Everything the navigation UI renders.
 *
 * Units are SI throughout: metres, seconds, metres per second. [eta] is the one
 * exception and is an absolute epoch timestamp, not a duration.
 */
data class NavigationUIState(
    val state: NavigationState = NavigationState.IDLE,
    /** Fraction of the route completed, 0.0 to 1.0. */
    val progress: Double = 0.0,
    /** Metres still to ride. */
    val distanceRemaining: Double = 0.0,
    /** Seconds still to ride. */
    val timeRemaining: Double = 0.0,
    /** Estimated arrival time, epoch milliseconds. */
    val eta: Long? = null,
    /** Metres per second. */
    val currentSpeed: Float = 0f,
    /**
     * Where to draw the rider. This is the position snapped onto the route rather
     * than the raw fix, so the marker tracks the road instead of wobbling with GPS
     * noise; it falls back to the raw fix when off route.
     */
    val position: GeoPoint? = null,
    /** Raw GPS fix, unsnapped — for anything that must not lie about position. */
    val rawPosition: GeoPoint? = null,
    /** Direction of travel in degrees clockwise from north. */
    val bearing: Float = 0f,
    /**
     * Geometry of the route actually being ridden. Republished here because after an
     * off-route detour this differs from the route the planning screen holds, and the
     * map must draw the line the rider is being sent along.
     */
    val routeGeometry: List<GeoPoint>? = null,
    val currentInstruction: TurnInstruction? = null,
    /**
     * The manoeuvre after [currentInstruction] — but only when it follows closely
     * enough that the two are effectively one action ("left, then immediately
     * right"), and only once the rider is near enough to the first to care. Null
     * the rest of the time, which is most of the time.
     *
     * This replaced an `upcomingInstructions` list that nothing ever rendered. Two
     * of the three reasons for not building that list have since gone away —
     * manoeuvres now come from the routing service, so they are real junctions and
     * they carry road names. The reason that remains is the decisive one: a
     * scrollable list is not something a rider reads at speed, in gloves. One line
     * for the case they genuinely cannot react to unaided is the useful part of it.
     */
    val followOnInstruction: TurnInstruction? = null,
    val warnings: List<NavigationWarning> = emptyList(),
    val isRecalculating: Boolean = false,
    val isOffRoute: Boolean = false,
    val isGpsLost: Boolean = false,
    val isTtsEnabled: Boolean = true,
    /** Index of the waypoint being ridden towards. */
    val currentWaypointIndex: Int = 0,
    val totalWaypoints: Int = 1,
    val skipAvailableWaypointName: String? = null
)
