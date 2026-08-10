package com.motorider.ui

import com.motorider.models.Route
import com.motorider.models.Waypoint
import com.motorider.ui.screen.stitchLegs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Per-leg ride styles are only real if each leg is routed separately and the
 * results joined. These cover the join, which is where plausible-looking wrong
 * numbers would come from.
 */
class StitchLegsTest {

    private fun leg(
        points: List<GeoPoint>,
        distanceKm: Double,
        durationMin: Double,
        curvature: Double = 0.0,
        estimate: Boolean = false,
        avoidancesHonoured: Boolean = true,
        curvatureAvailable: Boolean = true
    ): Route = Route("leg", emptyList()).apply {
        routeGeometry = points
        distance = distanceKm
        duration = durationMin
        curvatureScore = curvature
        routeScore = 1.0
        isEstimate = estimate
        this.avoidancesHonoured = avoidancesHonoured
        this.curvatureAvailable = curvatureAvailable
    }

    private val a = GeoPoint(53.0, -1.8)
    private val b = GeoPoint(53.1, -1.8)
    private val c = GeoPoint(53.2, -1.8)

    private val waypoints = listOf(
        Waypoint("A", a), Waypoint("B", b), Waypoint("C", c)
    )

    @Test
    fun `shared join vertex appears once, not twice`() {
        val combined = stitchLegs(
            listOf(
                leg(listOf(a, b), 10.0, 15.0),
                leg(listOf(b, c), 10.0, 15.0)
            ),
            waypoints
        )

        // A duplicated join would put a zero-length segment in the geometry, which
        // the turn generator reads as a 0-degree heading change at a real junction.
        assertEquals(listOf(a, b, c), combined.routeGeometry)
    }

    @Test
    fun `distance and duration are the sum of the legs`() {
        val combined = stitchLegs(
            listOf(
                leg(listOf(a, b), 12.0, 20.0),
                leg(listOf(b, c), 30.0, 45.0)
            ),
            waypoints
        )

        assertEquals(42.0, combined.distance, 1e-9)
        assertEquals(65.0, combined.duration, 1e-9)
    }

    @Test
    fun `curves per km is weighted by distance, not averaged per leg`() {
        // 5 km of hairpins then 95 km of motorway. A plain mean would report 3.0
        // curves/km for a ride that is overwhelmingly straight.
        val combined = stitchLegs(
            listOf(
                leg(listOf(a, b), 5.0, 10.0, curvature = 6.0),
                leg(listOf(b, c), 95.0, 60.0, curvature = 0.0)
            ),
            waypoints
        )

        assertEquals((6.0 * 5.0) / 100.0, combined.curvatureScore, 1e-9)
    }

    @Test
    fun `an estimate on any leg makes the whole ride an estimate`() {
        val combined = stitchLegs(
            listOf(
                leg(listOf(a, b), 10.0, 15.0),
                leg(listOf(b, c), 10.0, 15.0, estimate = true)
            ),
            waypoints
        )

        // Half a real route and half a straight line across country is not a route.
        assertTrue(combined.isEstimate)
    }

    @Test
    fun `an unhonoured avoidance on any leg is reported for the whole ride`() {
        val combined = stitchLegs(
            listOf(
                leg(listOf(a, b), 10.0, 15.0, avoidancesHonoured = false),
                leg(listOf(b, c), 10.0, 15.0)
            ),
            waypoints
        )

        assertFalse(combined.avoidancesHonoured)
    }

    @Test
    fun `missing curvature data on any leg makes the whole figure unavailable`() {
        val combined = stitchLegs(
            listOf(
                leg(listOf(a, b), 10.0, 15.0),
                leg(listOf(b, c), 10.0, 15.0, curvatureAvailable = false)
            ),
            waypoints
        )

        assertFalse(combined.curvatureAvailable)
    }

    @Test
    fun `turn instructions are regenerated over the joined geometry`() {
        val combined = stitchLegs(
            listOf(
                leg(listOf(a, b), 10.0, 15.0),
                leg(listOf(b, c), 10.0, 15.0)
            ),
            waypoints
        )

        // Instructions from the individual legs would each start with a Depart and
        // end with an Arrive in the middle of the ride.
        val instructions = combined.turnInstructions.orEmpty()
        assertTrue(instructions.isNotEmpty())
        assertEquals(1, instructions.count { it.maneuverType == com.motorider.models.ManeuverType.DEPART })
        assertEquals(1, instructions.count { it.maneuverType == com.motorider.models.ManeuverType.ARRIVE })
    }
}
