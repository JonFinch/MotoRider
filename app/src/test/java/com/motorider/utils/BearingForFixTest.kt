package com.motorider.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Which way the rider is pointing, for the heading-up map and the motorcycle
 * marker drawn at their position.
 *
 * The rule this replaced substituted 0f whenever the GPS withheld a bearing, which
 * is not "unknown" but "due north".
 */
class BearingForFixTest {

    private val base = GeoPoint(53.2500, -1.9026)

    private fun offset(north: Double, east: Double): GeoPoint {
        val dLat = north / 111319.49
        val dLon = east / (111319.49 * Math.cos(Math.toRadians(base.latitude)))
        return GeoPoint(base.latitude + dLat, base.longitude + dLon)
    }

    @Test
    fun `what the GPS reports wins while actually moving`() {
        val bearing = bearingForFix(
            reported = 217f, speedMps = 20f,
            previous = base, current = offset(100.0, 0.0), lastKnown = 10f
        )

        assertEquals(217f, bearing!!, 0.001f)
    }

    @Test
    fun `a bearing reported by a fix with no speed is not believed`() {
        // The emulator stamps a course of 0 on every synthetic fix, and a real
        // receiver does much the same standing still. Movement is the better signal.
        val bearing = bearingForFix(
            reported = 0f, speedMps = 0f,
            previous = base, current = offset(0.0, 60.0), lastKnown = null
        )

        assertEquals("should follow the movement, not the reported north", 90f, bearing!!, 2f)
    }

    @Test
    fun `movement supplies a bearing when the GPS withholds one`() {
        // Due east, far enough to be movement rather than noise.
        val bearing = bearingForFix(
            reported = null, speedMps = 0f, previous = base, current = offset(0.0, 60.0), lastKnown = null
        )

        assertEquals(90f, bearing!!, 2f)
    }

    @Test
    fun `a stationary wobble does not spin the rider round`() {
        // GPS noise at a standstill is a few metres. Deriving a heading from it
        // would swing the map and the marker while the rider waits at lights.
        val bearing = bearingForFix(
            reported = null, speedMps = 0f, previous = base, current = offset(2.0, 2.0), lastKnown = 140f
        )

        assertEquals("must hold the last heading", 140f, bearing!!, 0.001f)
    }

    @Test
    fun `losing the bearing holds the last one rather than snapping north`() {
        val bearing = bearingForFix(
            reported = null, speedMps = 0f, previous = null, current = base, lastKnown = 275f
        )

        assertEquals(275f, bearing!!, 0.001f)
    }

    @Test
    fun `nothing to go on reports nothing, not north`() {
        // The caller can then leave the marker unrotated instead of confidently
        // pointing it up the screen.
        assertNull(bearingForFix(reported = null, speedMps = 0f, previous = null, current = base, lastKnown = null))
    }

    @Test
    fun `a southward leg reads as south`() {
        val bearing = bearingForFix(
            reported = null, speedMps = 0f, previous = base, current = offset(-80.0, 0.0), lastKnown = null
        )

        assertEquals(180f, bearing!!, 2f)
    }
}
