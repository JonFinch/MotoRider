package com.motorider.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is allowed to see the API credential.
 *
 * This is the one piece of the auth wiring worth testing on its own: everything else
 * is a header set on a connection, but getting this wrong sends our credential to
 * somebody else's server, and it would not show up as a failure anywhere — the app
 * would keep working perfectly while leaking.
 */
class ApiConfigTest {

    private val api = "https://maps.example.com"
    private val tiles = "https://maps.example.com/styles/basic-preview"
    private val osm = "https://tile.openstreetmap.org"

    private fun own(url: String, tileBase: String = tiles, defaultTiles: Boolean = false) =
        ApiConfig.isOwnEndpoint(url, api, tileBase, defaultTiles)

    @Test
    fun `our own api endpoints are authenticated`() {
        assertTrue(own("https://maps.example.com/route"))
        assertTrue(own("https://maps.example.com/poi/corridor"))
        assertTrue(own("https://maps.example.com/poi/nearby"))
    }

    @Test
    fun `the base url itself is ours, with or without a trailing slash`() {
        assertTrue(own("https://maps.example.com"))
        assertTrue(own("https://maps.example.com/"))
    }

    @Test
    fun `our own tile server is authenticated`() {
        assertTrue(own("https://maps.example.com/styles/basic-preview/10/511/340.png"))
    }

    /**
     * The reason this is not a bare `startsWith`. This host shares the prefix of ours
     * but belongs to someone else; a prefix test would hand them the credential.
     */
    @Test
    fun `a lookalike host sharing our prefix is not ours`() {
        assertFalse(own("https://maps.example.com.evil.test/route"))
        assertFalse(own("https://maps.example.commercial.test/route"))
    }

    @Test
    fun `third party services never see the credential`() {
        assertFalse(own("https://nominatim.openstreetmap.org/reverse?lat=1&lon=2"))
        assertFalse(own("https://tile.openstreetmap.org/10/511/340.png"))
    }

    /** Pointed at public OSM, tiles are a third party and must not be authenticated. */
    @Test
    fun `public osm tiles are excluded even when configured as the tile base`() {
        assertFalse(own("$osm/10/511/340.png", tileBase = osm, defaultTiles = true))
    }

    @Test
    fun `a query string directly on the base is still ours`() {
        assertTrue(own("https://maps.example.com?debug=1"))
    }

    /** An unset base must never match, or every URL would look like ours. */
    @Test
    fun `an empty base matches nothing`() {
        assertFalse(ApiConfig.isOwnEndpoint("https://anything.test/x", "", "", false))
        assertFalse(ApiConfig.isOwnEndpoint("", "", "", false))
    }

    @Test
    fun `http and https are distinct origins`() {
        assertFalse(own("http://maps.example.com/route"))
    }
}
