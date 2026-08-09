package com.motorider.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCameraTest {

    private fun mph(value: Float): Float = value * 0.44704f

    private fun camera() = NavigationCamera()

    // ─── Speed to zoom ───────────────────────────────────────────────────────

    @Test
    fun slowRidingGetsACloserMapThanFastRiding() {
        val camera = camera()

        val walkingPace = camera.targetZoomForSpeed(mph(5f))
        val townSpeed = camera.targetZoomForSpeed(mph(30f))
        val openRoad = camera.targetZoomForSpeed(mph(70f))

        assertTrue("$walkingPace should be closer in than $townSpeed", walkingPace > townSpeed)
        assertTrue("$townSpeed should be closer in than $openRoad", townSpeed > openRoad)
    }

    @Test
    fun belowTwentyMphHoldsTheCloseZoom() {
        val camera = camera()
        // The whole sub-20 band should stay close; a rider filtering through traffic
        // should not see the map stepping about.
        val stationary = camera.targetZoomForSpeed(0f)
        val nineteen = camera.targetZoomForSpeed(mph(19f))

        assertTrue(stationary >= 17.0)
        assertTrue(nineteen >= 17.0)
    }

    @Test
    fun zoomCurveIsMonotonicAcrossTheWholeSpeedRange() {
        val camera = camera()
        var previous = Double.MAX_VALUE
        for (speed in 0..120 step 5) {
            val zoom = camera.targetZoomForSpeed(mph(speed.toFloat()))
            assertTrue("zoom rose at $speed mph ($zoom > $previous)", zoom <= previous + 1e-9)
            previous = zoom
        }
    }

    @Test
    fun speedsBeyondTheTableClampInsteadOfExtrapolating() {
        val camera = camera()
        val top = camera.targetZoomForSpeed(mph(80f))

        assertEquals(top, camera.targetZoomForSpeed(mph(150f)), 1e-9)
        assertEquals(top, camera.targetZoomForSpeed(mph(300f)), 1e-9)
        // Negative speed is nonsense from a bad fix; it must not invert the curve.
        assertEquals(camera.targetZoomForSpeed(0f), camera.targetZoomForSpeed(-5f), 1e-9)
    }

    @Test
    fun zoomEasesTowardsTheTargetRatherThanJumping() {
        val camera = camera()
        // Settle at a standstill, then accelerate hard.
        val first = camera.update(0f, 0f, 0L).zoom
        assertNotNull(first)

        val afterOneFix = camera.update(mph(70f), 0f, 1_000L).zoom
        assertNotNull(afterOneFix)

        val destination = camera.targetZoomForSpeed(mph(70f))
        assertTrue(
            "one fix should not snap all the way to $destination, got $afterOneFix",
            afterOneFix!! > destination + 0.1
        )

        // But it must actually get there if the speed holds.
        var latest: Double = afterOneFix
        repeat(60) { i ->
            camera.update(mph(70f), 0f, 2_000L + i * 1_000L).zoom?.let { latest = it }
        }
        assertEquals(destination, latest, 0.05)
    }

    @Test
    fun unchangedSpeedStopsIssuingZoomCommands() {
        val camera = camera()
        repeat(80) { i -> camera.update(mph(30f), 0f, i * 1_000L) }

        // Once settled, the camera should go quiet rather than nudging the map every
        // second - each command interrupts osmdroid's own animation.
        val settled = camera.update(mph(30f), 0f, 200_000L)
        assertNull(settled.zoom)
    }

    // ─── Pinch override ──────────────────────────────────────────────────────

    @Test
    fun pinchingHandsTheZoomToTheRider() {
        val camera = camera()
        camera.update(mph(30f), 0f, 0L)

        camera.onUserZoomGesture(1_000L)

        assertTrue(camera.isUserControllingZoom(1_000L))
        assertNull("camera must not fight the rider", camera.update(mph(70f), 0f, 2_000L).zoom)
    }

    @Test
    fun controlReturnsTenSecondsAfterThePinch() {
        val camera = camera()
        camera.onUserZoomGesture(0L)

        assertTrue("still theirs at 9.9s", camera.isUserControllingZoom(9_900L))
        assertNull(camera.update(mph(30f), 0f, 9_900L).zoom)

        assertFalse("released at 10s", camera.isUserControllingZoom(10_000L))
        assertNotNull("camera takes over again", camera.update(mph(30f), 0f, 10_001L).zoom)
    }

    @Test
    fun eachPinchRestartsTheTenSeconds() {
        val camera = camera()
        camera.onUserZoomGesture(0L)
        camera.onUserZoomGesture(8_000L)

        assertTrue("second pinch extends the hold", camera.isUserControllingZoom(17_000L))
        assertFalse(camera.isUserControllingZoom(18_001L))
    }

    @Test
    fun headingKeepsTrackingWhileTheRiderControlsZoom() {
        val camera = camera()
        camera.onUserZoomGesture(0L)

        // Only the zoom is handed over - the map must still rotate, or a pinch would
        // leave the rider looking at a map facing the wrong way.
        repeat(20) { camera.update(mph(30f), 90f, 1_000L) }
        assertEquals(90f, camera.update(mph(30f), 90f, 1_000L).bearing, 1f)
    }

    @Test
    fun zoomDoesNotLurchWhenControlComesBack() {
        val camera = camera()
        repeat(40) { i -> camera.update(mph(10f), 0f, i * 1_000L) }

        // The rider keeps adjusting the zoom by hand while accelerating to motorway
        // speed, so the hold never lapses during the climb.
        for (i in 40..60) {
            camera.onUserZoomGesture(i * 1_000L)
            camera.update(mph(70f), 0f, i * 1_000L)
        }

        // First command issued after the hold finally expires.
        val resumed = camera.update(mph(70f), 0f, 60_000L + 10_001L).zoom
        assertNotNull(resumed)
        // Easing continued underneath, so the first command back is already near the
        // right zoom rather than a jump from the old slow-speed value.
        assertEquals(camera.targetZoomForSpeed(mph(70f)), resumed!!, 0.3)
    }

    // ─── Heading ─────────────────────────────────────────────────────────────

    @Test
    fun headingFollowsTheDirectionOfTravel() {
        val camera = camera()
        repeat(30) { camera.update(mph(30f), 135f, 1_000L) }
        assertEquals(135f, camera.update(mph(30f), 135f, 1_000L).bearing, 1f)
    }

    @Test
    fun headingIsHeldStillWhenStopped() {
        val camera = camera()
        repeat(30) { camera.update(mph(30f), 90f, 1_000L) }

        // A stationary phone reports wildly swinging bearings; the map must not spin.
        val stopped1 = camera.update(0f, 270f, 2_000L).bearing
        val stopped2 = camera.update(0f, 10f, 3_000L).bearing

        assertEquals(90f, stopped1, 1f)
        assertEquals(90f, stopped2, 1f)
    }

    @Test
    fun headingCrossesNorthWithoutSpinningTheLongWayRound() {
        val camera = camera()
        repeat(30) { camera.update(mph(30f), 350f, 1_000L) }

        // 350 -> 10 is a 20 degree swing right. Averaging the raw degrees would send
        // the map through 180 and spin it right round the compass.
        val result = camera.update(mph(30f), 10f, 2_000L).bearing
        val distanceFrom350 = Math.abs(((result - 350f + 540f) % 360f) - 180f)

        assertTrue("bearing $result swung too far from 350", distanceFrom350 < 25f)
    }

    @Test
    fun headingIsAlwaysANormalisedCompassBearing() {
        val camera = camera()
        val values = listOf(-90f, 450f, 720f, -370f, 180f)
        for (raw in values) {
            repeat(20) { camera.update(mph(30f), raw, 1_000L) }
            val bearing = camera.update(mph(30f), raw, 1_000L).bearing
            assertTrue("$raw produced $bearing", bearing >= 0f && bearing < 360f)
        }
    }

    @Test
    fun resetClearsEasingAndAnyPinchHold() {
        val camera = camera()
        repeat(30) { i -> camera.update(mph(70f), 90f, i * 1_000L) }
        camera.onUserZoomGesture(30_000L)

        camera.reset()

        assertFalse(camera.isUserControllingZoom(30_001L))
        // First update after a reset jumps straight to the right zoom for the speed
        // instead of easing down from the previous ride's value.
        val target = camera.update(0f, 0f, 31_000L)
        assertEquals(camera.targetZoomForSpeed(0f), target.zoom!!, 1e-9)
    }
}
