package com.motorider.navigation

import com.motorider.models.ManeuverType
import com.motorider.models.Route
import com.motorider.models.Waypoint
import com.motorider.utils.RouteUtils
import com.motorider.utils.totalRouteDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class NavigationManagerTest {

    private val base = GeoPoint(51.5000, -0.1000)
    private var now = 1_000L

    private fun offset(north: Double, east: Double): GeoPoint {
        val dLat = north / 111319.49
        val dLon = east / (111319.49 * Math.cos(Math.toRadians(base.latitude)))
        return GeoPoint(base.latitude + dLat, base.longitude + dLon)
    }

    /** 1 km east, then a left turn and 1 km north, sampled every 100 m. */
    private fun lShapedGeometry(): List<GeoPoint> =
        (0..10).map { offset(0.0, it * 100.0) } +
            (1..10).map { offset(it * 100.0, 1000.0) }

    private fun routeOf(
        geometry: List<GeoPoint> = lShapedGeometry(),
        waypoints: List<Waypoint> = listOf(
            Waypoint("Start", geometry.first()),
            Waypoint("End", geometry.last())
        ),
        durationMinutes: Double = 20.0
    ): Route = Route("Test", waypoints).apply {
        routeGeometry = geometry
        distance = totalRouteDistance(geometry) / 1000.0
        duration = durationMinutes
        turnInstructions = RouteUtils.generateTurnInstructions(
            geometry, waypoints, durationMinutes * 60.0
        )
    }

    private fun manager(config: NavigationConfig = NavigationConfig()): NavigationManager =
        NavigationManager(config).apply { clock = { now } }

    // ─── Units ───────────────────────────────────────────────────────────────

    @Test
    fun startPublishesMetresAndSecondsNotKilometresAndMinutes() {
        val route = routeOf(durationMinutes = 20.0)
        val manager = manager()

        assertTrue(manager.startNavigation(route))

        val state = manager.uiState.value
        assertEquals(NavigationState.NAVIGATING, state.state)
        assertEquals("distance in metres", route.distance * 1000.0, state.distanceRemaining, 1.0)
        assertEquals("time in seconds", 1200.0, state.timeRemaining, 0.001)
    }

    @Test
    fun startIsRefusedForARouteWithNoGeometry() {
        val manager = manager()
        val empty = Route("Empty", listOf(Waypoint("A", base), Waypoint("B", offset(10.0, 10.0))))

        assertFalse(manager.startNavigation(empty))
        assertEquals(NavigationState.IDLE, manager.uiState.value.state)
    }

    // ─── Progress ────────────────────────────────────────────────────────────

    @Test
    fun progressTracksDistanceAlongTheRouteNotAsTheCrowFlies() {
        val manager = manager()
        val route = routeOf()
        manager.startNavigation(route)

        // 500 m along the first leg: a quarter of the 2 km route.
        manager.setPosition(offset(0.0, 500.0), 20f)

        val state = manager.uiState.value
        assertEquals(0.25, state.progress, 0.02)
        assertEquals(1500.0, state.distanceRemaining, 25.0)
        assertEquals("time falls with progress", 900.0, state.timeRemaining, 30.0)
    }

    @Test
    fun etaIsAnAbsoluteWallClockTime() {
        val manager = manager()
        manager.startNavigation(routeOf())
        manager.setPosition(offset(0.0, 500.0), 20f)

        val eta = manager.uiState.value.eta
        assertTrue("ETA must be in the future", eta != null && eta > System.currentTimeMillis())
        assertTrue(
            "ETA must be within the remaining journey time, not double-counted",
            eta!! < System.currentTimeMillis() + 20 * 60 * 1000L
        )
    }

    @Test
    fun speedIsCarriedThroughFromTheFix() {
        val manager = manager()
        manager.startNavigation(routeOf())

        manager.setPosition(offset(0.0, 100.0), 27.5f)
        assertEquals(27.5f, manager.uiState.value.currentSpeed, 0.001f)

        // A stationary rider reads zero rather than holding the last speed.
        manager.setPosition(offset(0.0, 100.0), 0f)
        assertEquals(0f, manager.uiState.value.currentSpeed, 0.001f)
    }

    // ─── Instructions ────────────────────────────────────────────────────────

    @Test
    fun currentInstructionAdvancesAsTheRiderMoves() {
        val manager = manager()
        manager.startNavigation(routeOf())

        // Early on, the turn at the 1 km mark is what lies ahead.
        manager.setPosition(offset(0.0, 100.0), 20f)
        val early = manager.uiState.value.currentInstruction!!
        assertEquals(ManeuverType.TURN_LEFT, early.maneuverType)
        assertEquals("distance measured from the rider", 900.0, early.distanceToManeuver, 20.0)

        // Past the turn, the arrival becomes current.
        manager.setPosition(offset(500.0, 1000.0), 20f)
        assertEquals(
            ManeuverType.ARRIVE,
            manager.uiState.value.currentInstruction!!.maneuverType
        )
    }

    @Test
    fun distanceToManeuverShrinksAsTheRiderApproaches() {
        val manager = manager()
        manager.startNavigation(routeOf())

        manager.setPosition(offset(0.0, 200.0), 20f)
        val far = manager.uiState.value.currentInstruction!!.distanceToManeuver

        manager.setPosition(offset(0.0, 800.0), 20f)
        val near = manager.uiState.value.currentInstruction!!.distanceToManeuver

        assertTrue("expected $near < $far", near < far)
        assertEquals(200.0, near, 20.0)
    }

    @Test
    fun upcomingListHoldsOnlyManeuvresStillAhead() {
        val manager = manager()
        manager.startNavigation(routeOf())
        manager.setPosition(offset(0.0, 500.0), 20f)

        val state = manager.uiState.value
        assertTrue(state.upcomingInstructions.none { it.maneuverType == ManeuverType.DEPART })
        assertTrue(state.upcomingInstructions.all { it.distanceToManeuver >= 0.0 })
    }

    // ─── Arrival ─────────────────────────────────────────────────────────────

    @Test
    fun arrivalIsDeclaredOnlyAtTheDestination() {
        val manager = manager()
        val route = routeOf()
        manager.startNavigation(route)

        // 1% short of a 2 km route is still 20 m of riding — but well outside the
        // arrival radius earlier in the ride.
        manager.setPosition(offset(500.0, 1000.0), 20f)
        assertEquals(NavigationState.NAVIGATING, manager.uiState.value.state)

        manager.setPosition(route.routeGeometry!!.last(), 0f)
        assertEquals(NavigationState.ARRIVED, manager.uiState.value.state)
        assertEquals(1.0, manager.uiState.value.progress, 0.001)
    }

    @Test
    fun fixesAfterEndingAreIgnored() {
        val manager = manager()
        manager.startNavigation(routeOf())
        manager.endNavigation()

        manager.setPosition(offset(0.0, 500.0), 20f)

        val state = manager.uiState.value
        assertEquals(NavigationState.IDLE, state.state)
        assertEquals(0.0, state.progress, 0.001)
        assertNull(state.currentInstruction)
    }

    @Test
    fun pausingStopsStateFromAdvancing() {
        val manager = manager()
        manager.startNavigation(routeOf())
        manager.setPosition(offset(0.0, 200.0), 20f)
        val progressWhenPaused = manager.uiState.value.progress

        manager.pauseNavigation()
        manager.setPosition(offset(0.0, 900.0), 20f)

        assertEquals(NavigationState.PAUSED, manager.uiState.value.state)
        assertEquals(progressWhenPaused, manager.uiState.value.progress, 0.001)

        manager.resumeNavigation()
        assertEquals(NavigationState.NAVIGATING, manager.uiState.value.state)
        assertTrue(manager.uiState.value.progress > progressWhenPaused)
    }

    // ─── Off route ───────────────────────────────────────────────────────────

    @Test
    fun ridingTheRouteNeverReadsAsOffRoute() {
        val manager = manager()
        manager.startNavigation(routeOf())

        for (metres in 100..900 step 100) {
            manager.setPosition(offset(5.0, metres.toDouble()), 20f)
            assertFalse(manager.uiState.value.isOffRoute)
        }
    }

    @Test
    fun leavingTheRouteRaisesAWarningAfterConfirmation() {
        val manager = manager()
        manager.startNavigation(routeOf())

        repeat(4) { manager.setPosition(offset(300.0, 500.0), 20f) }

        val state = manager.uiState.value
        assertTrue(state.isOffRoute)
        assertTrue(state.warnings.any { it.type == com.motorider.models.WarningType.OFF_ROUTE })
    }

    // ─── GPS loss ────────────────────────────────────────────────────────────

    @Test
    fun gpsLossIsNoticedByTheTickNotOnlyByNewFixes() {
        val manager = manager(NavigationConfig(gpsLossTimeoutSeconds = 60))
        manager.startNavigation(routeOf())
        manager.setPosition(offset(0.0, 100.0), 20f)
        assertFalse(manager.uiState.value.isGpsLost)

        // No further fixes arrive; only the tick can spot the silence.
        now += 61_000L
        manager.tick()

        assertTrue(manager.uiState.value.isGpsLost)
        assertTrue(manager.uiState.value.warnings.any { it.type == com.motorider.models.WarningType.GPS_LOST })
    }

    @Test
    fun gpsLossClearsWhenFixesResume() {
        val manager = manager(NavigationConfig(gpsLossTimeoutSeconds = 60))
        manager.startNavigation(routeOf())
        manager.setPosition(offset(0.0, 100.0), 20f)

        now += 61_000L
        manager.tick()
        assertTrue(manager.uiState.value.isGpsLost)

        manager.setPosition(offset(0.0, 200.0), 20f)
        assertFalse(manager.uiState.value.isGpsLost)
    }

    // ─── Waypoints ───────────────────────────────────────────────────────────

    @Test
    fun waypointIndexFollowsTheLegBeingRidden() {
        val geometry = lShapedGeometry()
        val waypoints = listOf(
            Waypoint("Start", geometry.first()),
            Waypoint("Cafe", offset(0.0, 1000.0)),
            Waypoint("End", geometry.last())
        )
        val manager = manager()
        manager.startNavigation(routeOf(geometry, waypoints))

        manager.setPosition(offset(0.0, 200.0), 20f)
        assertEquals("heading for the cafe", 1, manager.uiState.value.currentWaypointIndex)

        manager.setPosition(offset(600.0, 1000.0), 20f)
        assertEquals("cafe passed, heading for the end", 2, manager.uiState.value.currentWaypointIndex)
    }

    @Test
    fun skipIsOfferedOnlyNearAnIntermediateWaypoint() {
        val geometry = lShapedGeometry()
        val waypoints = listOf(
            Waypoint("Start", geometry.first()),
            Waypoint("Cafe", offset(0.0, 1000.0)),
            Waypoint("End", geometry.last())
        )
        val manager = manager()
        manager.startNavigation(routeOf(geometry, waypoints))

        manager.setPosition(offset(0.0, 100.0), 20f)
        assertNull("900 m out is too far to offer a skip", manager.uiState.value.skipAvailableWaypointName)

        manager.setPosition(offset(0.0, 700.0), 20f)
        assertEquals("Cafe", manager.uiState.value.skipAvailableWaypointName)
    }

    @Test
    fun skippingReturnsTheWaypointsStillWorthVisiting() {
        val geometry = lShapedGeometry()
        val waypoints = listOf(
            Waypoint("Start", geometry.first()),
            Waypoint("Cafe", offset(0.0, 1000.0)),
            Waypoint("End", geometry.last())
        )
        val manager = manager()
        manager.startNavigation(routeOf(geometry, waypoints))
        manager.setPosition(offset(0.0, 700.0), 20f)

        val remaining = manager.waypointsAfterSkip()!!
        assertEquals(listOf("End"), remaining.map { it.name })
    }

    @Test
    fun thereIsNothingToSkipOnATwoPointRoute() {
        val manager = manager()
        manager.startNavigation(routeOf())
        manager.setPosition(offset(0.0, 500.0), 20f)

        assertNull(manager.waypointsAfterSkip())
        assertNull(manager.uiState.value.skipAvailableWaypointName)
    }

    // ─── Round trips ─────────────────────────────────────────────────────────

    @Test
    fun aReturnLegRunningAlongsideTheOutboundOneDoesNotFakeProgress() {
        // The classic round-trip failure: the two legs pass within metres of each
        // other, and an unwindowed snap puts the rider almost home on departure.
        val out = (0..10).map { offset(0.0, it * 100.0) }
        val back = (10 downTo 0).map { offset(20.0, it * 100.0) }
        val geometry = out + back

        val manager = manager()
        manager.startNavigation(routeOf(geometry, durationMinutes = 20.0))

        manager.setPosition(offset(2.0, 100.0), 20f)
        manager.setPosition(offset(2.0, 300.0), 20f)

        val progress = manager.uiState.value.progress
        assertTrue("progress $progress should still be early in the ride", progress < 0.25)
        assertEquals(NavigationState.NAVIGATING, manager.uiState.value.state)
    }

    // ─── Route replacement ───────────────────────────────────────────────────

    @Test
    fun replacingTheRouteKeepsNavigatingAndClearsTheOffRouteFlag() {
        val manager = manager()
        manager.startNavigation(routeOf())
        repeat(4) { manager.setPosition(offset(300.0, 500.0), 20f) }
        assertTrue(manager.uiState.value.isOffRoute)

        val replacement = routeOf(
            geometry = (0..10).map { offset(300.0, 500.0 + it * 100.0) },
            waypoints = listOf(
                Waypoint("Current position", offset(300.0, 500.0)),
                Waypoint("End", offset(300.0, 1500.0))
            ),
            durationMinutes = 5.0
        )

        assertTrue(manager.replaceRoute(replacement))

        val state = manager.uiState.value
        assertEquals(NavigationState.NAVIGATING, state.state)
        assertFalse(state.isOffRoute)
        assertFalse(state.isRecalculating)
    }
}
