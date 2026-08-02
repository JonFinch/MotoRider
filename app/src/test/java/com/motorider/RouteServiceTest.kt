package com.motorider.services

import com.motorider.models.Avoidance
import com.motorider.models.RouteType
import org.junit.Test

class RouteServiceTest {

    @Test
    fun testAvoidanceEnumDisplayNames() {
        assert("Highways" == Avoidance.HIGHWAYS.displayName) { "HIGHWAYS display name" }
        assert("Toll Roads" == Avoidance.TOLLS.displayName) { "TOLLS display name" }
        assert("Ferries" == Avoidance.FERRIES.displayName) { "FERRIES display name" }
        assert("Unpaved Roads" == Avoidance.UNPAVED_ROADS.displayName) { "UNPAVED_ROADS display name" }
        assert("Narrow Roads" == Avoidance.NARROW_ROADS.displayName) { "NARROW_ROADS display name" }
    }

    @Test
    fun testRouteTypeCurvatureWeights() {
        assert(0.0 == RouteType.DIRECT.getCurvatureWeight()) { "DIRECT curvature weight" }
        assert(0.3 == RouteType.FAST.getCurvatureWeight()) { "FAST curvature weight" }
        assert(0.7 == RouteType.CURVY.getCurvatureWeight()) { "CURVY curvature weight" }
        assert(1.0 == RouteType.EXTRA_CURVY.getCurvatureWeight()) { "EXTRA_CURVY curvature weight" }
    }

    @Test
    fun testRouteTypeSpeedFactors() {
        assert(1.2 == RouteType.DIRECT.getSpeedFactor()) { "DIRECT speed factor" }
        assert(1.0 == RouteType.FAST.getSpeedFactor()) { "FAST speed factor" }
        assert(0.8 == RouteType.CURVY.getSpeedFactor()) { "CURVY speed factor" }
        assert(0.6 == RouteType.EXTRA_CURVY.getSpeedFactor()) { "EXTRA_CURVY speed factor" }
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
