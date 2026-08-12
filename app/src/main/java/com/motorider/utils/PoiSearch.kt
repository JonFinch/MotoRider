package com.motorider.utils

import com.motorider.models.PoiCategory
import com.motorider.models.PoiResult
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

/**
 * The pure half of the mid-ride POI search: what to ask the routing service, and
 * how to read and rank what comes back.
 *
 * Split from [com.motorider.services.PoiService] so all of it can be exercised on
 * the JVM. The network half is a dozen lines; the parts worth being sure of are the
 * corridor sampling and the ranking, and neither needs a socket.
 *
 * Ranking stays here rather than moving server-side because the app already holds
 * the route being ridden. Sending it up so the server could sort by it would mean
 * posting the whole geometry to have an ordering handed back, when the ordering is
 * a dozen lines over data already in memory.
 */
object PoiSearch {

    /** 10 miles, the distance ahead the rider asked to look along the route. */
    const val ON_ROUTE_LOOKAHEAD_METERS = 16_093.4

    /**
     * How far off the road a place may sit and still count as "on route".
     *
     * Generous enough for a filling station set back behind a forecourt or on the
     * far carriageway of a dual carriageway, tight enough that it excludes one in
     * the next village along.
     */
    const val ON_ROUTE_CORRIDOR_METERS = 250.0

    /**
     * Radius for the fallback search around the rider when the route ahead is dry.
     *
     * Wider than the on-route lookahead on purpose: having established there is
     * nothing convenient, the question changes from "what is on my way" to "what
     * can I reach at all", and a rider on reserve would rather see a station 12
     * miles off route than an empty list.
     */
    const val FALLBACK_RADIUS_METERS = 25_000.0

    /**
     * The API matches against the polyline through every coordinate sent, so the
     * corridor is exact — but a 10-mile route at the routing service's vertex
     * spacing is hundreds of points, and the endpoint refuses more than 400.
     * Sampling keeps the request small; 150 m is far finer than the 250 m
     * corridor, so nothing is missed between samples.
     */
    const val CORRIDOR_SAMPLE_SPACING_METERS = 150.0

    /** Hard ceiling on sampled points, whatever the spacing works out to. */
    const val MAX_CORRIDOR_SAMPLES = 120

    /** How many options the picker offers. More than a rider reads at a glance. */
    const val MAX_RESULTS = 3

    /**
     * The stretch of [geometry] the rider has yet to ride, capped at [maxMeters].
     *
     * Starts at [position] rather than the nearest vertex so the corridor does not
     * begin behind the rider, which on a route doubling back on itself would offer
     * a station they passed ten minutes ago.
     */
    fun routeAhead(
        geometry: List<GeoPoint>,
        segmentIndex: Int,
        position: GeoPoint,
        maxMeters: Double = ON_ROUTE_LOOKAHEAD_METERS
    ): List<GeoPoint> {
        if (geometry.isEmpty()) return emptyList()
        if (geometry.size == 1) return listOf(position)

        val from = segmentIndex.coerceIn(0, geometry.lastIndex)
        val ahead = mutableListOf(position)
        var travelled = 0.0
        var previous = position

        for (i in (from + 1)..geometry.lastIndex) {
            val next = geometry[i]
            travelled += previous.distanceToMeters(next)
            ahead.add(next)
            previous = next
            if (travelled >= maxMeters) break
        }
        return ahead
    }

    /**
     * Thin [points] to roughly one every [spacingMeters], always keeping the first
     * and last so the corridor spans the whole stretch.
     */
    fun sampleCorridor(
        points: List<GeoPoint>,
        spacingMeters: Double = CORRIDOR_SAMPLE_SPACING_METERS,
        maxSamples: Int = MAX_CORRIDOR_SAMPLES
    ): List<GeoPoint> {
        if (points.size <= 2) return points

        val sampled = mutableListOf(points.first())
        var sinceLast = 0.0
        for (i in 1 until points.lastIndex) {
            sinceLast += points[i - 1].distanceToMeters(points[i])
            if (sinceLast >= spacingMeters) {
                sampled.add(points[i])
                sinceLast = 0.0
            }
        }
        sampled.add(points.last())

        // Still too many on a long straight? Drop evenly rather than truncating,
        // which would search the first mile intensely and the rest not at all.
        if (sampled.size <= maxSamples) return sampled
        val stride = sampled.size.toDouble() / (maxSamples - 1)
        val thinned = (0 until maxSamples - 1)
            .map { sampled[(it * stride).toInt().coerceAtMost(sampled.lastIndex)] }
        return thinned + sampled.last()
    }

