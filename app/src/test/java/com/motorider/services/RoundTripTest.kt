package com.motorider.services

import com.motorider.models.Avoidance
import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * The request the app sends for a Quick Ride loop.
 *
 * The loop itself is built by the routing service — see Gate H of MotoRiderMaps'
 * `scripts/verify_routing.py`, which covers the loop closing on the start, its
 * length matching the request, and the absence of a long out-and-back. What is
 * this side's to get right is the contract: the right field names, and metres.
 *
 * This replaced a suite that re-implemented the old compass-circle geometry inline
 * and asserted against its own arithmetic. It referenced no production code, so it
 * could not fail whatever the app did, and it described a construction that no
 * longer exists.
 */
class RoundTripTest {

    private val service = RouteService()
    private val here = Waypoint("Here", GeoPoint(51.45, 0.12))

    private fun roundTripBody(km: Double, headingDeg: Double = 90.0) =
        service.buildRequestBody(
            start = here,
            end = here,
            waypoints = null,
            routePreference = RouteType.CURVY,
            avoidances = emptySet(),
            roundTrip = RouteService.RoundTripRequest(km * 1000.0, headingDeg)
        )

    @Test
    fun `loop distance is sent in metres, not kilometres`() {
        // The UI works in kilometres and the API takes metres. Getting this wrong
        // asks for a 58 metre loop, which is the kind of unit slip this codebase
        // keeps a table about.
        val body = roundTripBody(58.0)

        assertEquals(58_000.0, body.getJSONObject("round_trip").getDouble("distance_m"), 0.001)
    }

    @Test
    fun `heading is passed through unchanged`() {
        val body = roundTripBody(40.0, headingDeg = 225.0)

        assertEquals(225.0, body.getJSONObject("round_trip").getDouble("heading_deg"), 0.001)
    }

    @Test
    fun `a loop sends no via points`() {
        // The service generates its own on the network. Sending compass-circle
        // points alongside is exactly what produced the spurs.
        val body = roundTripBody(58.0)

        assertFalse(body.has("waypoints"))
    }

    @Test
    fun `a loop still carries the ride style and avoidances`() {
        val body = service.buildRequestBody(
            start = here,
            end = here,
            waypoints = null,
            routePreference = RouteType.EXTRA_CURVY,
            avoidances = setOf(Avoidance.FERRIES),
            roundTrip = RouteService.RoundTripRequest(30_000.0, 0.0)
        )

        assertEquals("extra_curvy", body.getString("curviness"))
        assertEquals("ferry", body.getJSONArray("avoidances").getString(0))
    }

    @Test
    fun `an ordinary route sends waypoints and no round trip block`() {
        val via = Waypoint("Via", GeoPoint(51.5, 0.2))
        val end = Waypoint("End", GeoPoint(51.6, 0.3))

        val body = service.buildRequestBody(
            start = here,
            end = end,
            waypoints = listOf(via),
            routePreference = RouteType.CURVY,
            avoidances = emptySet()
        )

        assertFalse(body.has("round_trip"))
        assertEquals(1, body.getJSONArray("waypoints").length())
        assertEquals(51.5, body.getJSONArray("waypoints").getJSONObject(0).getDouble("lat"), 1e-9)
    }

    @Test
    fun `a loop starts and ends at the rider`() {
        val body = roundTripBody(58.0)

        assertTrue(
            body.getJSONObject("start").getDouble("lat") ==
                body.getJSONObject("end").getDouble("lat")
        )
    }
}
