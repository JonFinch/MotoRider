package com.motorider.utils

import com.motorider.models.ManeuverType
import com.motorider.navigation.defaultTtsTriggerZones
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Manoeuvres now come from the routing service rather than being derived from
 * geometry. Geometry cannot tell a bend from a junction — they are the same
 * polyline — so the derived path announced every corner.
 */
class ServiceInstructionsTest {

    private val base = GeoPoint(53.2500, -1.9026)

    /** A straight 1 km eastward line sampled every 100 m. */
    private fun geometry(): List<GeoPoint> = (0..10).map {
        GeoPoint(base.latitude, base.longitude + it * (100.0 / (111319.49 * Math.cos(Math.toRadians(base.latitude)))))
    }

    private fun instructions(json: String) = JSONArray(json)

    @Test
    fun `service instructions are used verbatim, not re-derived`() {
        val json = """
            [{"sign":0,"text":"Continue onto Duke's Drive","street_name":"Duke's Drive","interval":[0,2]},
             {"sign":-2,"text":"Turn left onto Tagg Lane","street_name":"Tagg Lane","interval":[2,7]},
             {"sign":4,"text":"Arrive at destination","interval":[7,10]}]
        """.trimIndent()

        val result = RouteUtils.parseInstructions(instructions(json), geometry(), 600.0)

        assertNotNull(result)
        assertEquals(3, result!!.size)
        assertEquals(ManeuverType.CONTINUE, result[0].maneuverType)
        assertEquals(ManeuverType.TURN_LEFT, result[1].maneuverType)
        assertEquals(ManeuverType.ARRIVE, result[2].maneuverType)
        assertEquals("Tagg Lane", result[1].roadName)
    }

    @Test
    fun `interval start becomes distance along the route`() {
        val json = """[{"sign":-2,"text":"Turn left","interval":[5,8]}]"""

        val result = RouteUtils.parseInstructions(instructions(json), geometry(), 0.0)!!

        // Vertex 5 of a 100 m-spaced line is 500 m in.
        assertEquals(500.0, result[0].distanceAlongRoute, 5.0)
        assertEquals(5, result[0].segmentIndex)
    }

    @Test
    fun `a road number is preferred over the street name`() {
        // A rider follows signs, and the signs say A6, not Fairfield Road.
        val json = """
            [{"sign":2,"text":"Turn right onto Fairfield Road",
              "street_name":"Fairfield Road","street_ref":"A6","interval":[1,4]}]
        """.trimIndent()

        val result = RouteUtils.parseInstructions(instructions(json), geometry(), 0.0)!!

        assertEquals("A6", result[0].roadName)
    }

    @Test
    fun `roundabout carries its exit number`() {
        val json = """
            [{"sign":6,"exit_number":2,"text":"At roundabout, take exit 2",
              "street_name":"Rutland Square","interval":[1,4]}]
        """.trimIndent()

        val result = RouteUtils.parseInstructions(instructions(json), geometry(), 0.0)!!

        assertEquals(ManeuverType.ROUNDABOUT, result[0].maneuverType)
        assertEquals(2, result[0].roundaboutExit)
    }

    @Test
    fun `exit number is ignored on manoeuvres that are not roundabouts`() {
        val json = """[{"sign":-2,"text":"Turn left","exit_number":3,"interval":[1,4]}]"""

        val result = RouteUtils.parseInstructions(instructions(json), geometry(), 0.0)!!

        assertNull(result[0].roundaboutExit)
    }

    @Test
    fun `every GraphHopper sign code maps to a manoeuvre`() {
        // -98/-8/8 u-turns, -7/7 keep, -3..3 turns, 4 arrive, 5 waypoint, 6 roundabout.
        val signs = listOf(-98, -8, -7, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8)
        val json = signs.joinToString(",", "[", "]") {
            """{"sign":$it,"text":"x","interval":[1,2]}"""
        }

        val result = RouteUtils.parseInstructions(instructions(json), geometry(), 0.0)!!

        assertEquals(signs.size, result.size)
        assertEquals(ManeuverType.UTURN, result[0].maneuverType)
        assertEquals(ManeuverType.KEEP_LEFT, result[2].maneuverType)
        assertEquals(ManeuverType.TURN_SHARP_LEFT, result[3].maneuverType)
        assertEquals(ManeuverType.ROUNDABOUT, result[12].maneuverType)
        assertEquals(ManeuverType.KEEP_RIGHT, result[13].maneuverType)
    }

    @Test
    fun `an unknown sign is kept rather than dropped`() {
        // A manoeuvre the app cannot name is still somewhere the rider must be
        // told about, and the service's own text is shown alongside.
        val json = """[{"sign":99,"text":"Something new","interval":[1,4]}]"""

        val result = RouteUtils.parseInstructions(instructions(json), geometry(), 0.0)!!

        assertEquals(1, result.size)
        assertEquals(ManeuverType.CONTINUE, result[0].maneuverType)
        assertEquals("Something new", result[0].instruction)
    }

    @Test
    fun `no instructions means fall back, not navigate with none`() {
        assertNull(RouteUtils.parseInstructions(null, geometry(), 0.0))
        assertNull(RouteUtils.parseInstructions(JSONArray("[]"), geometry(), 0.0))
    }

    @Test
    fun `an interval past the end of the geometry is clamped rather than crashing`() {
        val json = """[{"sign":-2,"text":"Turn left","interval":[999,1000]}]"""

        val result = RouteUtils.parseInstructions(instructions(json), geometry(), 0.0)!!

        assertTrue(result[0].segmentIndex <= geometry().lastIndex)
    }

    @Test
    fun `remaining distance and time count down towards the destination`() {
        val json = """
            [{"sign":0,"text":"a","interval":[0,1]},
             {"sign":-2,"text":"b","interval":[5,6]},
             {"sign":4,"text":"c","interval":[10,10]}]
        """.trimIndent()

        val result = RouteUtils.parseInstructions(instructions(json), geometry(), 600.0)!!

        assertTrue(result[0].distanceRemaining > result[1].distanceRemaining)
        assertTrue(result[1].distanceRemaining > result[2].distanceRemaining)
        assertEquals(0.0, result.last().distanceRemaining, 1.0)
        assertEquals(0.0, result.last().timeRemaining, 1.0)
    }

    @Test
    fun `every manoeuvre type has a spoken trigger distance`() {
        // The lookup in maybeSpeakInstruction returns early on a missing entry, so
        // a type added to the enum and forgotten here is never announced at all —
        // silent failure on the one feature used without looking at the screen.
        val zones = defaultTtsTriggerZones()
        val missing = ManeuverType.entries.filterNot { zones.containsKey(it) }

        assertTrue("no TTS trigger distance for $missing", missing.isEmpty())
    }
}
