package com.motorider.navigation

import com.motorider.models.Route
import com.motorider.models.Waypoint
import com.motorider.utils.totalRouteDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * The map splits the route line at the rider to show what has already been ridden.
 * That split needs [NavigationUIState.routeSegmentIndex] to point at the vertex the
 * rider has actually passed — the index and the snapped position have to agree, or
 * the join between the two coloured lines jumps around.
 */
class RideProgressTest {

    private val base = GeoPoint(51.5000, -0.1000)

    private fun offset(north: Double, east: Double): GeoPoint {
        val dLat = north / 111319.49
        val dLon = east / (111319.49 * Math.cos(Math.toRadians(base.latitude)))
        return GeoPoint(base.latitude + dLat, base.longitude + dLon)
    }

    /** 1 km east sampled every 100 m. */
    private fun straightGeometry(): List<GeoPoint> = (0..10).map { offset(0.0, it * 100.0) }

    private fun routeOf(geometry: List<GeoPoint> = straightGeometry()): Route {
        val waypoints = listOf(Waypoint("Start", geometry.first()), Waypoint("End", geometry.last()))
        return Route("Test", waypoints).apply {
            routeGeometry = geometry
            distance = totalRouteDistance(geometry) / 1000.0
            duration = 10.0
        }
    }

    private fun manager() = NavigationManager().apply { clock = { 1_000L } }

    @Test
    fun `the published index is the vertex the rider has passed`() {
        val manager = manager()
        manager.startNavigation(routeOf())

        // 450 m along a line with vertices every 100 m: past vertex 4, short of 5.
        manager.setPosition(offset(0.0, 450.0), 15f)

        assertEquals(4, manager.uiState.value.routeSegmentIndex)
    }

    @Test
    fun `the index advances as the rider moves`() {
        val manager = manager()
        manager.startNavigation(routeOf())

        manager.setPosition(offset(0.0, 150.0), 15f)
        val early = manager.uiState.value.routeSegmentIndex
        manager.setPosition(offset(0.0, 850.0), 15f)
        val late = manager.uiState.value.routeSegmentIndex

        assertTrue("expected $late > $early", late > early)
    }

    @Test
    fun `the index and the snapped position agree`() {
        // The split uses both: points up to the index, then the position. If the
        // position were not between that vertex and the next, the travelled line
        // would double back on itself at the seam.
        val manager = manager()
        val geometry = straightGeometry()
        manager.startNavigation(routeOf(geometry))

        manager.setPosition(offset(0.0, 620.0), 15f)

        val state = manager.uiState.value
        val index = state.routeSegmentIndex
        val position = state.position!!
        assertTrue(index in geometry.indices)
        assertTrue(
            "snapped position must lie at or beyond the published vertex",
            position.longitude >= geometry[index].longitude - 1e-9
        )
    }

    @Test
    fun `a replacement route re-derives the index rather than carrying it over`() {
        val manager = manager()
        manager.startNavigation(routeOf())
        manager.setPosition(offset(0.0, 800.0), 15f)
        assertTrue(manager.uiState.value.routeSegmentIndex > 0)

        // A recalculation hands over a route beginning where the rider now is —
        // they have ridden none of it. Carrying the old index over would grey out
        // the first 800 m of a route they have not touched.
        val fromHere = (0..10).map { offset(it * 100.0, 800.0) }
        manager.replaceRoute(routeOf(fromHere))

        assertEquals(0, manager.uiState.value.routeSegmentIndex)
    }
}