    /** Request body for `POST /poi/corridor`. */
    fun corridorRequest(
        category: PoiCategory,
        corridor: List<GeoPoint>,
        radiusMeters: Double = ON_ROUTE_CORRIDOR_METERS,
        limit: Int = 20
    ): JSONObject = JSONObject().apply {
        put("category", category.apiValue)
        put("corridor", JSONArray().apply {
            corridor.forEach { point ->
                put(JSONArray().apply {
                    put(point.latitude)
                    put(point.longitude)
                })
            }
        })
        put("radius_m", radiusMeters)
        put("limit", limit)
    }

    /** Request body for `POST /poi/nearby`. */
    fun nearbyRequest(
        category: PoiCategory,
        centre: GeoPoint,
        radiusMeters: Double = FALLBACK_RADIUS_METERS,
        limit: Int = 20
    ): JSONObject = JSONObject().apply {
        put("category", category.apiValue)
        put("lat", centre.latitude)
        put("lon", centre.longitude)
        put("radius_m", radiusMeters)
        put("limit", limit)
    }

    /** A POI as the API reported it, before it is measured against a route. */
    data class RawPoi(
        val name: String?,
        val location: GeoPoint,
        val brand: String?,
        val openingHours: String?
    )

    /**
     * Read the `results` array of a POI search response.
     *
     * A result without usable coordinates is skipped rather than defaulted — a POI
     * at 0,0 in the Atlantic would rank as impossibly far away and so never be
     * offered, which hides the bug instead of showing it.
     */
    fun parsePoiResponse(json: String?): List<RawPoi> {
        if (json.isNullOrBlank()) return emptyList()
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()

        val parsed = mutableListOf<RawPoi>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val lat = item.optDouble("lat", Double.NaN)
            val lon = item.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            parsed.add(
                RawPoi(
                    name = item.optString("name").takeIf { it.isNotBlank() && it != "null" },
                    location = GeoPoint(lat, lon),
                    brand = item.optString("brand").takeIf { it.isNotBlank() && it != "null" },
                    openingHours = item.optString("opening_hours")
                        .takeIf { it.isNotBlank() && it != "null" }
                )
            )
        }
        return parsed
    }

    /**
     * Measure each POI against the route ahead and keep the ones genuinely beside
     * it, nearest first *along the road* rather than as the crow flies.
     *
     * Ordering by straight-line distance would be wrong in the one case that
     * matters: on a road looping round a hillside, the station 400 m away over the
     * ridge is 6 km further to ride than the one 2 km ahead on the tarmac.
     */
    fun rankOnRoute(
        pois: List<RawPoi>,
        category: PoiCategory,
        routeAhead: List<GeoPoint>,
        riderPosition: GeoPoint,
        corridorMeters: Double = ON_ROUTE_CORRIDOR_METERS,
        limit: Int = MAX_RESULTS
    ): List<PoiResult> {
        if (routeAhead.size < 2) return emptyList()
        val cumulative = calculateCumulativeDistances(routeAhead)

        return pois.mapNotNull { poi ->
            val nearest = nearestPointOnPolyline(poi.location, routeAhead, cumulative)
            if (nearest.distance > corridorMeters) return@mapNotNull null
            PoiResult(
                name = poi.displayName(category),
                location = poi.location,
                category = category,
                directDistanceMeters = riderPosition.distanceToMeters(poi.location),
                distanceAlongRouteMeters = nearest.distanceAlongRoute,
                offsetFromRouteMeters = nearest.distance,
                brand = poi.brand,
                openingHours = poi.openingHours
            )
        }
            .sortedBy { it.distanceAlongRouteMeters }
            .take(limit)
    }

    /** Rank POIs by straight-line distance from the rider, for the off-route fallback. */
    fun rankByDistance(
        pois: List<RawPoi>,
        category: PoiCategory,
        riderPosition: GeoPoint,
        limit: Int = MAX_RESULTS
    ): List<PoiResult> = pois
        .map { poi ->
            PoiResult(
                name = poi.displayName(category),
                location = poi.location,
                category = category,
                directDistanceMeters = riderPosition.distanceToMeters(poi.location),
                brand = poi.brand,
                openingHours = poi.openingHours
            )
        }
        .sortedBy { it.directDistanceMeters }
        .take(limit)

    /**
     * A usable label for a POI that OSM has not named.
     *
     * Unnamed filling stations are common in rural OSM data, and "Unnamed" three
     * times in a list is no help at all — the brand is often tagged even when the
     * name is not, and failing that the category at least says what it is.
     */
    private fun RawPoi.displayName(category: PoiCategory): String =
        name ?: brand ?: when (category) {
            PoiCategory.FUEL -> "Petrol station"
            PoiCategory.RESTAURANT -> "Place to eat"
        }
}
