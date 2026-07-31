package com.motorider.models

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.osmdroid.util.GeoPoint

class RouteModelTest {

    @Test
    fun testRouteConstructor() {
        val route = Route("Test Route", emptyList())
        assertEquals("Route name should match", "Test Route", route.name)
    }

    @Test
    fun testRouteGettersAndSetters() {
        val route = Route("Test Route", emptyList())

        route.distance = 45.5
        assertEquals("Route distance should be updated", 45.5, route.distance, 0.01)

        route.duration = 1800.0
        assertEquals("Route duration should be updated", 1800.0, route.duration, 0.01)

        route.curvatureScore = 85.0
        assertEquals("Route curvature score should be updated", 85.0, route.curvatureScore, 0.01)

        route.elevationGain = 850.0
        assertEquals("Route elevation gain should be updated", 850.0, route.elevationGain, 0.01)
    }

    @Test
    fun testWaypointConstructor() {
        val waypoint = Waypoint("Test Point", GeoPoint(40.7128, -74.0060))
        assertEquals("Waypoint name should match", "Test Point", waypoint.name)
        assertNotNull("Location should not be null", waypoint.location)
    }

    @Test
    fun testWaypointGettersAndSetters() {
        val waypoint = Waypoint("Test Point", GeoPoint(40.7128, -74.0060))

        waypoint.curvature = 45.0
        assertEquals("Waypoint curvature should be updated", 45.0, waypoint.curvature, 0.01)

        waypoint.elevation = 100.0
        assertEquals("Waypoint elevation should be updated", 100.0, waypoint.elevation, 0.01)

        waypoint.description = "Test description"
        assertEquals("Waypoint description should be updated", "Test description", waypoint.description)
    }

    @Test
    fun testWaypointWithNullLocation() {
        try {
            Waypoint("Test Point", GeoPoint(0.0, 0.0))
            // This should not throw an exception
        } catch (e: IllegalArgumentException) {
            fail("Should not throw IllegalArgumentException for valid location")
        }
    }

    @Test
    fun testRouteWithNullWaypoints() {
        try {
            Route("Test Route", emptyList())
            // This should not throw an exception
        } catch (e: IllegalArgumentException) {
            fail("Should not throw IllegalArgumentException for valid waypoints")
        }
    }
}
