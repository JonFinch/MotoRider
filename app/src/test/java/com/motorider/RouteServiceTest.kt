package com.motorider.services

import com.motorider.models.Avoidance
import com.motorider.models.RouteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteServiceTest {

    @Test
    fun testAvoidanceEnumDisplayNames() {
        assert("Highways" == Avoidance.HIGHWAYS.displayName) { "HIGHWAYS display name" }
        assert("Toll Roads" == Avoidance.TOLLS.displayName) { "TOLLS display name" }
        assert("Ferries" == Avoidance.FERRIES.displayName) { "FERRIES display name" }
        assert("Unpaved Roads" == Avoidance.UNPAVED_ROADS.displayName) { "UNPAVED_ROADS display name" }
        assert("Tracks & Service Roads" == Avoidance.TRACKS_AND_SERVICE.displayName) { "TRACKS_AND_SERVICE display name" }
    }

    @Test
    fun testRouteTypeSpeedFactors() {
        assert(1.2 == RouteType.DIRECT.getSpeedFactor()) { "DIRECT speed factor" }
        assert(1.0 == RouteType.FAST.getSpeedFactor()) { "FAST speed factor" }
        assert(0.8 == RouteType.CURVY.getSpeedFactor()) { "CURVY speed factor" }
        assert(0.6 == RouteType.EXTRA_CURVY.getSpeedFactor()) { "EXTRA_CURVY speed factor" }
    }

    @Test
    fun testAvoidanceApiValues() {
        assertEquals("motorway", Avoidance.HIGHWAYS.apiValue)
        assertEquals("toll", Avoidance.TOLLS.apiValue)
        assertEquals("ferry", Avoidance.FERRIES.apiValue)
        // Both are now backed by the routing API (surface / road_class rules),
        // so every checkbox in the avoidance dialog has a real effect.
        assertEquals("unpaved", Avoidance.UNPAVED_ROADS.apiValue)
        assertEquals("narrow", Avoidance.TRACKS_AND_SERVICE.apiValue)
    }

    @Test
    fun testAvoidanceSetOperations() {
        val set = setOf(Avoidance.HIGHWAYS, Avoidance.TOLLS)
        assert(set.size == 2)
        assert(set.contains(Avoidance.HIGHWAYS))
        assert(set.contains(Avoidance.TOLLS))
        assert(!set.contains(Avoidance.FERRIES))
    }
}
