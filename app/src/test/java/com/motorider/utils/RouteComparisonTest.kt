package com.motorider.utils

import com.motorider.models.Route
import com.motorider.models.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class RouteComparisonTest {

    private fun route(
        distance: Double,
        duration: Double,
        curvature: Double = 10.0,
        curvatureAvailable: Boolean = true,
        isEstimate: Boolean = false
    ): Route {
        val waypoints = listOf(
            Waypoint("start", GeoPoint(53.0, -1.75)),
            Waypoint("end", GeoPoint(53.1, -1.75))
        )
        return Route("test", waypoints).apply {
            this.distance = distance
            this.duration = duration
            this.curvatureScore = curvature
            this.curvatureAvailable = curvatureAvailable
            this.isEstimate = isEstimate
        }
    }

    @Test
    fun `a single route gets no label`() {
        assertTrue(routeSuperlatives(listOf(route(50.0, 60.0))).isEmpty())
    }

    @Test
    fun `the clearly quickest route is labelled fastest`() {
        val routes = listOf(
            route(distance = 60.0, duration = 90.0, curvature = 10.0),
            route(distance = 62.0, duration = 60.0, curvature = 10.0)
        )
        assertEquals(RouteSuperlative.FASTEST, routeSuperlatives(routes)[1])
    }

    @Test
    fun `the clearly twistiest route is labelled curviest`() {
        val routes = listOf(
            route(distance = 60.0, duration = 60.0, curvature = 10.0),
            route(distance = 61.0, duration = 61.0, curvature = 45.0)
        )
        assertEquals(RouteSuperlative.CURVIEST, routeSuperlatives(routes)[1])
    }

    @Test
    fun `near-identical times are not labelled fastest`() {
        // 60 vs 60.6 minutes: 1%, inside the tolerance. Telling a rider one is
        // "fastest" over 36 seconds would be a distinction they act on and that
        // does not exist.
        val routes = listOf(
            route(distance = 60.0, duration = 60.0, curvature = 10.0),
            route(distance = 60.2, duration = 60.6, curvature = 10.0)
        )
        val labels = routeSuperlatives(routes)
        assertFalse("no fastest on a 1% margin", labels.containsValue(RouteSuperlative.FASTEST))
    }

    @Test
    fun `an exact tie is not labelled`() {
        val routes = listOf(route(60.0, 60.0), route(60.0, 60.0))
        assertTrue(routeSuperlatives(routes).isEmpty())
    }

    @Test
    fun `one route takes at most one label`() {
        // Route 1 is shortest AND fastest. It should say one thing, not two.
        val routes = listOf(
            route(distance = 90.0, duration = 95.0, curvature = 10.0),
            route(distance = 60.0, duration = 60.0, curvature = 10.0)
        )
        val labels = routeSuperlatives(routes)
        assertEquals(1, labels.size)
        assertEquals(RouteSuperlative.FASTEST, labels[1])
    }

    @Test
    fun `curviest outranks fastest when one route is both`() {
        val routes = listOf(
            route(distance = 90.0, duration = 95.0, curvature = 5.0),
            route(distance = 60.0, duration = 60.0, curvature = 50.0)
        )
        assertEquals(RouteSuperlative.CURVIEST, routeSuperlatives(routes)[1])
    }

    @Test
    fun `distinct routes can each hold a different label`() {
        val routes = listOf(
            route(distance = 50.0, duration = 90.0, curvature = 10.0),   // shortest
            route(distance = 70.0, duration = 55.0, curvature = 11.0),   // fastest
            route(distance = 95.0, duration = 120.0, curvature = 60.0)   // curviest
        )
        val labels = routeSuperlatives(routes)

        assertEquals(RouteSuperlative.SHORTEST, labels[0])
        assertEquals(RouteSuperlative.FASTEST, labels[1])
        assertEquals(RouteSuperlative.CURVIEST, labels[2])
    }

    @Test
    fun `curvature is ignored when the service could not supply it`() {
        // Both report 0.0 because there is no data — labelling either "curviest"
        // would be inventing a distinction out of a missing value.
        val routes = listOf(
            route(distance = 60.0, duration = 90.0, curvature = 0.0, curvatureAvailable = false),
            route(distance = 61.0, duration = 60.0, curvature = 0.0, curvatureAvailable = false)
        )
        val labels = routeSuperlatives(routes)

        assertFalse(labels.containsValue(RouteSuperlative.CURVIEST))
        assertEquals(RouteSuperlative.FASTEST, labels[1])
    }

    @Test
    fun `curvature is ignored on a straight-line estimate`() {
        val routes = listOf(
            route(distance = 60.0, duration = 90.0, curvature = 0.0, isEstimate = true),
            route(distance = 61.0, duration = 60.0, curvature = 30.0, isEstimate = true)
        )
        assertFalse(routeSuperlatives(routes).containsValue(RouteSuperlative.CURVIEST))
    }

    @Test
    fun `routes with no usable figures are not labelled`() {
        val routes = listOf(route(0.0, 0.0, 0.0), route(0.0, 0.0, 0.0))
        assertTrue(routeSuperlatives(routes).isEmpty())
    }

    @Test
    fun `a marginally twistier route is not labelled curviest`() {
        // 10.2 against 10.5 curves/km clears the 2% bar the time and distance
        // comparisons use, and describes two roads that ride the same.
        val routes = listOf(
            route(distance = 80.0, duration = 100.0, curvature = 10.2),
            route(distance = 81.0, duration = 101.0, curvature = 10.5)
        )
        assertFalse(routeSuperlatives(routes).containsValue(RouteSuperlative.CURVIEST))
    }

    @Test
    fun `labels are keyed by the index the chips use`() {
        val routes = listOf(
            route(distance = 80.0, duration = 100.0, curvature = 10.0),
            route(distance = 81.0, duration = 101.0, curvature = 10.5),
            route(distance = 50.0, duration = 55.0, curvature = 10.2)
        )
        val labels = routeSuperlatives(routes)

        assertNull("untouched routes carry no label", labels[1])
        assertEquals(RouteSuperlative.FASTEST, labels[2])
    }
}
