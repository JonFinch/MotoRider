package com.motorider.services

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.motorider.config.ApiConfig
import com.motorider.models.PoiCategory
import com.motorider.models.PoiResult
import com.motorider.utils.PoiSearch
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * How a mid-ride POI search ended.
 *
 * The two success cases are kept apart rather than collapsed into one list with a
 * flag, because they answer different questions and the picker says different
 * things about them: [OnRoute] is "here is what is on your way", [OffRoute] is
 * "nothing is on your way for the next ten miles, but these exist".
 */
sealed interface PoiOutcome {
    data class OnRoute(val results: List<PoiResult>) : PoiOutcome

    /** Nothing within the on-route corridor, so these are near the rider instead. */
    data class OffRoute(val results: List<PoiResult>) : PoiOutcome

    /** The search worked and there is genuinely nothing out there. */
    data object None : PoiOutcome

    /** The search itself failed. Distinct from [None] — this one is worth retrying. */
    data class Failed(val message: String) : PoiOutcome
}

/**
 * Finds fuel and food near the rider, from MotoRiderMaps.
 *
 * The data is the same OSM extract that backs routing: the import already loads
 * every amenity into PostGIS, so no third-party POI service is involved and the
 * whole feature answers to a host the rider controls. See `/poi/corridor` and
 * `/poi/nearby` in MotoRiderMaps' `API_REFERENCE.md`.
 *
 * Nothing here interprets opening hours. OSM's `opening_hours` grammar is rich and
 * its coverage on rural UK forecourts is patchy and often stale, so an "open now"
 * badge would eventually send a rider on reserve to a closed pump — the one
 * failure this feature exists to prevent. The string is shown as tagged, and its
 * absence is stated rather than hidden.
 */
class PoiService {

    companion object {
        private const val TAG = "PoiService"

        /**
         * Enough for a cold PostGIS query over the corridor, short enough that a
         * rider is not left watching a spinner at 60 mph wondering if it worked.
         */
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    }

    /**
     * Find the nearest [category] on the road ahead, falling back to a search
     * around the rider when the next ten miles have none.
     *
     * @param routeGeometry the route being ridden, or empty when there is none.
     * @param segmentIndex the rider's current vertex in that geometry.
     */
    fun findNearest(
        category: PoiCategory,
        riderPosition: GeoPoint,
        routeGeometry: List<GeoPoint>,
        segmentIndex: Int,
        callback: (PoiOutcome) -> Unit
    ) {
        executor.execute {
            val outcome = try {
                search(category, riderPosition, routeGeometry, segmentIndex)
            } catch (e: Exception) {
                Log.w(TAG, "POI search failed for $category", e)
                PoiOutcome.Failed(e.message ?: "Search failed")
            }
            Handler(Looper.getMainLooper()).post { callback(outcome) }
        }
    }

    private fun search(
        category: PoiCategory,
        riderPosition: GeoPoint,
        routeGeometry: List<GeoPoint>,
        segmentIndex: Int
    ): PoiOutcome {
        val ahead = PoiSearch.routeAhead(routeGeometry, segmentIndex, riderPosition)

        // On route first. Skipped entirely when there is no route to be on, which
        // is the case for a rider who has ended navigation but still wants fuel.
        //
        // A failure here falls through to the nearby search rather than ending the
        // whole thing. The corridor query is much the heavier of the two — up to a
        // 120-vertex geography linestring against the amenity tables — so it is the
        // one that times out on a cold database or a city-wide food search. Letting
        // that abort the cheap point query would tell a rider on reserve "search
        // failed" when an answer was a fifth of a second away.
        if (ahead.size >= 2) {
            val onRoute = try {
                val corridor = PoiSearch.sampleCorridor(ahead)
                PoiSearch.rankOnRoute(
                    pois = PoiSearch.parsePoiResponse(
                        post(ApiConfig.POI_CORRIDOR_URL, PoiSearch.corridorRequest(category, corridor))
                    ),
                    category = category,
                    routeAhead = ahead,
                    riderPosition = riderPosition
                )
            } catch (e: Exception) {
                Log.w(TAG, "Corridor search failed; falling back to a search around the rider", e)
                emptyList()
            }
            if (onRoute.isNotEmpty()) return PoiOutcome.OnRoute(onRoute)
        }

        val nearby = PoiSearch.rankByDistance(
            pois = PoiSearch.parsePoiResponse(
                post(ApiConfig.POI_NEARBY_URL, PoiSearch.nearbyRequest(category, riderPosition))
            ),
            category = category,
            riderPosition = riderPosition
        )
        return if (nearby.isEmpty()) PoiOutcome.None else PoiOutcome.OffRoute(nearby)
    }

    /** POST [body] and return the response, or throw with the service's own message. */
    private fun post(url: String, body: JSONObject): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "MotoRider/1.0")
        // Omitted when no credential is configured, which is the local
        // docker-compose case - it has no proxy and answers unauthenticated.
        if (ApiConfig.mayAuthenticate(url)) {
            conn.setRequestProperty("Authorization", ApiConfig.authorizationHeader)
        }

        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // The service explains itself in `detail` — an unknown category, or
                // a 503 because the POI database is unreachable. Carrying that
                // through beats replacing it with a generic failure, since the two
                // call for different things from the rider.
                val detail = try {
                    JSONObject(
                        conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
                    ).optString("detail", "HTTP $code")
                } catch (_: Exception) {
                    "HTTP $code"
                }
                throw java.io.IOException(detail)
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
