package com.motorider.utils

import com.motorider.models.PoiCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * The mid-ride fuel/food search, exercised without a network.
 *
 * A straight north-south line at a fixed longitude is used throughout: at UK
 * latitudes one degree of latitude is ~111 km, so distances along it are easy to
 * reason about and assertions can be tight without being brittle.
 */
class PoiSearchTest {

    private val lon = -1.75

    /** A north-south line of [count] points, [stepDeg] apart, from 53.0N. */
    private fun line(count: Int, stepDeg: Double = 0.01): List<GeoPoint> =
        (0 until count).map { GeoPoint(53.0 + it * stepDeg, lon) }

    // ─── routeAhead ──────────────────────────────────────────────────────────

    @Test
    fun `routeAhead starts at the rider, not the nearest vertex`() {
        val geometry = line(5)
        val rider = GeoPoint(53.015, lon)

        val ahead = PoiSearch.routeAhead(geometry, segmentIndex = 1, position = rider)

        assertEquals("first point is the rider", rider, ahead.first())
        assertTrue("everything after is in front", ahead.drop(1).all { it.latitude > rider.latitude })
    }

    @Test
    fun `routeAhead does not include road already ridden`() {
        val geometry = line(5)
        // Rider is at the fourth vertex; the first three are behind them.
        val ahead = PoiSearch.routeAhead(geometry, segmentIndex = 3, position = GeoPoint(53.03, lon))

        assertTrue("nothing behind the rider", ahead.all { it.latitude >= 53.03 })
    }

    @Test
    fun `routeAhead stops at the lookahead cap`() {
        // 200 points 0.01 deg apart is ~222 km, far beyond the 10-mile cap.
        val geometry = line(200)

        val ahead = PoiSearch.routeAhead(geometry, 0, GeoPoint(53.0, lon))
        val length = ahead.zipWithNext().sumOf { (a, b) -> a.distanceToMeters(b) }

        assertTrue(
            "capped near the 10-mile lookahead, was $length m",
            length in PoiSearch.ON_ROUTE_LOOKAHEAD_METERS..(PoiSearch.ON_ROUTE_LOOKAHEAD_METERS + 1200.0)
        )
    }

    @Test
    fun `routeAhead survives a route with no geometry`() {
        assertTrue(PoiSearch.routeAhead(emptyList(), 0, GeoPoint(53.0, lon)).isEmpty())
    }

    @Test
    fun `routeAhead clamps a segment index past the end`() {
        val geometry = line(4)
        val ahead = PoiSearch.routeAhead(geometry, segmentIndex = 99, position = GeoPoint(53.03, lon))
        // Nothing ahead but the rider themselves — must not throw.
        assertEquals(1, ahead.size)
    }

    // ─── sampleCorridor ──────────────────────────────────────────────────────

    @Test
    fun `sampleCorridor keeps both ends`() {
        val points = line(50, stepDeg = 0.001)
        val sampled = PoiSearch.sampleCorridor(points)

        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
    }

    @Test
    fun `sampleCorridor thins a dense line`() {
        // 0.001 deg is ~111 m, so at 150 m spacing roughly every other point survives.
        val points = line(100, stepDeg = 0.001)
        val sampled = PoiSearch.sampleCorridor(points)

        assertTrue("thinned, was ${sampled.size} of ${points.size}", sampled.size < points.size)
        assertTrue("not over-thinned", sampled.size > 10)
    }

    @Test
    fun `sampleCorridor respects the hard cap`() {
        val points = line(5000, stepDeg = 0.001)
        val sampled = PoiSearch.sampleCorridor(points)

        assertTrue(
            "must stay within the endpoint's limit, was ${sampled.size}",
            sampled.size <= PoiSearch.MAX_CORRIDOR_SAMPLES
        )
        // Thinning must stay spread across the whole stretch rather than truncating
        // it, or the far half of the corridor is never searched.
        assertEquals(points.last(), sampled.last())
    }

    @Test
    fun `sampleCorridor leaves a two-point line alone`() {
        val points = line(2)
        assertEquals(points, PoiSearch.sampleCorridor(points))
    }

    // ─── request bodies ──────────────────────────────────────────────────────

    @Test
    fun `corridorRequest sends lat lon pairs and the category the API expects`() {
        val body = PoiSearch.corridorRequest(PoiCategory.FUEL, line(3))

        assertEquals("fuel", body.getString("category"))
        val corridor = body.getJSONArray("corridor")
        assertEquals(3, corridor.length())
        val first = corridor.getJSONArray(0)
        assertEquals(53.0, first.getDouble(0), 1e-9)
        assertEquals(lon, first.getDouble(1), 1e-9)
    }

    @Test
    fun `nearbyRequest carries the fallback radius`() {
        val body = PoiSearch.nearbyRequest(PoiCategory.RESTAURANT, GeoPoint(53.0, lon))

        assertEquals("restaurant", body.getString("category"))
        assertEquals(PoiSearch.FALLBACK_RADIUS_METERS, body.getDouble("radius_m"), 1e-9)
    }

    // ─── parsing ─────────────────────────────────────────────────────────────

    @Test
    fun `parsePoiResponse reads a full result`() {
        val json = """
            {"category":"fuel","count":1,"results":[
              {"name":"Hartington Filling Station","amenity":"fuel","lat":53.14,"lon":-1.81,
               "brand":"Esso","opening_hours":"Mo-Sa 07:00-20:00"}
            ]}
        """.trimIndent()

        val parsed = PoiSearch.parsePoiResponse(json)

        assertEquals(1, parsed.size)
        assertEquals("Hartington Filling Station", parsed[0].name)
        assertEquals("Esso", parsed[0].brand)
        assertEquals("Mo-Sa 07:00-20:00", parsed[0].openingHours)
        assertEquals(53.14, parsed[0].location.latitude, 1e-9)
    }

