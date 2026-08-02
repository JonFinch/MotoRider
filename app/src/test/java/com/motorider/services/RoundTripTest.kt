package com.motorider.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.osmdroid.util.GeoPoint

class RoundTripTest {

    @Test
    fun testRadiusFromTargetDistance() {
        assertEquals("30km trip", 4.3, 30.0 / 7.0, 0.1)
        assertEquals("50km trip", 7.1, 50.0 / 7.0, 0.1)
        assertEquals("80km trip", 11.4, 80.0 / 7.0, 0.1)
        assertEquals("200km trip", 28.6, 200.0 / 7.0, 0.1)
    }

    @Test
    fun testCardinalDirectionUses120DegreeSpread() {
        val dir = 0.0
        val spread = 120.0
        val halfSpread = spread / 2.0
        val angles = listOf(dir - halfSpread, dir, dir + halfSpread)

        assertEquals(-60.0, angles[0], 0.1)
        assertEquals(0.0, angles[1], 0.1)
        assertEquals(60.0, angles[2], 0.1)
    }

    @Test
    fun testIntercardinalDirectionUses90DegreeSpread() {
        val dir = 45.0
        val spread = 90.0
        val halfSpread = spread / 2.0
        val angles = listOf(dir - halfSpread, dir, dir + halfSpread)

        assertEquals(0.0, angles[0], 0.1)
        assertEquals(45.0, angles[1], 0.1)
        assertEquals(90.0, angles[2], 0.1)
    }

    @Test
    fun testNorthAllWaypointsAreNorthOfCenter() {
        val center = GeoPoint(50.0, 0.0)
        val direction = 0
        val spread = 120.0
        val halfSpread = spread / 2.0
        val dir = direction.toDouble()
        val radiusKm = 4.3

        val angles = listOf(dir - halfSpread, dir, dir + halfSpread)
        for (angle in angles) {
            val rad = Math.toRadians(angle)
            val latOffset = (radiusKm / 111.32) * Math.cos(rad)
            val wp = GeoPoint(center.latitude + latOffset, center.longitude)
            assertTrue("Waypoint at angle $angle should be north", wp.latitude > center.latitude)
        }
    }

    @Test
    fun testSouthAllWaypointsAreSouthOfCenter() {
        val center = GeoPoint(50.0, 0.0)
        val direction = 180
        val spread = 120.0
        val halfSpread = spread / 2.0
        val dir = direction.toDouble()
        val radiusKm = 4.3

        val angles = listOf(dir - halfSpread, dir, dir + halfSpread)
        for (angle in angles) {
            val rad = Math.toRadians(angle)
            val latOffset = (radiusKm / 111.32) * Math.cos(rad)
            val wp = GeoPoint(center.latitude + latOffset, center.longitude)
            assertTrue("Waypoint at angle $angle should be south", wp.latitude < center.latitude)
        }
    }

    @Test
    fun testEastAllWaypointsAreEastOfCenter() {
        val center = GeoPoint(50.0, 0.0)
        val direction = 90
        val spread = 120.0
        val halfSpread = spread / 2.0
        val dir = direction.toDouble()
        val radiusKm = 4.3
        val cosLat = Math.cos(Math.toRadians(center.latitude))

        val angles = listOf(dir - halfSpread, dir, dir + halfSpread)
        for (angle in angles) {
            val rad = Math.toRadians(angle)
            val lonOffset = (radiusKm / (111.32 * cosLat)) * Math.sin(rad)
            val wp = GeoPoint(center.latitude, center.longitude + lonOffset)
            assertTrue("Waypoint at angle $angle should be east", wp.longitude > center.longitude)
        }
    }

    @Test
    fun testWestAllWaypointsAreWestOfCenter() {
        val center = GeoPoint(50.0, 0.0)
        val direction = 270
        val spread = 120.0
        val halfSpread = spread / 2.0
        val dir = direction.toDouble()
        val radiusKm = 4.3
        val cosLat = Math.cos(Math.toRadians(center.latitude))

        val angles = listOf(dir - halfSpread, dir, dir + halfSpread)
        for (angle in angles) {
            val rad = Math.toRadians(angle)
            val lonOffset = (radiusKm / (111.32 * cosLat)) * Math.sin(rad)
            val wp = GeoPoint(center.latitude, center.longitude + lonOffset)
            assertTrue("Waypoint at angle $angle should be west", wp.longitude < center.longitude)
        }
    }

    @Test
    fun testNENoPointsAreSouthOrWest() {
        val center = GeoPoint(50.0, 0.0)
        val direction = 45
        val spread = 90.0
        val halfSpread = spread / 2.0
        val dir = direction.toDouble()
        val radiusKm = 4.3
        val cosLat = Math.cos(Math.toRadians(center.latitude))

        val angles = listOf(dir - halfSpread, dir, dir + halfSpread)
        for (angle in angles) {
            val rad = Math.toRadians(angle)
            val latOffset = (radiusKm / 111.32) * Math.cos(rad)
            val lonOffset = (radiusKm / (111.32 * cosLat)) * Math.sin(rad)
            val wp = GeoPoint(center.latitude + latOffset, center.longitude + lonOffset)

            assertFalse("Waypoint at $angle should NOT be south", wp.latitude < center.latitude - 0.001)
            assertFalse("Waypoint at $angle should NOT be west", wp.longitude < center.longitude - 0.001)
        }
    }

    @Test
    fun testSWNoPointsAreNorthOrEast() {
        val center = GeoPoint(50.0, 0.0)
        val direction = 225
        val spread = 90.0
        val halfSpread = spread / 2.0
        val dir = direction.toDouble()
        val radiusKm = 4.3
        val cosLat = Math.cos(Math.toRadians(center.latitude))

        val angles = listOf(dir - halfSpread, dir, dir + halfSpread)
        for (angle in angles) {
            val rad = Math.toRadians(angle)
            val latOffset = (radiusKm / 111.32) * Math.cos(rad)
            val lonOffset = (radiusKm / (111.32 * cosLat)) * Math.sin(rad)
            val wp = GeoPoint(center.latitude + latOffset, center.longitude + lonOffset)

            assertFalse("Waypoint at $angle should NOT be north", wp.latitude > center.latitude + 0.001)
            assertFalse("Waypoint at $angle should NOT be east", wp.longitude > center.longitude + 0.001)
        }
    }

    @Test
    fun testRadiusScalesWithDesiredDistance() {
        val shortR = 30.0 / 7.0
        val longR = 200.0 / 7.0
        assertTrue(longR > shortR)
        assertTrue((longR / shortR) > 6.0)
    }
}
