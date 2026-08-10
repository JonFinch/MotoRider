package com.motorider.navigation

import android.util.Log
import com.motorider.models.ManeuverType
import com.motorider.models.NavigationWarning
import com.motorider.models.Route
import com.motorider.models.TurnInstruction
import com.motorider.models.Waypoint
import com.motorider.models.WarningSeverity
import com.motorider.models.WarningType
import com.motorider.navigation.NavigationState.ARRIVED
import com.motorider.navigation.NavigationState.IDLE
import com.motorider.navigation.NavigationState.NAVIGATING
import com.motorider.navigation.NavigationState.PAUSED
import com.motorider.services.LocationResult
import com.motorider.utils.NearestPointResult
import com.motorider.utils.OffRouteDetector
import com.motorider.utils.calculateCumulativeDistances
import com.motorider.utils.distanceToMeters
import com.motorider.utils.RouteUtils
import com.motorider.utils.nearestPointOnPolyline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.util.GeoPoint

private const val TAG = "NavigationManager"

data class NavigationConfig(
    val offRouteThresholdMeters: Float = 50f,
    val offRouteConfirmationCount: Int = 3,
    val gpsLossTimeoutSeconds: Long = 60,
    /** Distance to the destination at which the ride counts as finished. */
    val arrivalRadiusMeters: Double = 40.0,
    /** How close to an intermediate waypoint the "skip" button appears. */
    val skipOfferRadiusMeters: Double = 500.0,
    /**
     * How short of a waypoint counts as having reached it. Route geometry rarely
     * passes exactly through a requested waypoint, so an exact test would leave one
     * permanently outstanding and a ride unable to finish.
     */
    val waypointReachedToleranceMeters: Double = 30.0,
    /** How far along the route to aim when steering a strayed rider back onto it. */
    val rejoinLookaheadMeters: Double = 250.0,
    val ttsTriggerZones: Map<ManeuverType, Double> = defaultTtsTriggerZones()
)

fun defaultTtsTriggerZones(): Map<ManeuverType, Double> = mapOf(
    ManeuverType.DEPART to 0.0,
    ManeuverType.ARRIVE to 100.0,
    ManeuverType.WAYPOINT_ARRIVED to 100.0,
    ManeuverType.CONTINUE to 300.0,
    ManeuverType.TURN_LEFT to 150.0,
    ManeuverType.TURN_RIGHT to 150.0,
    ManeuverType.TURN_SLIGHT_LEFT to 100.0,
    ManeuverType.TURN_SLIGHT_RIGHT to 100.0,
    ManeuverType.UTURN to 100.0
)

/**
 * Owns the live navigation state machine.
 *
 * Everything it publishes in [NavigationUIState] is SI: metres and seconds. The
 * [Route] model stores kilometres and minutes, so conversions happen here, at the
 * boundary, and nowhere else.
 */