    @Test
    fun `parsePoiResponse skips a result with no coordinates`() {
        val json = """{"results":[{"name":"Nowhere","amenity":"fuel"}]}"""
        // Dropped rather than defaulted to 0,0 — a POI in the Atlantic would sort
        // last forever and quietly never be offered.
        assertTrue(PoiSearch.parsePoiResponse(json).isEmpty())
    }

    @Test
    fun `parsePoiResponse treats missing optional fields as absent`() {
        val json = """{"results":[{"amenity":"fuel","lat":53.1,"lon":-1.8}]}"""
        val parsed = PoiSearch.parsePoiResponse(json)

        assertEquals(1, parsed.size)
        assertNull("no name means no name, not the string 'null'", parsed[0].name)
        assertNull(parsed[0].openingHours)
    }

    @Test
    fun `parsePoiResponse handles empty and malformed input`() {
        assertTrue(PoiSearch.parsePoiResponse(null).isEmpty())
        assertTrue(PoiSearch.parsePoiResponse("").isEmpty())
        assertTrue(PoiSearch.parsePoiResponse("""{"detail":"boom"}""").isEmpty())
    }

    // ─── ranking ─────────────────────────────────────────────────────────────

    private fun raw(lat: Double, lon: Double, name: String? = "POI") =
        PoiSearch.RawPoi(name = name, location = GeoPoint(lat, lon), brand = null, openingHours = null)

    @Test
    fun `rankOnRoute orders by distance along the road, not as the crow flies`() {
        val ahead = line(20)  // 53.00 -> 53.19, heading north
        val rider = GeoPoint(53.0, lon)

        // `near` is closer in a straight line but further along the route than
        // `far` is... constructed the other way round: `further` sits at 53.15 and
        // `nearer` at 53.05, so along-route order is nearer then further.
        val pois = listOf(raw(53.15, lon, "further"), raw(53.05, lon, "nearer"))

        val ranked = PoiSearch.rankOnRoute(pois, PoiCategory.FUEL, ahead, rider)

        assertEquals(listOf("nearer", "further"), ranked.map { it.name })
        assertTrue("all marked as on route", ranked.all { it.isOnRoute })
    }

    @Test
    fun `rankOnRoute drops anything outside the corridor`() {
        val ahead = line(20)
        val rider = GeoPoint(53.0, lon)
        // ~0.05 deg of longitude at 53N is roughly 3.3 km — well outside 250 m.
        val pois = listOf(raw(53.05, lon + 0.05, "in the next valley"))

        assertTrue(PoiSearch.rankOnRoute(pois, PoiCategory.FUEL, ahead, rider).isEmpty())
    }

    @Test
    fun `rankOnRoute keeps something just off the carriageway`() {
        val ahead = line(20)
        val rider = GeoPoint(53.0, lon)
        // ~0.001 deg of longitude at 53N is about 67 m: a forecourt set back.
        val pois = listOf(raw(53.05, lon + 0.001, "forecourt"))

        val ranked = PoiSearch.rankOnRoute(pois, PoiCategory.FUEL, ahead, rider)

        assertEquals(1, ranked.size)
        assertNotNull(ranked[0].offsetFromRouteMeters)
        assertTrue(ranked[0].offsetFromRouteMeters!! < PoiSearch.ON_ROUTE_CORRIDOR_METERS)
    }

    @Test
    fun `rankOnRoute caps the number of options`() {
        val ahead = line(40)
        val rider = GeoPoint(53.0, lon)
        val pois = (1..10).map { raw(53.0 + it * 0.01, lon, "poi$it") }

        assertEquals(PoiSearch.MAX_RESULTS, PoiSearch.rankOnRoute(pois, PoiCategory.FUEL, ahead, rider).size)
    }

    @Test
    fun `rankByDistance sorts by straight line and leaves route distance unset`() {
        val rider = GeoPoint(53.0, lon)
        val pois = listOf(raw(53.2, lon, "far"), raw(53.05, lon, "near"))

        val ranked = PoiSearch.rankByDistance(pois, PoiCategory.FUEL, rider)

        assertEquals(listOf("near", "far"), ranked.map { it.name })
        assertTrue("off-route results claim no route position", ranked.none { it.isOnRoute })
    }

    @Test
    fun `unnamed places fall back to brand then category`() {
        val rider = GeoPoint(53.0, lon)
        val branded = PoiSearch.RawPoi(null, GeoPoint(53.01, lon), brand = "Shell", openingHours = null)
        val anonymous = PoiSearch.RawPoi(null, GeoPoint(53.02, lon), brand = null, openingHours = null)

        val ranked = PoiSearch.rankByDistance(listOf(branded, anonymous), PoiCategory.FUEL, rider)

        assertEquals("Shell", ranked[0].name)
        assertEquals("Petrol station", ranked[1].name)
    }

    @Test
    fun `rankOnRoute copes with a route too short to search`() {
        val rider = GeoPoint(53.0, lon)
        assertTrue(
            PoiSearch.rankOnRoute(listOf(raw(53.01, lon)), PoiCategory.FUEL, listOf(rider), rider).isEmpty()
        )
    }
}
