package com.motorider.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nominatim's `display_name` is the string the old picker showed whole, on one
 * ellipsised line. These cover the split that replaced it.
 */
class PlaceSearchTest {

    @Test
    fun `place name splits into the place and where it is`() {
        val (primary, secondary) = RouteUtils.splitPlaceName(
            "Snake Pass, Glossop, High Peak, Derbyshire, England, United Kingdom"
        )
        assertEquals("Snake Pass", primary)
        assertTrue("secondary should locate the place", secondary.startsWith("Glossop"))
    }

    @Test
    fun `explicit name field wins over the first component`() {
        val (primary, _) = RouteUtils.splitPlaceName(
            "12, Market Street, Buxton, Derbyshire", name = "The Old Hall Hotel"
        )
        assertEquals("The Old Hall Hotel", primary)
    }

    @Test
    fun `postcodes are dropped from the secondary line`() {
        val (_, secondary) = RouteUtils.splitPlaceName(
            "Cat and Fiddle, Buxton, SK17 0AR, United Kingdom"
        )
        assertTrue("postcode should not survive: $secondary", !secondary.contains("SK17"))
    }

    @Test
    fun `secondary line is capped so it fits on one row`() {
        val (_, secondary) = RouteUtils.splitPlaceName(
            "A, B, C, D, E, F, G, H"
        )
        assertEquals("B, C, D", secondary)
    }

    @Test
    fun `single component name has no secondary line`() {
        val (primary, secondary) = RouteUtils.splitPlaceName("Wales")
        assertEquals("Wales", primary)
        assertEquals("", secondary)
    }

    @Test
    fun `suggestions carry the coordinates nominatim resolved`() {
        val json = """
            [{"display_name":"Hartington, Derbyshire, England","name":"Hartington",
              "lat":"53.1387","lon":"-1.8085"}]
        """.trimIndent()

        val results = RouteUtils.parseSearchResponse(json)

        assertEquals(1, results.size)
        // The whole point of carrying these: a picked place is never geocoded twice,
        // so it cannot resolve to somewhere else the second time.
        assertEquals(53.1387, results[0].lat, 1e-6)
        assertEquals(-1.8085, results[0].lon, 1e-6)
        assertEquals("Hartington", results[0].primaryName)
    }

    @Test
    fun `entries without usable coordinates are skipped rather than guessed at`() {
        val json = """
            [{"display_name":"Nowhere"},
             {"display_name":"Bakewell, Derbyshire","lat":"53.2131","lon":"-1.6753"}]
        """.trimIndent()

        val results = RouteUtils.parseSearchResponse(json)

        assertEquals(1, results.size)
        assertEquals("Bakewell", results[0].primaryName)
    }

    @Test
    fun `empty response yields no suggestions`() {
        assertEquals(0, RouteUtils.parseSearchResponse("[]").size)
        assertEquals(0, RouteUtils.parseSearchResponse("").size)
        assertEquals(0, RouteUtils.parseSearchResponse(null).size)
    }
}
