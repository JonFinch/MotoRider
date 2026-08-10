package com.motorider.ui.screen

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.motorider.R
import com.motorider.models.Avoidance
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import com.motorider.services.RouteService
import com.motorider.ui.component.RouteStop
import com.motorider.utils.RouteUtils
import org.osmdroid.util.GeoPoint

/** How a planning attempt ended. Every failure names what the rider can do about it. */
sealed interface PlanOutcome {
    data class Success(val routes: List<Route>) : PlanOutcome

    /** An address the rider typed could not be resolved. [label] is that address. */
    data class GeocodeFailed(val label: String) : PlanOutcome

    /** The routing service answered, but with a failure. */
    data class RoutingFailed(val message: String) : PlanOutcome

    /** A stop is set to "current location" but there is no fix yet. */
    data object NoFix : PlanOutcome
}

private fun onMain(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}

/**
 * Turn a [PlanDraft] into routes.
 *
 * Resolution is deliberately all-or-nothing. The previous implementation, when
 * Nominatim could not find an address, quietly substituted a point a couple of
 * kilometres from the rider and routed to *that* — so a typo in "Hartington"
 * produced a confident-looking route to a field. Anything unresolvable now stops
 * the attempt and comes back as [PlanOutcome.GeocodeFailed] naming the offending
 * entry.
 */
fun planRoute(
    context: Context,
    routeService: RouteService,
    draft: PlanDraft,
    legPrefs: List<RouteType>,
    avoidances: Set<Avoidance>,
    currentLocation: GeoPoint?,
    onResult: (PlanOutcome) -> Unit
) {
    val stops = draft.ordered
    resolveStops(context, stops, currentLocation, onFailure = { onMain { onResult(it) } }) { waypoints ->
        val styles = legPrefs.take(waypoints.size - 1).ifEmpty { listOf(RouteType.DIRECT) }
        if (styles.distinct().size <= 1) {
            // One style for the whole trip: a single request, which is also the only
            // shape that can return ranked alternatives.
            routeService.calculateRouteAsync(
                waypoints.first(),
                waypoints.last(),
                waypoints.subList(1, waypoints.lastIndex.coerceAtLeast(1)),
                styles.first(),
                avoidances,
                object : RouteService.RouteCalculationCallback {
                    override fun onRouteCalculated(routes: List<Route>) = onMain {
                        if (routes.isEmpty()) {
                            onResult(PlanOutcome.RoutingFailed(context.getString(R.string.route_failed_no_route)))
                        } else {
                            onResult(PlanOutcome.Success(routes))
                        }
                    }

                    override fun onError(error: String) = onMain {
                        onResult(PlanOutcome.RoutingFailed(error))
                    }
                }
            )
        } else {
            routeLegsSeparately(context, routeService, waypoints, styles, avoidances, onResult)
        }
    }
}

/**
 * Resolve every stop to a coordinate, in order, stopping at the first failure.
 *
 * Stops picked from the search list already carry their coordinates, so this
 * usually makes no network calls at all. Sequential rather than parallel for the
 * ones that do: Nominatim allows one request a second, and a burst of four is the
 * quickest way to get an app blocked.
 */
private fun resolveStops(
    context: Context,
    stops: List<RouteStop>,
    currentLocation: GeoPoint?,
    onFailure: (PlanOutcome) -> Unit = {},
    onResolved: (List<Waypoint>) -> Unit = {}
) {
    val resolved = arrayOfNulls<Waypoint>(stops.size)

    fun step(index: Int) {
        if (index == stops.size) {
            onResolved(resolved.filterNotNull())
            return
        }
        val stop = stops[index]
        val name = stop.label.ifBlank { context.getString(R.string.waypoint_via) }

        // "Current location" tracks the rider rather than the fix that was showing
        // when they picked it — planning from where they are now is the only reading
        // of that choice that is not stale by the time they press Find route.
        if (stop.isCurrentLocation) {
            val fix = currentLocation ?: return onFailure(PlanOutcome.NoFix)
            resolved[index] = Waypoint(name, fix)
            return step(index + 1)
        }

        val point = stop.point
        if (point != null) {
            resolved[index] = Waypoint(name, point)
            return step(index + 1)
        }

        RouteUtils.geocodeLocation(stop.label, object : RouteUtils.GeocodingCallback {
            override fun onResult(geoPoint: GeoPoint) {
                resolved[index] = Waypoint(name, geoPoint)
                step(index + 1)
            }

            override fun onError(error: String) {
                onFailure(PlanOutcome.GeocodeFailed(stop.label))
            }
        })
    }

    step(0)
}

