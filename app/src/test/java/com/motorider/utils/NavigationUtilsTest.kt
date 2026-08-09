package com.motorider.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Geometry the navigation state machine depends on. Coordinates are around London
 * so the latitude scaling is representative rather than equatorial.
 */
class NavigationUtilsTest {

    private val base = GeoPoint(51.5000, -0.1000)

    /** A point [north] metres north and [east] metres east of [base]. */
    private fun offset(north: Double, east: Double): GeoPoint {
        val dLat = north / 111319.49
        val dLon = east / (111319.49 * Math.cos(Math.toRadians(base.latitude)))
        return GeoPoint(base.latitude + dLat, base.longitude + dLon)
    }

    @Test
    fun distanceMatchesKnownSeparation() {
        assertEquals(100.0, base.distanceToMeters(offset(100.0, 0.0)), 0.5)
        assertEquals(250.0, base.distanceToMeters(offset(0.0, 250.0)), 0.5)
        assertEquals(0.0, base.distanceToMeters(base), 1e-9)
    }

    @Test
    fun perpendicularDistanceIsCrossTrackNotEndpointDistance() {
        // Segment runs 1 km east; the point sits 30 m north of its midpoint.
        val a = base
        val b = offset(0.0, 1000.0)
        val p = offset(30.0, 500.0)

        assertEquals(30.0, perpendicularDistance(p, a, b), 1.0)
    }

    @Test
    fun perpendicularDistanceOfPointOnLineIsZero() {
        val a = base
        val b = offset(0.0, 1000.0)
        assertEquals(0.0, perpendicularDistance(offset(0.0, 400.0), a, b), 0.5)
    }

    @Test
    fun projectionClampsToSegmentEnds() {
        val a = base
        val b = offset(0.0, 100.0)

        // 500 m beyond the far end must clamp to that end, not extrapolate.
        val projected = projectPointOntoSegment(offset(0.0, 600.0), a, b)
        assertEquals(0.0, projected.distanceToMeters(b), 1.0)

        val behind = projectPointOntoSegment(offset(0.0, -600.0), a, b)
        assertEquals(0.0, behind.distanceToMeters(a), 1.0)
    }

    @Test
    fun projectionFallsAtPerpendicularFoot() {
        val a = base
        val b = offset(0.0, 1000.0)
        val projected = projectPointOntoSegment(offset(40.0, 300.0), a, b)

        assertEquals(0.0, projected.distanceToMeters(offset(0.0, 300.0)), 1.0)
    }

    @Test
    fun cumulativeDistancesAccumulateAlongPolyline() {
        val geometry = listOf(base, offset(0.0, 100.0), offset(0.0, 300.0))
        val cumulative = calculateCumulativeDistances(geometry)

        assertEquals(3, cumulative.size)
        assertEquals(0.0, cumulative[0], 0.001)
        assertEquals(100.0, cumulative[1], 1.0)
        assertEquals(300.0, cumulative[2], 1.0)
    }

    @Test
    fun cumulativeDistancesHandleDegenerateGeometry() {
        assertEquals(1, calculateCumulativeDistances(emptyList()).size)
        assertEquals(1, calculateCumulativeDistances(listOf(base)).size)
        assertEquals(0.0, totalRouteDistance(listOf(base)), 0.001)
    }

    @Test
    fun nearestPointReportsDistanceAlongRouteNotCrossTrack() {
        // Straight 1 km run sampled every 100 m.
        val geometry = (0..10).map { offset(0.0, it * 100.0) }
        val rider = offset(20.0, 450.0)

        val result = nearestPointOnPolyline(rider, geometry)

        assertEquals("cross-track distance", 20.0, result.distance, 1.5)
        assertEquals("distance along route", 450.0, result.distanceAlongRoute, 2.0)
        assertEquals("segment index", 4, result.segmentIndex)
    }

