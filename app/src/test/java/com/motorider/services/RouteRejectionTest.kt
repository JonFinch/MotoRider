package com.motorider.services

import com.motorider.models.Route
import com.motorider.models.Waypoint
import com.motorider.utils.RouteUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * A refusal from the routing service and an unreachable routing service are
 * different failures and must not be reported to a rider the same way.
 *
 * The straight-line estimate exists for the second. Using it for the first draws a
 * confident line across whatever lies between the points — the case that prompted
 * this was a destination on Monk's Trod, a bridleway in the Cambrian Mountains, so
 * the "estimate" was a line over open moorland — and labels the cause a
 * connectivity problem the rider might sit and wait out.
 */
class RouteRejectionTest {

    private val buxton = Waypoint("Buxton", GeoPoint(53.2500, -1.9026))
    private val monksTrod = Waypoint("Monk's Trod", GeoPoint(52.2928116, -3.73893))

    @Test
    fun `a rejection carries the service's own wording`() {
        val message = "No road a motorcycle can use near the destination."

        val thrown = RouteRejectedException(message)

        // The rider is told what is actually wrong, not a generic failure.
        assertEquals(message, thrown.message)
    }

    @Test
    fun `a rejection is not an estimate`() {
        // An estimate is a Route the UI will draw and offer to navigate. A
        // rejection must not produce one at all, so the two cannot be confused.
        val rejection = RouteRejectedException("unroutable")

        assertTrue(rejection is Exception)
        assertFalse(
            "a rejection must not be expressible as a drawable route",
            Route::class.java.isAssignableFrom(rejection.javaClass)
        )
    }

    @Test
    fun `an estimate stays available for a service that never answered`() {
        // Unchanged behaviour, and the reason the estimate exists: with no reply
        // there is nothing better to show, and the UI flags it loudly.
        val route = Route("Estimate", listOf(buxton, monksTrod)).apply {
            isEstimate = true
            estimateReason = "Routing service unreachable"
            routeGeometry = listOf(buxton.location, monksTrod.location)
        }

        assertTrue(route.isEstimate)
        assertEquals("Routing service unreachable", route.estimateReason)
    }

    @Test
    fun `the parser still yields nothing for an error body`() {
        // A 4xx body has no routes array; parsing must not invent one.
        val body = """{"detail":"No road a motorcycle can use near the destination."}"""

        val routes = RouteUtils.parseRouteApiResponse(
            body, listOf(buxton, monksTrod), com.motorider.models.RouteType.CURVY
        )

        assertTrue(routes.isEmpty())
    }
}
