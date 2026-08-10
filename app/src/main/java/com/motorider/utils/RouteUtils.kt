package com.motorider.utils

import com.motorider.models.ManeuverType
import com.motorider.models.RouteType
import com.motorider.models.TurnInstruction
import com.motorider.models.Waypoint
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.motorider.config.ApiConfig

object RouteUtils {

    // Singleton executor to avoid leaking threads on every call.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Nominatim's usage policy caps clients at one request a second and asks for a
     * contact address in the User-Agent so an abusive client can be reached before
     * it is blocked outright. Every call here goes through the single-threaded
     * [executor], so holding the gap is a matter of making each request wait its
     * turn rather than any real synchronisation.
     */
    private const val NOMINATIM_USER_AGENT = "MotoRider/1.0 (Android; https://github.com/motorider)"
    private const val NOMINATIM_MIN_INTERVAL_MS = 1100L
    private var lastNominatimRequestAt = 0L

    private fun throttleNominatim() {
        val wait = NOMINATIM_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastNominatimRequestAt)
        if (wait > 0) {
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        lastNominatimRequestAt = System.currentTimeMillis()
    }

    /**
     * A place the rider can pick, with the coordinates Nominatim already resolved.
     *
     * Carrying [lat]/[lon] is the point: a picked suggestion must never be geocoded
     * a second time at routing time. Nominatim ranks by relevance, so a second
     * lookup of the same display name can legitimately return a different place —
     * the rider would be sent somewhere they never chose.
     */
    data class LocationSuggestion(
        val displayName: String,
        /** The place itself: "Snake Pass", "Hartington". */
        val primaryName: String,
        /** Where it is: "Derbyshire, England". Empty when there is nothing to add. */
        val secondaryName: String,
        val lat: Double,
        val lon: Double
    ) {
        val point: GeoPoint get() = GeoPoint(lat, lon)
    }

    interface GeocodingCallback {
        fun onResult(geoPoint: GeoPoint)
        fun onError(error: String)
    }

