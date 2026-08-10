package com.motorider.navigation

import com.motorider.models.ManeuverType
import com.motorider.models.Route
import com.motorider.models.TurnInstruction
import com.motorider.models.Waypoint
import com.motorider.utils.RouteUtils
import com.motorider.utils.distanceToMeters
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

    /**
     * A dogleg: 500 m east, left, 100 m north, right, then 500 m east again —
     * two manoeuvres 100 m apart, the junction pair the "then" line exists for.
     */
    private fun doglegGeometry(): List<GeoPoint> =
        (0..10).map { offset(0.0, it * 50.0) } +
            (1..2).map { offset(it * 50.0, 500.0) } +
            (1..10).map { offset(100.0, 500.0 + it * 50.0) }

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

    /** A manoeuvre as the routing service supplies it — with a road name attached. */
    private fun serviceInstruction(
        type: ManeuverType,
        text: String,
        road: String,
        distanceAlong: Double,
        index: Int
    ) = TurnInstruction(
        maneuverType = type,
        instruction = text,
        distanceToManeuver = 0.0,
        distanceAlongRoute = distanceAlong,
        distanceRemaining = 0.0,
        timeRemaining = 0.0,
        bearing = 0.0,
        segmentIndex = index,
        roadName = road
    )

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

    // ─── The "then" line ─────────────────────────────────────────────────────

    @Test
    fun followOnIsOfferedWhenTwoManoeuvresComeInQuickSuccession() {
        val manager = manager()
        manager.startNavigation(routeOf(geometry = doglegGeometry()))

        // 100 m short of the left turn, with the right turn 100 m beyond it.
        manager.setPosition(offset(0.0, 400.0), 20f)

        val state = manager.uiState.value
        assertEquals(ManeuverType.TURN_LEFT, state.currentInstruction!!.maneuverType)
        assertEquals(
            "the right that follows immediately should be offered",
            ManeuverType.TURN_RIGHT,
            state.followOnInstruction!!.maneuverType
        )
    }

    @Test
    fun followOnIsWithheldUntilTheRiderIsNearTheFirstManoeuvre() {
        val manager = manager()
        manager.startNavigation(routeOf(geometry = doglegGeometry()))

        // 450 m out. The pair is just as close together, but announcing it here
        // would be clutter — the rider gets told again when it matters.
        manager.setPosition(offset(0.0, 50.0), 20f)

        assertNull(manager.uiState.value.followOnInstruction)
    }

    @Test
    fun followOnIsWithheldWhenTheNextManoeuvreIsFarBeyondTheCurrentOne() {
        val manager = manager()
        manager.startNavigation(routeOf())

        // Approaching the single turn on the L-shape; the next thing after it is
        // arrival, a kilometre further on.
        manager.setPosition(offset(0.0, 900.0), 20f)

        assertEquals(ManeuverType.TURN_LEFT, manager.uiState.value.currentInstruction!!.maneuverType)
        assertNull(manager.uiState.value.followOnInstruction)
    }

    @Test
    fun followOnIsClearedOnArrival() {
        val manager = manager()
        val route = routeOf(geometry = doglegGeometry())
        manager.startNavigation(route)

        manager.setPosition(route.routeGeometry!!.last(), 0f)

        assertEquals(NavigationState.ARRIVED, manager.uiState.value.state)
        assertNull("nothing follows arriving", manager.uiState.value.followOnInstruction)
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

    // ─── Round trips ─────────────────────────────────────────────────────────

    /**
     * A loop out and back: east 1 km, north 1 km, west 1 km, south 1 km, finishing
     * exactly where it started — the shape Quick Ride produces.
     */
    private fun loopGeometry(): List<GeoPoint> =
        (0..10).map { offset(0.0, it * 100.0) } +
            (1..10).map { offset(it * 100.0, 1000.0) } +
            (9 downTo 0).map { offset(1000.0, it * 100.0) } +
            (9 downTo 0).map { offset(it * 100.0, 0.0) }

    private fun loopRoute(): Route {
        val geometry = loopGeometry()
        val waypoints = listOf(
            Waypoint("Start", geometry.first()),
            Waypoint("Via 1", offset(0.0, 1000.0)),
            Waypoint("Via 2", offset(1000.0, 1000.0)),
            Waypoint("Via 3", offset(1000.0, 0.0)),
            Waypoint("End", geometry.last())
        )
        return routeOf(geometry, waypoints, durationMinutes = 30.0)
    }

    @Test
    fun aRoundTripDoesNotFinishTheMomentItStarts() {
        val manager = manager()
        val route = loopRoute()
        manager.startNavigation(route)

        // Standing at the start, which is also the finish.
        manager.setPosition(route.routeGeometry!!.first(), 0f)

        assertEquals(
            "a loop must not report itself finished before it is ridden",
            NavigationState.NAVIGATING, manager.uiState.value.state
        )
    }

    @Test
    fun aRoundTripStaysNavigatingAllTheWayRound() {
        val manager = manager()
        val route = loopRoute()
        manager.startNavigation(route)

        // Ride the loop, stopping short of the finish.
        for (point in route.routeGeometry!!.dropLast(3)) {
            manager.setPosition(point, 15f)
            assertEquals(NavigationState.NAVIGATING, manager.uiState.value.state)
        }
    }

    @Test
    fun aRoundTripFinishesOnceTheWholeLoopIsRidden() {
        val manager = manager()
        val route = loopRoute()
        manager.startNavigation(route)

        for (point in route.routeGeometry!!) {
            manager.setPosition(point, 15f)
        }

        assertEquals(NavigationState.ARRIVED, manager.uiState.value.state)
    }

    @Test
    fun theFinalWaypointOfALoopIsNotMatchedBackToTheStart() {
        val manager = manager()
        val route = loopRoute()
        manager.startNavigation(route)
        manager.setPosition(route.routeGeometry!!.first(), 0f)

        // Every waypoint but the start is still outstanding at the start line. If the
        // end waypoint had been matched to index 0 it would already count as reached.
        assertEquals(4, manager.outstandingWaypointCount())
    }

    @Test
    fun skippingTheLoopMiddleStillLeavesWaypointsOutstanding() {
        val manager = manager()
        val route = loopRoute()
        manager.startNavigation(route)

        // Teleport to the far corner as a bad fix might: waypoints behind it count,
        // but the ones after are still required before the ride can end.
        manager.setPosition(offset(1000.0, 1000.0), 15f)

        assertTrue(manager.outstandingWaypointCount() > 0)
        assertEquals(NavigationState.NAVIGATING, manager.uiState.value.state)
    }

    @Test
    fun aPointToPointRideStillArrivesNormally() {
        val manager = manager()
        val route = routeOf()
        manager.startNavigation(route)

        manager.setPosition(route.routeGeometry!!.last(), 0f)
        assertEquals(NavigationState.ARRIVED, manager.uiState.value.state)
    }

    // ─── Rejoining after going off route ─────────────────────────────────────

    @Test
    fun rejoinTargetAimsAheadOfTheRiderNotBackAtThem() {
        val manager = manager()
        manager.startNavigation(routeOf())
        // 300 m along the first leg but 200 m off to the north.
        manager.setPosition(offset(200.0, 300.0), 15f)

        val rejoin = manager.rejoinTarget(lookaheadMeters = 250.0)!!

        assertTrue(
            "rejoin at ${rejoin.distanceAlongRoute} m should be ahead of 300 m",
            rejoin.distanceAlongRoute >= 500.0
        )
    }

    @Test
    fun rejoinTargetClampsToTheEndOfTheRoute() {
        val manager = manager()
        val route = routeOf()
        manager.startNavigation(route)
        manager.setPosition(offset(200.0, 950.0), 15f)

        val rejoin = manager.rejoinTarget(lookaheadMeters = 5_000.0)!!
        assertEquals(route.routeGeometry!!.lastIndex, rejoin.vertexIndex)
    }

    @Test
    fun aRejoinRouteKeepsTheRestOfTheOriginalRide() {
        val manager = manager()
        val original = routeOf()
        manager.startNavigation(original)
        manager.setPosition(offset(300.0, 300.0), 15f)

        val rejoin = manager.rejoinTarget(lookaheadMeters = 250.0)!!
        // A detour from where the rider is back onto the route.
        val detourGeometry = listOf(offset(300.0, 300.0), offset(150.0, 450.0), rejoin.point)
        val detour = routeOf(
            geometry = detourGeometry,
            waypoints = listOf(
                Waypoint("Current position", detourGeometry.first()),
                Waypoint("Rejoin route", rejoin.point)
            ),
            durationMinutes = 2.0
        )

        val rejoined = manager.buildRejoinRoute(detour, rejoin)!!
        val geometry = rejoined.routeGeometry!!

        assertEquals("starts where the rider is", 0.0, geometry.first().distanceToMeters(detourGeometry.first()), 5.0)
        assertEquals(
            "ends where the original route ended",
            0.0, geometry.last().distanceToMeters(original.routeGeometry!!.last()), 5.0
        )
        assertTrue("must carry the remaining original route, not just the detour", geometry.size > detourGeometry.size)
        assertTrue(rejoined.turnInstructions!!.isNotEmpty())
    }

    @Test
    fun aRejoinKeepsTheServiceManoeuvresRatherThanRederivingThem() {
        val manager = manager()
        // Both halves carry service instructions, with road names geometry can
        // never supply. Re-deriving from the joined line would silently drop the
        // rider back to one instruction per corner for the rest of the ride.
        val original = routeOf().apply {
            turnInstructions = listOf(
                serviceInstruction(ManeuverType.DEPART, "Continue onto Duke's Drive", "Duke's Drive", 0.0, 0),
                serviceInstruction(ManeuverType.TURN_LEFT, "Turn left onto Tagg Lane", "Tagg Lane", 1400.0, 14),
                serviceInstruction(ManeuverType.ARRIVE, "Arrive", "", 2000.0, 20)
            )
        }
        manager.startNavigation(original)
        manager.setPosition(offset(300.0, 300.0), 15f)

        val rejoin = manager.rejoinTarget(lookaheadMeters = 250.0)!!
        val detourGeometry = listOf(offset(300.0, 300.0), offset(150.0, 450.0), rejoin.point)
        val detour = routeOf(
            geometry = detourGeometry,
            waypoints = listOf(
                Waypoint("Current position", detourGeometry.first()),
                Waypoint("Rejoin route", rejoin.point)
            ),
            durationMinutes = 2.0
        ).apply {
            turnInstructions = listOf(
                serviceInstruction(ManeuverType.TURN_RIGHT, "Turn right onto Manchester Road", "A5004", 0.0, 0),
                serviceInstruction(ManeuverType.ARRIVE, "Arrive", "", 400.0, 2)
            )
        }

        val rejoined = manager.buildRejoinRoute(detour, rejoin)!!
        val instructions = rejoined.turnInstructions!!

        assertTrue(
            "road names only exist on service instructions",
            instructions.any { it.roadName == "A5004" }
        )
        assertTrue(
            "manoeuvres still ahead on the original route must survive the rejoin",
            instructions.any { it.roadName == "Tagg Lane" }
        )
        assertTrue(
            "the detour's own arrival is not the destination",
            instructions.none { it.maneuverType == ManeuverType.ARRIVE && it.roadName == "A5004" }
        )
        // Rebased onto the combined route, so distances only ever grow forwards.
        val distances = instructions.map { it.distanceAlongRoute }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun aRejoinFallsBackToGeometryWhenTheServiceGaveNoManoeuvres() {
        val manager = manager()
        // An older API, or the offline straight-line estimate. Better a noisy
        // instruction than none at all.
        val original = routeOf().apply { turnInstructions = null }
        manager.startNavigation(original)
        manager.setPosition(offset(300.0, 300.0), 15f)

        val rejoin = manager.rejoinTarget(lookaheadMeters = 250.0)!!
        val detourGeometry = listOf(offset(300.0, 300.0), offset(150.0, 450.0), rejoin.point)
        val detour = routeOf(geometry = detourGeometry, durationMinutes = 2.0)

        val rejoined = manager.buildRejoinRoute(detour, rejoin)!!

        assertTrue(rejoined.turnInstructions!!.isNotEmpty())
    }

    @Test
    fun aRejoinRouteCanBeNavigatedStraightAway() {
        val manager = manager()
        manager.startNavigation(routeOf())
        manager.setPosition(offset(300.0, 300.0), 15f)

        val rejoin = manager.rejoinTarget(lookaheadMeters = 250.0)!!
        val detourGeometry = listOf(offset(300.0, 300.0), offset(150.0, 450.0), rejoin.point)
        val detour = routeOf(
            geometry = detourGeometry,
            waypoints = listOf(
                Waypoint("Current position", detourGeometry.first()),
                Waypoint("Rejoin route", rejoin.point)
            ),
            durationMinutes = 2.0
        )

        assertTrue(manager.replaceRoute(manager.buildRejoinRoute(detour, rejoin)!!))

        val state = manager.uiState.value
        assertEquals(NavigationState.NAVIGATING, state.state)
        assertFalse("the detour clears the off-route flag", state.isOffRoute)
        assertTrue("the map needs the new line to draw", state.routeGeometry!!.isNotEmpty())
    }
}