    @Test
    fun nearestPointOnShortRouteStillUsesGeometry() {
        // Two-point geometry: the answer must be the projection, not the endpoint.
        val geometry = listOf(base, offset(0.0, 1000.0))
        val result = nearestPointOnPolyline(offset(10.0, 250.0), geometry)

        assertEquals(250.0, result.distanceAlongRoute, 2.0)
        assertEquals(10.0, result.distance, 1.5)
    }

    @Test
    fun searchWindowKeepsRiderOffAParallelReturnLeg() {
        // A there-and-back route: the return leg is 15 m north of the outbound one,
        // closer than the outbound vertices at the rider's position.
        val out = (0..10).map { offset(0.0, it * 100.0) }
        val back = (10 downTo 0).map { offset(15.0, it * 100.0) }
        val geometry = out + back
        val cumulative = calculateCumulativeDistances(geometry)

        val rider = offset(12.0, 500.0)

        val unconstrained = nearestPointOnPolyline(rider, geometry, cumulative)
        assertTrue(
            "unconstrained search snaps to the return leg",
            unconstrained.distanceAlongRoute > 1000.0
        )

        val windowed = nearestPointOnPolyline(
            rider, geometry, cumulative, searchFrom = 0, searchTo = 8
        )
        assertEquals(500.0, windowed.distanceAlongRoute, 5.0)
    }

    @Test
    fun searchWindowIsClampedToValidIndices() {
        val geometry = (0..5).map { offset(0.0, it * 100.0) }
        val result = nearestPointOnPolyline(
            offset(0.0, 250.0), geometry,
            searchFrom = -50, searchTo = 9999
        )
        assertEquals(250.0, result.distanceAlongRoute, 2.0)
    }

    @Test
    fun bearingIsClockwiseFromNorth() {
        assertEquals(0.0, calculateBearing(base, offset(100.0, 0.0)), 0.5)
        assertEquals(90.0, calculateBearing(base, offset(0.0, 100.0)), 0.5)
        assertEquals(180.0, calculateBearing(base, offset(-100.0, 0.0)), 0.5)
        assertEquals(270.0, calculateBearing(base, offset(0.0, -100.0)), 0.5)
    }

    @Test
    fun signedBearingDeltaIsPositiveForRightTurns() {
        assertEquals(90.0, signedBearingDelta(0.0, 90.0), 1e-9)
        assertEquals(-90.0, signedBearingDelta(90.0, 0.0), 1e-9)
    }

    @Test
    fun signedBearingDeltaHandlesTheNorthWrap() {
        // 350° → 10° is a 20° right, not a 340° left.
        assertEquals(20.0, signedBearingDelta(350.0, 10.0), 1e-9)
        assertEquals(-20.0, signedBearingDelta(10.0, 350.0), 1e-9)
    }

    @Test
    fun emaFilterStartsAtFirstReadingThenSmooths() {
        val filter = DistanceEMAFilter(0.3f)

        // No warm-up ramp from zero: the first reading is taken at face value.
        assertEquals(100f, filter.update(100f), 0.01f)
        assertEquals(70f, filter.update(0f), 0.01f)
    }

    @Test
    fun offRouteNeedsConsecutiveConfirmations() {
        val detector = OffRouteDetector(thresholdMeters = 50f, confirmationCount = 3)

        assertFalse(detector.update(200f))
        assertFalse(detector.update(200f))
        assertTrue("third consecutive reading confirms", detector.update(200f))
    }

    @Test
    fun offRouteIgnoresASingleGpsSpike() {
        val detector = OffRouteDetector(thresholdMeters = 50f, confirmationCount = 3)

        detector.update(5f)
        assertFalse(detector.update(400f))
        assertFalse(detector.update(5f))
        assertFalse(detector.update(5f))
    }

    @Test
    fun offRouteResetsCleanly() {
        val detector = OffRouteDetector(thresholdMeters = 50f, confirmationCount = 2)
        detector.update(500f)
        detector.update(500f)

        detector.reset()
        assertFalse("first reading after reset cannot re-confirm", detector.update(500f))
    }
}