    fun reverseGeocode(lat: Double, lon: Double, callback: (String?) -> Unit) {
        executor.execute {
            try {
                throttleNominatim()
                val urlObj = URL("${ApiConfig.NOMINATIM_BASE_URL}/reverse?lat=$lat&lon=$lon&format=json&zoom=18")
                val conn = urlObj.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", NOMINATIM_USER_AGENT)

                val response = try {
                    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                        reader.readLines().joinToString("\n")
                    }
                } finally {
                    conn.disconnect()
                }

                val json = JSONObject(response)
                val displayName = json.optString("display_name", null)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(displayName)
                }
            } catch (e: Exception) {
                android.util.Log.w("RouteUtils", "Reverse geocoding failed", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(null)
                }
            }
        }
    }

    /**
     * Place search for the location picker.
     *
     * The result is a [Result] rather than a bare list because "the search service
     * could not be reached" and "there is no such place" need different words in
     * front of the rider: one is worth retrying, the other means fix the spelling.
     * Collapsing both into an empty list is what made the old picker feel broken
     * whenever a tunnel took the signal.
     */
    fun searchLocations(
        query: String,
        centerLat: Double? = null,
        centerLon: Double? = null,
        callback: (Result<List<LocationSuggestion>>) -> Unit
    ) {
        executor.execute {
            val outcome = try {
                throttleNominatim()
                val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                val baseUrl = "${ApiConfig.NOMINATIM_BASE_URL}/search?q=$encoded&format=json&limit=8&addressdetails=1"

                // Bias, not restrict: results inside the box float to the top but a
                // rider planning a trip to the far side of the country still finds it.
                val urlStr = if (centerLat != null && centerLon != null) {
                    val viewboxMinLon = centerLon - 1.0
                    val viewboxMinLat = centerLat - 1.0
                    val viewboxMaxLon = centerLon + 1.0
                    val viewboxMaxLat = centerLat + 1.0
                    "${baseUrl}&viewbox=${viewboxMinLon},${viewboxMinLat},${viewboxMaxLon},${viewboxMaxLat}"
                } else baseUrl

                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", NOMINATIM_USER_AGENT)

                val response = try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw java.io.IOException("HTTP $code")
                    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                        reader.readLines().joinToString("\n")
                    }
                } finally {
                    conn.disconnect()
                }

                Result.success(parseSearchResponse(response))
            } catch (e: Exception) {
                android.util.Log.w("RouteUtils", "Location search failed: $query", e)
                Result.failure(e)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(outcome)
            }
        }
    }

    /** Split out from the network call so it can be exercised on the JVM. */
    fun parseSearchResponse(jsonResponse: String?): List<LocationSuggestion> {
        if (jsonResponse.isNullOrBlank()) return emptyList()
        val results = JSONArray(jsonResponse)
        val suggestions = mutableListOf<LocationSuggestion>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val displayName = item.optString("display_name", "").takeIf { it.isNotBlank() } ?: continue
            val lat = item.optString("lat").toDoubleOrNull() ?: continue
            val lon = item.optString("lon").toDoubleOrNull() ?: continue
            val (primary, secondary) = splitPlaceName(displayName, item.optString("name"))
            suggestions.add(LocationSuggestion(displayName, primary, secondary, lat, lon))
        }
        return suggestions
    }

    /**
     * Nominatim's `display_name` is one long comma-separated chain — house number,
     * street, village, district, county, region, postcode, country. Shown whole on
     * a single ellipsised line it reads as noise, so it is split into the place and
     * where that place is, and the tail (postcode, country) is dropped: a rider
     * searching from Derbyshire does not need "United Kingdom" spelled out.
     */
    fun splitPlaceName(displayName: String, name: String? = null): Pair<String, String> {
        val parts = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return displayName to ""

        val primary = name?.takeIf { it.isNotBlank() } ?: parts.first()
        val rest = if (parts.firstOrNull().equals(primary, ignoreCase = true)) parts.drop(1) else parts
        val secondary = rest
            // Postcodes carry no meaning at a glance and eat the whole line.
            .filterNot { it.any(Char::isDigit) && it.none(Char::isLetter) }
            .filterNot { it.matches(Regex("[A-Z]{1,2}\\d[\\dA-Z]?\\s*\\d?[A-Z]{0,2}")) }
            .take(3)
            .joinToString(", ")
        return primary to secondary
    }

    fun geocodeLocation(locationName: String, callback: GeocodingCallback?) {
        val mainHandler = try {
            android.os.Handler(android.os.Looper.getMainLooper())
        } catch (e: Exception) {
            null
        }

        executor.execute {
            try {
                throttleNominatim()
                val encoded = URLEncoder.encode(locationName, StandardCharsets.UTF_8.name())
                val urlObj = URL("${ApiConfig.NOMINATIM_BASE_URL}/search?q=$encoded&format=json&limit=1")

                val conn = urlObj.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", NOMINATIM_USER_AGENT)

                val response = try {
                    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                        reader.readLines().joinToString("\n")
                    }
                } finally {
                    conn.disconnect()
                }

                val parsed = parseGeocodingResponse(response)
                if (parsed != null) {
                    if (mainHandler != null) mainHandler.post { callback?.onResult(parsed) }
                    else callback?.onResult(parsed)
                } else {
                    if (mainHandler != null) mainHandler.post { callback?.onError("Location not found: $locationName") }
                    else callback?.onError("Location not found: $locationName")
                }
            } catch (e: Exception) {
                android.util.Log.w("RouteUtils", "Geocoding failed for: $locationName", e)
                val errorMsg = "Network error: ${e.message}"
                if (mainHandler != null) mainHandler.post { callback?.onError(errorMsg) }
                else callback?.onError(errorMsg)
            }
        }
    }

    @Deprecated("Use geocodeLocation(String, GeocodingCallback) instead.", ReplaceWith("geocodeLocation(locationName, callback)"))
    fun stringToGeoPoint(locationName: String): GeoPoint? {
        android.util.Log.w("RouteUtils", "stringToGeoPoint is deprecated and blocks the UI thread. Use geocodeLocation with a callback instead.")
        return null
    }

    fun parseGeocodingResponse(jsonResponse: String?): GeoPoint? {
        if (jsonResponse.isNullOrEmpty()) return null

        return try {
            val results = JSONArray(jsonResponse)
            if (results.length() == 0) return null

            val first = results.getJSONObject(0)
            if (!first.has("lat") || !first.has("lon")) return null

            val lat = first.getDouble("lat")
            val lon = first.getDouble("lon")
            GeoPoint(lat, lon)
        } catch (e: Exception) {
            null
        }
    }

    fun parseRouteApiResponse(
        jsonResponse: String?,
        waypoints: List<Waypoint>,
        routePreference: com.motorider.models.RouteType
    ): List<com.motorider.models.Route> {
        if (jsonResponse.isNullOrEmpty()) return emptyList()

        return try {
            val root = org.json.JSONObject(jsonResponse)
            if (!root.optBoolean("success", false)) return emptyList()

            val routes = root.optJSONArray("routes") ?: return emptyList()
            val resultList = mutableListOf<com.motorider.models.Route>()

            // Response-level caveats. The API can succeed while quietly failing to
            // honour an avoidance, or while unable to reach the curvature data; both
            // are reported here and must be carried onto every route so the UI can
            // surface them.
            val avoidancesHonoured = root.optBoolean("avoidances_honoured", true)
            val curvatureAvailable = root.optBoolean("curvature_available", true)
            val serviceMessage = root.optString("message", "").takeIf { it.isNotBlank() }

            for (r in 0 until routes.length()) {
                val routeObj = routes.getJSONObject(r)
                val distance = routeObj.optDouble("distance", 0.0)
                val duration = routeObj.optDouble("duration", 0.0)
                val score = routeObj.optDouble("score", 0.0)

                val geometry = routeObj.optJSONArray("geometry") ?: continue
                if (geometry.length() == 0) continue

                val points = java.util.ArrayList<org.osmdroid.util.GeoPoint>(geometry.length())
                for (i in 0 until geometry.length()) {
                    val pair = geometry.optJSONArray(i) ?: continue
                    if (pair.length() < 2) continue
                    points.add(org.osmdroid.util.GeoPoint(pair.getDouble(1), pair.getDouble(0)))
                }
                if (points.isEmpty()) continue

                val routeName = if (r == 0) routePreference.displayName
                                else "${routePreference.displayName} (Alt ${r + 1})"
                val route = com.motorider.models.Route(routeName, waypoints.toList())
                route.routeGeometry = points
                route.distance = distance / 1000.0
                route.duration = duration / 60.0
                route.routeScore = score

                val metaObj = routeObj.optJSONObject("curvature_metadata")
                if (metaObj != null) {
                    val metadata = com.motorider.models.Route.CurvatureMetadata(
                        totalCurvature = metaObj.optDouble("total_curvature", 0.0),
                        curvaturePerKm = metaObj.optDouble("curvature_per_km", 0.0),
                        curviestSegmentWayId = metaObj.optLong("curviest_segment_way_id", 0),
                        curviestSegmentCurvature = metaObj.optDouble("curviest_segment_curvature", 0.0),
                        segmentsAnalyzed = metaObj.optInt("segments_analyzed", 0),
                        segmentsWithCurvature = metaObj.optInt("segments_with_curvature", 0)
                    )
                    route.curvatureMetadata = metadata
                    route.curvatureScore = metadata.curvaturePerKm
                }
                route.elevationGain = 0.0
                route.avoidancesHonoured = avoidancesHonoured
                route.curvatureAvailable = curvatureAvailable
                if (!avoidancesHonoured || !curvatureAvailable) {
                    route.serviceWarning = serviceMessage
                }

                resultList.add(route)
            }
            resultList
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Generate turn-by-turn navigation instructions from route geometry.
     *
     * The routing API returns no road names, so manoeuvres are derived purely from
     * how the heading changes between consecutive geometry vertices.
     *
     * Route geometry is dense — a long route has thousands of vertices, nearly all
     * of them gentle curve-following. Emitting one instruction per vertex would bury
     * the real junctions, so only heading changes above [MIN_TURN_ANGLE_DEGREES] are
     * kept, and turns closer together than [MIN_MANEUVER_SPACING_METERS] are merged
     * into the sharper of the two (a single junction is usually several vertices).
     *
     * @param durationSeconds total route duration in seconds, used to apportion the
     *   remaining time to each manoeuvre. Pass 0 if unknown.
     */
    fun generateTurnInstructions(
        geometry: List<GeoPoint>,
        waypoints: List<Waypoint>,
        durationSeconds: Double
    ): List<TurnInstruction> {
        if (geometry.size < 2) return emptyList()

        val cumulativeDistances = calculateCumulativeDistances(geometry)
        val totalDistance = cumulativeDistances.last()

        fun timeToEndFrom(distAlong: Double): Double =
            if (totalDistance > 0 && durationSeconds > 0) {
                ((totalDistance - distAlong) / totalDistance) * durationSeconds
            } else 0.0

        fun instructionAt(
            index: Int,
            type: ManeuverType,
            text: String,
            bearing: Double
        ): TurnInstruction {
            val distAlong = distanceAlongRouteAt(index, cumulativeDistances)
            return TurnInstruction(
                maneuverType = type,
                instruction = text,
                distanceToManeuver = 0.0,
                distanceAlongRoute = distAlong,
                distanceRemaining = (totalDistance - distAlong).coerceAtLeast(0.0),
                timeRemaining = timeToEndFrom(distAlong),
                bearing = bearing,
                segmentIndex = index
            )
        }

        val turns = mutableListOf<TurnInstruction>()

        for (i in 1 until geometry.size - 1) {
            val bearingIn = calculateBearing(geometry[i - 1], geometry[i])
            val bearingOut = calculateBearing(geometry[i], geometry[i + 1])

            // Signed: positive is clockwise, i.e. a right turn.
            val delta = signedBearingDelta(bearingIn, bearingOut)
            val magnitude = Math.abs(delta)
            if (magnitude < MIN_TURN_ANGLE_DEGREES) continue

            val right = delta > 0
            val (type, text) = when {
                magnitude < 45.0 ->
                    if (right) ManeuverType.TURN_SLIGHT_RIGHT to "Slight right"
                    else ManeuverType.TURN_SLIGHT_LEFT to "Slight left"
                magnitude < 135.0 ->
                    if (right) ManeuverType.TURN_RIGHT to "Turn right"
                    else ManeuverType.TURN_LEFT to "Turn left"
                magnitude < 160.0 ->
                    if (right) ManeuverType.TURN_RIGHT to "Sharp right"
                    else ManeuverType.TURN_LEFT to "Sharp left"
                else -> ManeuverType.UTURN to "U-turn"
            }

            val candidate = instructionAt(i, type, text, bearingOut)

            // Merge with the previous manoeuvre when they are close enough to be the
            // same junction, keeping whichever turn is sharper.
            val previous = turns.lastOrNull()
            if (previous != null &&
                candidate.distanceAlongRoute - previous.distanceAlongRoute < MIN_MANEUVER_SPACING_METERS
            ) {
                val previousMagnitude = maneuverMagnitude(previous.maneuverType)
                if (maneuverMagnitude(type) > previousMagnitude) {
                    turns[turns.lastIndex] = candidate
                }
                continue
            }

            turns.add(candidate)
        }

        val instructions = mutableListOf<TurnInstruction>()

        instructions.add(
            instructionAt(0, ManeuverType.DEPART, "Depart", calculateBearing(geometry[0], geometry[1]))
        )
        instructions.addAll(turns)

        // Intermediate waypoints: announce arrival at each one.
        if (waypoints.size > 2) {
            for (wpIdx in 1 until waypoints.size - 1) {
                val wp = waypoints[wpIdx]
                var closestIndex = 0
                var closestDist = Double.MAX_VALUE
                for (i in geometry.indices) {
                    val d = wp.location.distanceToMeters(geometry[i])
                    if (d < closestDist) {
                        closestDist = d
                        closestIndex = i
                    }
                }

                if (closestIndex <= 0 || closestIndex >= geometry.size - 1) continue

                val waypointDistAlong = distanceAlongRouteAt(closestIndex, cumulativeDistances)
                // Drop any turn that coincides with the waypoint — arriving somewhere
                // is the more useful thing to say.
                instructions.removeAll {
                    it.maneuverType != ManeuverType.DEPART &&
                        Math.abs(it.distanceAlongRoute - waypointDistAlong) < MIN_MANEUVER_SPACING_METERS
                }
                instructions.add(
                    instructionAt(
                        closestIndex,
                        ManeuverType.WAYPOINT_ARRIVED,
                        "Arrive at ${wp.name}",
                        calculateBearing(geometry[closestIndex], geometry[closestIndex + 1])
                    )
                )
            }
        }

        val lastIndex = geometry.size - 1
        instructions.add(
            instructionAt(
                lastIndex,
                ManeuverType.ARRIVE,
                "Arrive at ${waypoints.lastOrNull()?.name ?: "destination"}",
                calculateBearing(geometry[lastIndex - 1], geometry[lastIndex])
            )
        )

        instructions.sortBy { it.distanceAlongRoute }
        return instructions
    }

    /** Heading changes gentler than this are curve-following, not a manoeuvre. */
    private const val MIN_TURN_ANGLE_DEGREES = 20.0

    /** Manoeuvres closer together than this are treated as one junction. */
    private const val MIN_MANEUVER_SPACING_METERS = 25.0

    /** Rough ordering of how significant a manoeuvre is, for merge decisions. */
    private fun maneuverMagnitude(type: ManeuverType): Int = when (type) {
        ManeuverType.UTURN -> 3
        ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT -> 2
        ManeuverType.TURN_SLIGHT_LEFT, ManeuverType.TURN_SLIGHT_RIGHT -> 1
        else -> 0
    }
}