class NavigationManager(
    private val config: NavigationConfig = NavigationConfig()
) {
    private val _uiState = MutableStateFlow(NavigationUIState(state = IDLE))
    val uiState: StateFlow<NavigationUIState> = _uiState.asStateFlow()

    private val _locationFlow = MutableStateFlow<LocationResult?>(null)
    val locationFlow: StateFlow<LocationResult?> = _locationFlow.asStateFlow()

    private val offRouteDetector = OffRouteDetector(
        config.offRouteThresholdMeters, config.offRouteConfirmationCount
    )

    private var currentRoute: Route? = null
    private var cumulativeDistances: DoubleArray = DoubleArray(1)
    /** Distance along the route of each waypoint, parallel to `route.waypoints`. */
    private var waypointDistances: DoubleArray = DoubleArray(0)
    /** Which waypoints the rider has ridden past, in order. */
    private var waypointReached: BooleanArray = BooleanArray(0)
    private var lastSegmentIndex: Int = 0
    private var lastPosition: GeoPoint? = null
    private var lastSpeedMps: Float = 0f
    private var lastBearingDegrees: Float = 0f
    private var lastFixElapsedMs: Long = 0
    private var lastSpokenKey: String? = null
    private var lastSpokenAtMs: Long = 0
    private var ttsManager: TTSManager? = null
    private var ttsEnabled: Boolean = true

    /** Injectable clock so the state machine can be tested without waiting in real time. */
    var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    val route: Route? get() = currentRoute

    /** The most recent fix, as plain coordinates. */
    val currentPosition: GeoPoint? get() = lastPosition

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun startNavigation(route: Route): Boolean {
        val geometry = route.routeGeometry
        if (geometry == null || geometry.size < 2) {
            Log.w(TAG, "Route has no usable geometry, cannot start navigation")
            return false
        }

        bindRoute(route)
        offRouteDetector.reset()
        lastFixElapsedMs = 0
        lastSpokenKey = null
        lastSpokenAtMs = 0

        _uiState.value = NavigationUIState(
            state = NAVIGATING,
            progress = 0.0,
            distanceRemaining = route.distance * 1000.0,
            timeRemaining = route.duration * 60.0,
            totalWaypoints = route.waypoints.size,
            currentWaypointIndex = 0,
            isTtsEnabled = ttsEnabled
        )

        Log.d(TAG, "Navigation started: ${route.name}, ${route.distance} km")
        return true
    }

    /**
     * Swap in a freshly calculated route (after a recalculation or a skipped
     * waypoint) without leaving the NAVIGATING state.
     */
    fun replaceRoute(route: Route): Boolean {
        val geometry = route.routeGeometry
        if (geometry == null || geometry.size < 2) {
            Log.w(TAG, "Replacement route has no usable geometry")
            return false
        }

        bindRoute(route)
        offRouteDetector.reset()

        _uiState.value = _uiState.value.copy(
            state = NAVIGATING,
            isRecalculating = false,
            isOffRoute = false,
            progress = 0.0,
            distanceRemaining = route.distance * 1000.0,
            timeRemaining = route.duration * 60.0,
            totalWaypoints = route.waypoints.size,
            currentWaypointIndex = 0,
            currentInstruction = null,
            followOnInstruction = null,
            warnings = emptyList()
        )

        // Re-derive from the current fix straight away rather than showing stale
        // figures until the next one arrives.
        updateNavigationState()
        return true
    }

    private fun bindRoute(route: Route) {
        val geometry = route.routeGeometry ?: return
        currentRoute = route
        cumulativeDistances = calculateCumulativeDistances(geometry)

        // Match each waypoint to the geometry *after* the previous one, never to the
        // globally nearest vertex. A round trip starts and finishes at the same place,
        // so a global search maps its final waypoint back to index 0 — the route then
        // looks zero-length and the ride reports itself finished before it begins.
        waypointDistances = DoubleArray(route.waypoints.size)
        var searchFrom = 0
        for (wpIdx in route.waypoints.indices) {
            val target = route.waypoints[wpIdx].location
            var bestIndex = searchFrom
            var bestDist = Double.MAX_VALUE
            for (i in searchFrom until geometry.size) {
                val d = target.distanceToMeters(geometry[i])
                if (d < bestDist) {
                    bestDist = d
                    bestIndex = i
                }
            }
            waypointDistances[wpIdx] = cumulativeDistances[bestIndex]
            // Leave room for the remaining waypoints so the last one cannot consume
            // the whole polyline and strand those after it.
            val remaining = route.waypoints.size - wpIdx - 1
            searchFrom = minOf(bestIndex + 1, (geometry.size - 1 - remaining).coerceAtLeast(0))
        }

        waypointReached = BooleanArray(route.waypoints.size)
        // The rider is standing at the first waypoint when the ride starts.
        if (waypointReached.isNotEmpty()) waypointReached[0] = true
        lastSegmentIndex = 0
    }

    fun setRecalculating(recalculating: Boolean) {
        _uiState.value = _uiState.value.copy(isRecalculating = recalculating)
    }

    fun pauseNavigation() {
        if (_uiState.value.state != NAVIGATING) return
        _uiState.value = _uiState.value.copy(state = PAUSED)
        Log.d(TAG, "Navigation paused")
    }

    fun resumeNavigation() {
        if (_uiState.value.state != PAUSED) return
        _uiState.value = _uiState.value.copy(state = NAVIGATING)
        // The rider may have moved while paused; refresh before the next fix.
        updateNavigationState()
        Log.d(TAG, "Navigation resumed")
    }

    fun endNavigation() {
        currentRoute = null
        cumulativeDistances = DoubleArray(1)
        waypointDistances = DoubleArray(0)
        waypointReached = BooleanArray(0)
        lastSegmentIndex = 0
        offRouteDetector.reset()
        lastSpokenKey = null
        lastSpokenAtMs = 0
        ttsManager?.stop()

        _uiState.value = NavigationUIState(state = IDLE, isTtsEnabled = ttsEnabled)
        Log.d(TAG, "Navigation ended")
    }

    // ─── Inputs ──────────────────────────────────────────────────────────────

    fun setLocation(location: LocationResult?) {
        _locationFlow.value = location
        if (location != null) {
            setPosition(
                GeoPoint(location.location.latitude, location.location.longitude),
                location.speed,
                location.bearing
            )
        }
    }

    /**
     * Feed a fix as plain coordinates.
     *
     * [setLocation] delegates here so the state machine never touches Android's
     * `Location` class, which keeps it exercisable off-device.
     */
    @JvmOverloads
    fun setPosition(point: GeoPoint, speedMps: Float, bearingDegrees: Float = 0f) {
        lastPosition = point
        lastSpeedMps = speedMps
        lastBearingDegrees = bearingDegrees
        lastFixElapsedMs = clock()
        updateNavigationState()
    }

    /**
     * Re-evaluate time-based state (GPS loss) without a new fix.
     *
     * GPS loss is the absence of updates, so it can only ever be noticed by
     * something that ticks independently of the location callback.
     */
    fun tick() {
        if (_uiState.value.state != NAVIGATING) return
        val lost = isGpsLost()
        if (lost != _uiState.value.isGpsLost) {
            _uiState.value = _uiState.value.copy(
                isGpsLost = lost,
                warnings = buildWarnings(isOffRoute = _uiState.value.isOffRoute, isGpsLost = lost)
            )
        }
    }

    fun setTTSManager(ttsManager: TTSManager?) {
        this.ttsManager = ttsManager
    }

    fun setTTSEnabled(enabled: Boolean) {
        ttsEnabled = enabled
        if (!enabled) ttsManager?.stop()
        _uiState.value = _uiState.value.copy(isTtsEnabled = enabled)
        Log.d(TAG, "TTS ${if (enabled) "enabled" else "disabled"}")
    }

    fun isTTSEnabled(): Boolean = ttsEnabled

    // ─── Waypoint skipping ───────────────────────────────────────────────────

    /**
     * The waypoints a recalculated route should visit if the rider skips the next
     * intermediate one, or null when there is nothing skippable.
     *
     * The manager does not fetch routes itself — the caller runs the request and
     * hands the result back through [replaceRoute].
     */
    fun waypointsAfterSkip(): List<Waypoint>? {
        val route = currentRoute ?: return null
        if (_uiState.value.state != NAVIGATING) return null

        val skipIndex = _uiState.value.currentWaypointIndex
        if (skipIndex <= 0 || skipIndex >= route.waypoints.size - 1) {
            Log.w(TAG, "Cannot skip: no intermediate waypoint is current")
            return null
        }

        val remaining = route.waypoints.filterIndexed { index, _ -> index > skipIndex }
        if (remaining.isEmpty()) return null

        Log.d(TAG, "Skipping waypoint: ${route.waypoints[skipIndex].name}")
        return remaining
    }

    /** A point on the planned route to steer back to after straying off it. */
    data class RejoinTarget(
        val point: GeoPoint,
        val vertexIndex: Int,
        val distanceAlongRoute: Double
    )

    /**
     * Where to rejoin the planned route, [lookaheadMeters] further on than the point
     * the rider is currently nearest to.
     *
     * The lookahead matters: aiming at the nearest point would often route the rider
     * back the way they came, or into the junction they have just overshot. Aiming a
     * little further along sends them forward onto the route instead.
     */
    fun rejoinTarget(lookaheadMeters: Double = config.rejoinLookaheadMeters): RejoinTarget? {
        val geometry = currentRoute?.routeGeometry ?: return null
        val position = lastPosition ?: return null
        if (geometry.size < 2 || cumulativeDistances.size != geometry.size) return null

        val nearest = nearestPointOnPolyline(position, geometry, cumulativeDistances)
        val targetDistance = nearest.distanceAlongRoute + lookaheadMeters

        val index = cumulativeDistances.indexOfFirst { it >= targetDistance }
            .takeIf { it >= 0 } ?: geometry.lastIndex

        return RejoinTarget(geometry[index], index, cumulativeDistances[index])
    }

    /**
     * Stitch a detour that reaches [rejoin] onto the rest of the planned route.
     *
     * Off-route recovery deliberately preserves the original ride rather than
     * replanning end-to-end: the rider chose a curvy route, and routing them straight
     * to the destination from wherever they strayed would quietly discard it.
     */
    fun buildRejoinRoute(detour: Route, rejoin: RejoinTarget): Route? {
        val route = currentRoute ?: return null
        val geometry = route.routeGeometry ?: return null
        val detourGeometry = detour.routeGeometry
        if (detourGeometry.isNullOrEmpty()) return null
        if (rejoin.vertexIndex !in geometry.indices) return null
        if (cumulativeDistances.size != geometry.size) return null

        // Drop the detour's final point if it is the rejoin vertex, so the seam does
        // not leave a zero-length segment for the instruction generator to trip on.
        val head = if (detourGeometry.last().distanceToMeters(geometry[rejoin.vertexIndex]) < 1.0) {
            detourGeometry.dropLast(1)
        } else {
            detourGeometry
        }
        val combined = head + geometry.subList(rejoin.vertexIndex, geometry.size)
        if (combined.size < 2) return null

        val totalDistance = cumulativeDistances.last()
        val remainderMeters = (totalDistance - rejoin.distanceAlongRoute).coerceAtLeast(0.0)
        val remainderMinutes = if (totalDistance > 0) {
            route.duration * (remainderMeters / totalDistance)
        } else 0.0

        val ahead = route.waypoints.filterIndexed { i, _ ->
            waypointDistances.getOrElse(i) { 0.0 } > rejoin.distanceAlongRoute
        }.ifEmpty { listOfNotNull(route.waypoints.lastOrNull()) }

        val waypoints = listOf(Waypoint("Current position", head.first())) + ahead
        val durationMinutes = detour.duration + remainderMinutes

        return Route(route.name, waypoints).apply {
            routeGeometry = combined
            distance = detour.distance + remainderMeters / 1000.0
            duration = durationMinutes
            routeType = route.routeType
            avoidances = route.avoidances
            curvatureScore = route.curvatureScore
            turnInstructions = RouteUtils.generateTurnInstructions(
                combined, waypoints, durationMinutes * 60.0
            )
        }
    }

    /** Waypoints still ahead of the rider, for rebuilding a route on recalculation. */
    fun remainingWaypoints(): List<Waypoint> {
        val route = currentRoute ?: return emptyList()
        val current = _uiState.value.currentWaypointIndex
        return route.waypoints.filterIndexed { index, _ -> index > current }
            .ifEmpty { listOfNotNull(route.waypoints.lastOrNull()) }
    }

    // ─── Core update ─────────────────────────────────────────────────────────

    private fun updateNavigationState() {
        if (_uiState.value.state != NAVIGATING) return

        val position = lastPosition ?: return
        val route = currentRoute ?: return
        val geometry = route.routeGeometry ?: return
        if (geometry.size < 2 || cumulativeDistances.size != geometry.size) return

        val nearest = snapToRoute(position, geometry)
        lastSegmentIndex = nearest.segmentIndex

        val totalDistance = cumulativeDistances.last()
        val distanceRemaining = (totalDistance - nearest.distanceAlongRoute).coerceAtLeast(0.0)
        val progress = if (totalDistance > 0) {
            (nearest.distanceAlongRoute / totalDistance).coerceIn(0.0, 1.0)
        } else 0.0
        val timeRemaining = (route.duration * 60.0 * (1.0 - progress)).coerceAtLeast(0.0)
        val eta = if (timeRemaining > 0) {
            System.currentTimeMillis() + (timeRemaining * 1000.0).toLong()
        } else null

        val instructions = liveInstructions(route, nearest.distanceAlongRoute)
        val currentInstruction = instructions.firstOrNull()
        maybeSpeakInstruction(currentInstruction)

        val isOff = offRouteDetector.update(nearest.distance.toFloat())
        val isGps = isGpsLost()

        val waypointIndex = computeWaypointIndex(nearest.distanceAlongRoute)
        val skipName = route.waypoints.getOrNull(waypointIndex)
            ?.takeIf {
                waypointIndex > 0 && waypointIndex < route.waypoints.size - 1 &&
                    Math.abs(waypointDistances[waypointIndex] - nearest.distanceAlongRoute) <=
                        config.skipOfferRadiusMeters
            }?.name

        markWaypointsReached(nearest.distanceAlongRoute)

        // Arrival needs the whole route ridden AND every waypoint visited in turn.
        // Proximity to the final point alone is not enough: a round trip finishes
        // where it starts, so that test is true before the rider has moved. Requiring
        // the waypoints in order is also what stops a route that doubles back on
        // itself from being declared finished on the outbound leg.
        val arrived = distanceRemaining <= config.arrivalRadiusMeters &&
            waypointReached.all { it }

        _uiState.value = _uiState.value.copy(
            state = if (arrived) ARRIVED else NAVIGATING,
            progress = if (arrived) 1.0 else progress,
            distanceRemaining = distanceRemaining,
            timeRemaining = timeRemaining,
            eta = eta,
            currentSpeed = lastSpeedMps,
            // Draw the rider on the route line when they are on it; a raw fix
            // wanders across neighbouring streets at typical GPS accuracy.
            position = if (isOff) position else nearest.point,
            rawPosition = position,
            bearing = lastBearingDegrees,
            routeGeometry = geometry,
            currentInstruction = if (arrived) {
                route.turnInstructions?.lastOrNull { it.maneuverType == ManeuverType.ARRIVE }
            } else currentInstruction,
            // Nothing follows arriving.
            followOnInstruction = if (arrived) {
                null
            } else {
                followOnInstruction(currentInstruction, instructions.getOrNull(1))
            },
            warnings = buildWarnings(isOff, isGps),
            isOffRoute = isOff,
            isGpsLost = isGps,
            currentWaypointIndex = waypointIndex,
            skipAvailableWaypointName = skipName
        )

        if (arrived) {
            ttsManager?.takeIf { ttsEnabled }?.speak("You have arrived")
            Log.d(TAG, "Navigation arrived at destination")
        }
    }

    /**
     * Snap the fix to the route, preferring a window around the last known position.
     *
     * An unconstrained nearest-point search snaps to whichever part of the polyline
     * happens to be closest, which on a round trip can be the return leg running
     * alongside the outbound one — reporting the rider as nearly home before they
     * have left. The window keeps progress monotonic; the full search is only used
     * when the rider is genuinely nowhere near the expected stretch.
     */
    private fun snapToRoute(position: GeoPoint, geometry: List<GeoPoint>): NearestPointResult {
        val windowed = nearestPointOnPolyline(
            position, geometry, cumulativeDistances,
            searchFrom = lastSegmentIndex - SNAP_WINDOW_BACK,
            searchTo = lastSegmentIndex + SNAP_WINDOW_FORWARD
        )
        if (windowed.distance <= config.offRouteThresholdMeters) return windowed

        val global = nearestPointOnPolyline(position, geometry, cumulativeDistances)
        return if (global.distance < windowed.distance) global else windowed
    }

    /**
     * The manoeuvres still ahead of the rider, nearest first, each carrying a live
     * distance from the rider's current position.
     */
    /**
     * The "then" manoeuvre, or null when there is nothing worth saying.
     *
     * Two conditions, and both matter:
     *
     *  - the rider is **approaching** the current manoeuvre. A follow-on announced
     *    2 km out is not information, it is clutter — they will be told again when
     *    it is relevant.
     *  - the follow-on lands **soon after** it. This is the whole point: a junction
     *    pair close enough that the second cannot be reacted to on its own once the
     *    first is being ridden.
     *
     * Deliberately distance-based rather than time-based. A speed-derived window
     * would make the line appear and vanish as the rider slows for the very turn it
     * describes, and a flickering banner is worse than a slightly conservative one.
     */
    private fun followOnInstruction(
        current: TurnInstruction?,
        next: TurnInstruction?
    ): TurnInstruction? {
        if (current == null || next == null) return null
        if (current.distanceToManeuver > FOLLOW_ON_APPROACH_METERS) return null
        val gap = next.distanceToManeuver - current.distanceToManeuver
        if (gap > FOLLOW_ON_GAP_METERS) return null
        return next
    }

    private fun liveInstructions(route: Route, distanceAlongRoute: Double): List<TurnInstruction> {
        val instructions = route.turnInstructions ?: return emptyList()
        return instructions
            .filter { it.distanceAlongRoute >= distanceAlongRoute - PASSED_MANEUVER_TOLERANCE_METERS }
            .map { it.copy(distanceToManeuver = (it.distanceAlongRoute - distanceAlongRoute).coerceAtLeast(0.0)) }
    }

    private fun buildWarnings(isOffRoute: Boolean, isGpsLost: Boolean): List<NavigationWarning> {
        val warnings = mutableListOf<NavigationWarning>()
        if (isGpsLost) {
            warnings.add(
                NavigationWarning(WarningType.GPS_LOST, "GPS signal lost", WarningSeverity.CRITICAL)
            )
        }
        if (isOffRoute) {
            warnings.add(
                NavigationWarning(WarningType.OFF_ROUTE, "You are off route", WarningSeverity.WARNING)
            )
        }
        return warnings
    }

    private fun maybeSpeakInstruction(instruction: TurnInstruction?) {
        if (!ttsEnabled || instruction == null) return
        val tts = ttsManager?.takeIf { it.isAvailable() } ?: return

        val triggerZone = config.ttsTriggerZones[instruction.maneuverType] ?: return
        if (instruction.distanceToManeuver > triggerZone) return

        // One announcement per manoeuvre. Without this, every fix inside the trigger
        // zone would re-speak it; a fixed cooldown alone would also swallow a second,
        // genuinely different manoeuvre arriving moments later.
        val key = "${instruction.segmentIndex}:${instruction.instruction}"
        val now = clock()
        if (key == lastSpokenKey && now - lastSpokenAtMs < REPEAT_SUPPRESSION_MS) return

        tts.speak(instruction.instruction)
        lastSpokenKey = key
        lastSpokenAtMs = now
        Log.d(TAG, "TTS: ${instruction.instruction}")
    }

    private fun isGpsLost(): Boolean {
        if (lastFixElapsedMs == 0L) return false
        return (clock() - lastFixElapsedMs) / 1000L > config.gpsLossTimeoutSeconds
    }

    /**
     * Tick off every waypoint the rider has now ridden past.
     *
     * Strictly in order — a later waypoint cannot count as reached while an earlier
     * one is outstanding, which is what makes "has the whole loop been ridden?" a
     * meaningful question on a route that returns to its start.
     */
    private fun markWaypointsReached(distanceAlongRoute: Double) {
        for (i in waypointReached.indices) {
            if (waypointReached[i]) continue
            if (distanceAlongRoute >= waypointDistances[i] - config.waypointReachedToleranceMeters) {
                waypointReached[i] = true
            } else {
                break
            }
        }
    }

    /** Waypoints still to be visited, for callers deciding whether a ride can end. */
    fun outstandingWaypointCount(): Int = waypointReached.count { !it }

    /**
     * Which leg of a multi-waypoint route the rider is on: the index of the waypoint
     * they are heading towards.
     */
    private fun computeWaypointIndex(distanceAlongRoute: Double): Int {
        if (waypointDistances.size <= 2) return 0
        for (i in 1 until waypointDistances.size) {
            if (distanceAlongRoute < waypointDistances[i]) return i
        }
        return waypointDistances.size - 1
    }

    private companion object {
        /** Segments to look back/ahead of the last snap. ~1 km of dense geometry. */
        const val SNAP_WINDOW_BACK = 20
        const val SNAP_WINDOW_FORWARD = 200

        /** Keep a manoeuvre "current" for a moment after passing it, for GPS jitter. */
        const val PASSED_MANEUVER_TOLERANCE_METERS = 15.0

        /** Do not repeat the same announcement within this window. */
        const val REPEAT_SUPPRESSION_MS = 20_000L

        /** How close to the current manoeuvre before a follow-on is worth showing. */
        const val FOLLOW_ON_APPROACH_METERS = 400.0

        /**
         * How soon after the current manoeuvre a follow-on must land to count as
         * part of the same action. At the national speed limit 150 m is a handful
         * of seconds — too little to read a fresh banner in, which is exactly why
         * the rider should be told now.
         */
        const val FOLLOW_ON_GAP_METERS = 150.0
    }
}
