package com.motorider.utils

import com.motorider.models.ManeuverType
import com.motorider.models.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class TurnInstructionsTest {

    private val base = GeoPoint(51.5000, -0.1000)

    private fun offset(north: Double, east: Double): GeoPoint {
        val dLat = north / 111319.49
        val dLon = east / (111319.49 * Math.cos(Math.toRadians(base.latitude)))
        return GeoPoint(base.latitude + dLat, base.longitude + dLon)
    }

    private fun waypoints(vararg points: Pair<String, GeoPoint>) =
        points.map { Waypoint(it.first, it.second) }

    /** East for 500 m, then a square turn to the north for 500 m. */
    private fun rightAngleRouteEastThenNorth(): List<GeoPoint> =
        (0..5).map { offset(0.0, it * 100.0) } +
            (1..5).map { offset(it * 100.0, 500.0) }

    /** East for 500 m, then a square turn to the south. */
    private fun rightAngleRouteEastThenSouth(): List<GeoPoint> =
        (0..5).map { offset(0.0, it * 100.0) } +
            (1..5).map { offset(-it * 100.0, 500.0) }

    @Test
    fun headingEastThenNorthIsALeftTurn() {
        val instructions = RouteUtils.generateTurnInstructions(
            rightAngleRouteEastThenNorth(),
            waypoints("Start" to base, "End" to offset(500.0, 500.0)),
            600.0
        )

        val turn = instructions.single { it.maneuverType != ManeuverType.DEPART && it.maneuverType != ManeuverType.ARRIVE }
        assertEquals(ManeuverType.TURN_LEFT, turn.maneuverType)
    }

    @Test
    fun headingEastThenSouthIsARightTurn() {
        val instructions = RouteUtils.generateTurnInstructions(
            rightAngleRouteEastThenSouth(),
            waypoints("Start" to base, "End" to offset(-500.0, 500.0)),
            600.0
        )

        val turn = instructions.single { it.maneuverType != ManeuverType.DEPART && it.maneuverType != ManeuverType.ARRIVE }
        assertEquals(ManeuverType.TURN_RIGHT, turn.maneuverType)
    }

    @Test
    fun routeAlwaysOpensWithDepartAndClosesWithArrive() {
        val instructions = RouteUtils.generateTurnInstructions(
            rightAngleRouteEastThenNorth(),
            waypoints("Start" to base, "Home" to offset(500.0, 500.0)),
            600.0
        )

        assertEquals(ManeuverType.DEPART, instructions.first().maneuverType)
        assertEquals(ManeuverType.ARRIVE, instructions.last().maneuverType)
        assertTrue(instructions.last().instruction.contains("Home"))
    }

    @Test
    fun gentleCurvesDoNotBecomeManeuvers() {
        // A shallow arc: every vertex bends slightly, none of it is a junction.
        val geometry = (0..60).map { i ->
            val angle = Math.toRadians(i * 1.0)
            offset(200.0 * (1 - Math.cos(angle)), 200.0 * Math.sin(angle))
        }

        val instructions = RouteUtils.generateTurnInstructions(
            geometry, waypoints("Start" to geometry.first(), "End" to geometry.last()), 600.0
        )

        // Only DEPART and ARRIVE should survive — no "Continue" per vertex.
        assertEquals(
            "curve-following should not generate manoeuvres",
            2, instructions.size
        )
    }

    @Test
    fun clusteredVerticesAtOneJunctionCollapseToASingleManeuver() {
        // A junction described by several vertices a few metres apart, as real
        // routing geometry does, must not read as several separate turns.
        val geometry = listOf(
            offset(0.0, 0.0),
            offset(0.0, 200.0),
            offset(3.0, 205.0),
            offset(8.0, 208.0),
            offset(14.0, 209.0),
            offset(200.0, 210.0),
            offset(400.0, 210.0)
        )

        val turns = RouteUtils.generateTurnInstructions(
            geometry, waypoints("Start" to geometry.first(), "End" to geometry.last()), 600.0
        ).filter { it.maneuverType != ManeuverType.DEPART && it.maneuverType != ManeuverType.ARRIVE }

        assertEquals("one junction, one instruction", 1, turns.size)
    }

    @Test
    fun distanceAlongRouteIsMeasuredFromTheStart() {
        val geometry = rightAngleRouteEastThenNorth()
        val instructions = RouteUtils.generateTurnInstructions(
            geometry, waypoints("Start" to base, "End" to offset(500.0, 500.0)), 600.0
        )

        assertEquals(0.0, instructions.first().distanceAlongRoute, 0.001)

        val turn = instructions.first { it.maneuverType == ManeuverType.TURN_LEFT }
        assertEquals("turn sits 500 m in", 500.0, turn.distanceAlongRoute, 5.0)

        assertEquals(
            "arrival sits at the full route length",
            totalRouteDistance(geometry), instructions.last().distanceAlongRoute, 1.0
        )
    }

    @Test
    fun storedDistanceToManeuverIsZeroUntilNavigationFillsItIn() {
        val instructions = RouteUtils.generateTurnInstructions(
            rightAngleRouteEastThenNorth(),
            waypoints("Start" to base, "End" to offset(500.0, 500.0)),
            600.0
        )

        assertTrue(instructions.all { it.distanceToManeuver == 0.0 })
    }

    @Test
    fun remainingDistanceAndTimeCountDownToZero() {
        val instructions = RouteUtils.generateTurnInstructions(
            rightAngleRouteEastThenNorth(),
            waypoints("Start" to base, "End" to offset(500.0, 500.0)),
            600.0
        )

        val first = instructions.first()
        assertEquals(600.0, first.timeRemaining, 0.001)
        assertEquals(totalRouteDistance(rightAngleRouteEastThenNorth()), first.distanceRemaining, 1.0)

        assertEquals(0.0, instructions.last().distanceRemaining, 1.0)
        assertEquals(0.0, instructions.last().timeRemaining, 1.0)
    }

    @Test
    fun intermediateWaypointsAreAnnounced() {
        val geometry = rightAngleRouteEastThenNorth()
        val instructions = RouteUtils.generateTurnInstructions(
            geometry,
            waypoints(
                "Start" to base,
                "Cafe" to offset(300.0, 500.0),
                "End" to offset(500.0, 500.0)
            ),
            600.0
        )

        val waypointStop = instructions.single { it.maneuverType == ManeuverType.WAYPOINT_ARRIVED }
        assertTrue(waypointStop.instruction.contains("Cafe"))
        assertEquals("announced where the waypoint actually is", 800.0, waypointStop.distanceAlongRoute, 20.0)
    }

    @Test
    fun instructionsComeBackInRidingOrder() {
        val geometry = rightAngleRouteEastThenNorth()
        val instructions = RouteUtils.generateTurnInstructions(
            geometry,
            waypoints("Start" to base, "Cafe" to offset(300.0, 500.0), "End" to offset(500.0, 500.0)),
            600.0
        )

        val distances = instructions.map { it.distanceAlongRoute }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun degenerateGeometryYieldsNoInstructions() {
        assertTrue(RouteUtils.generateTurnInstructions(emptyList(), emptyList(), 0.0).isEmpty())
        assertTrue(RouteUtils.generateTurnInstructions(listOf(base), emptyList(), 0.0).isEmpty())
    }

    @Test
    fun unknownDurationLeavesTimesAtZeroRatherThanGuessing() {
        val instructions = RouteUtils.generateTurnInstructions(
            rightAngleRouteEastThenNorth(),
            waypoints("Start" to base, "End" to offset(500.0, 500.0)),
            0.0
        )

        assertTrue(instructions.all { it.timeRemaining == 0.0 })
    }
}