/**
 * Route each leg with its own ride style and stitch the results into one route.
 *
 * The routing API takes a single curviness per request, so the per-leg styles the
 * planning sheet offers only mean anything if each leg is asked for separately.
 * Before this, every leg silently inherited the first leg's style — the same class
 * of quiet betrayal as an ignored avoidance.
 *
 * Alternatives are dropped here on purpose: the alternatives of leg 1 and leg 2 are
 * not comparable as whole-trip options, and inventing a cross product of them would
 * be presenting arithmetic as a ranking.
 */
private fun routeLegsSeparately(
    context: Context,
    routeService: RouteService,
    waypoints: List<Waypoint>,
    styles: List<RouteType>,
    avoidances: Set<Avoidance>,
    onResult: (PlanOutcome) -> Unit
) {
    Thread {
        try {
            val legs = mutableListOf<Route>()
            for (i in 0 until waypoints.size - 1) {
                val style = styles.getOrElse(i) { styles.last() }
                val legRoutes = routeService.calculateRoute(
                    waypoints[i], waypoints[i + 1], emptyList(), style, avoidances
                )
                val leg = legRoutes.firstOrNull()
                    ?: return@Thread onMain {
                        onResult(PlanOutcome.RoutingFailed(context.getString(R.string.route_failed_no_route)))
                    }
                legs.add(leg)
            }
            val combined = stitchLegs(legs, waypoints)
            onMain { onResult(PlanOutcome.Success(listOf(combined))) }
        } catch (e: Exception) {
            onMain {
                onResult(PlanOutcome.RoutingFailed(e.message ?: context.getString(R.string.route_failed_no_route)))
            }
        }
    }.start()
}

/** Joins consecutive legs end-to-end. Visible for testing. */
internal fun stitchLegs(legs: List<Route>, waypoints: List<Waypoint>): Route {
    val combined = Route(legs.first().name, waypoints)
    val geometry = mutableListOf<GeoPoint>()

    legs.forEachIndexed { index, leg ->
        val points = leg.routeGeometry.orEmpty()
        // Each leg starts where the previous ended; keeping both copies of the join
        // would put a zero-length segment in the geometry, which reads as a 0° turn.
        val toAdd = if (index == 0) points else points.drop(1)
        geometry.addAll(toAdd)
    }

    combined.routeGeometry = geometry
    combined.distance = legs.sumOf { it.distance }
    combined.duration = legs.sumOf { it.duration }
    // Curves per km is a rate, so it averages by distance, not by leg count: a 5 km
    // hairpin leg must not drag the figure for a 200 km trip.
    combined.curvatureScore = if (combined.distance > 0) {
        legs.sumOf { it.curvatureScore * it.distance } / combined.distance
    } else 0.0
    combined.routeScore = legs.map { it.routeScore }.average()
    // A caveat on any leg is a caveat on the whole ride.
    combined.isEstimate = legs.any { it.isEstimate }
    combined.estimateReason = legs.firstNotNullOfOrNull { it.estimateReason }
    combined.avoidancesHonoured = legs.all { it.avoidancesHonoured }
    combined.curvatureAvailable = legs.all { it.curvatureAvailable }
    combined.serviceWarning = legs.firstNotNullOfOrNull { it.serviceWarning }
    combined.turnInstructions = RouteUtils.generateTurnInstructions(
        geometry, waypoints, combined.duration * 60.0
    )
    return combined
}

/**
 * Ask for a loop of roughly [targetDistanceKm] setting off on [directionDeg].
 *
 * This used to build the loop itself: three via points on a circle of
 * targetDistance/7 around the rider, at fixed angles either side of the heading.
 * Those are arbitrary coordinates, so the router snapped each to the nearest road
 * — often a dead-end lane — and the rider got a spur to ride up and back. Measured
 * over five varied requests that produced a mean worst out-and-back of 4.2 km.
 * The service now generates the loop and chooses between candidates; the same
 * measurement came to 0.45 km.
 */
fun generateRoundTrip(
    context: Context,
    routeService: RouteService,
    center: GeoPoint,
    targetDistanceKm: Double,
    directionDeg: Int,
    preference: RouteType,
    avoidances: Set<Avoidance>,
    onResult: (PlanOutcome) -> Unit
) {
    routeService.calculateRoundTripAsync(
        start = Waypoint(context.getString(R.string.waypoint_start), center),
        distanceMeters = targetDistanceKm * 1000.0,
        headingDegrees = directionDeg.toDouble(),
        routePreference = preference,
        avoidances = avoidances,
        callback = object : RouteService.RouteCalculationCallback {
            override fun onRouteCalculated(routes: List<Route>) = onMain {
                if (routes.isEmpty()) {
                    onResult(PlanOutcome.RoutingFailed(context.getString(R.string.route_failed_no_route)))
                } else {
                    onResult(PlanOutcome.Success(routes))
                }
            }

            override fun onError(error: String) = onMain {
                onResult(PlanOutcome.RoutingFailed(error))
            }
        }
    )
}
