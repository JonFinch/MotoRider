package com.motorider.models

import org.osmdroid.util.GeoPoint

/**
 * The kinds of stop the rider can divert to mid-ride.
 *
 * Deliberately a closed set of two rather than a general "find nearby" search. A
 * rider at speed is choosing between two things they urgently need — fuel and
 * food — and each gets its own button so neither costs a category selection first.
 *
 * [apiValue] is what MotoRiderMaps' POI endpoints call it; the mapping from there
 * to OSM amenity tags is the server's business, so widening "restaurant" to take
 * in cafes and pubs does not need an app release.
 */
enum class PoiCategory(val apiValue: String) {
    FUEL("fuel"),
    RESTAURANT("restaurant")
}

/**
 * A place the rider could divert to.
 *
 * [distanceAlongRouteMeters] is how far along the remaining route the stop sits, and
 * is null for anything found off route — which is the distinction the picker draws,
 * because a petrol station 200 m ahead on the road being ridden is a different
 * proposition from one 3 km away across country.
 *
 * [openingHours] is OSM's raw `opening_hours` string, passed through unparsed and
 * unjudged all the way from the database. See [com.motorider.services.PoiService]
 * for why nothing here claims a place is open.
 */
data class PoiResult(
    val name: String,
    val location: GeoPoint,
    val category: PoiCategory,
    /** Straight-line metres from the rider. Always known. */
    val directDistanceMeters: Double,
    /** Metres along the remaining route, or null when this is not on the route. */
    val distanceAlongRouteMeters: Double? = null,
    /** Metres from the route line itself, or null when this is not on the route. */
    val offsetFromRouteMeters: Double? = null,
    val brand: String? = null,
    val openingHours: String? = null
) {
    val isOnRoute: Boolean get() = distanceAlongRouteMeters != null
}
