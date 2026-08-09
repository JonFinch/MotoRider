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
    val currentInstruction: TurnInstruction? = null,
    val upcomingInstructions: List<TurnInstruction> = emptyList(),
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
