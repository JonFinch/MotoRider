package com.motorider.maps

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * [routeFramingBox] is the pure geometry behind camera framing: given a route's
 * points, the box the camera should be zoomed to. Coordinates are around London so
 * longitude scaling is representative rather than equatorial.
 */
class RouteFramingTest {

    @Test
    fun emptyRouteHasNoFramingBox() {
        assertNull(routeFramingBox(emptyList()))
    }

    @Test
    fun boxContainsEveryPoint() {
        val points = listOf(
            GeoPoint(51.6565, -0.3903), // Watford
            GeoPoint(51.7500, -0.3400),
            GeoPoint(51.7520, -0.3363)  // St Albans
        )

        val box = routeFramingBox(points)!!
        for (p in points) {
            assertTrue("north bound should clear ${p.latitude}", box.latNorth >= p.latitude)
            assertTrue("south bound should clear ${p.latitude}", box.latSouth <= p.latitude)
            assertTrue("east bound should clear ${p.longitude}", box.lonEast >= p.longitude)
            assertTrue("west bound should clear ${p.longitude}", box.lonWest <= p.longitude)
        }
    }

    @Test
    fun boxIsPaddedBeyondTheRawRouteExtent() {
        val points = listOf(GeoPoint(51.0, -0.5), GeoPoint(51.2, -0.2))
        val box = routeFramingBox(points)!!

        assertTrue(box.latNorth > 51.2)
        assertTrue(box.latSouth < 51.0)
        assertTrue(box.lonEast > -0.2)
        assertTrue(box.lonWest < -0.5)
    }

    @Test
    fun paddingIsBiggerToTheSouthThanTheNorth() {
        // The planning sheet docks along the bottom of the screen, so the box should
        // be pushed further south than north to keep the route clear of it.
        val points = listOf(GeoPoint(51.0, -0.5), GeoPoint(51.2, -0.2))
        val box = routeFramingBox(points)!!

        val northPad = box.latNorth - 51.2
        val southPad = 51.0 - box.latSouth
        assertTrue(southPad > northPad)
    }

    @Test
    fun singleStackedPointDoesNotProduceAZeroSizeBox() {
        val samePoint = GeoPoint(51.5, -0.1)
        val box = routeFramingBox(listOf(samePoint, samePoint, samePoint))!!

        assertTrue(box.latNorth > box.latSouth)
        assertTrue(box.lonEast > box.lonWest)
    }

    @Test
    fun singlePointRouteIsFramable() {
        val box = routeFramingBox(listOf(GeoPoint(51.5, -0.1)))!!
        assertTrue(box.latNorth > box.latSouth)
        assertTrue(box.lonEast > box.lonWest)
    }
}
