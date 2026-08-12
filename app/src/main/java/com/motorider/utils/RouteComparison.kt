package com.motorider.utils

import com.motorider.models.Route

/** What makes one alternative the pick of the set. */
enum class RouteSuperlative { FASTEST, SHORTEST, CURVIEST }

/**
 * Work out which alternative, if any, is the notable one on each axis.
 *
 * Three rules, and each of them exists to stop the labels being noise:
 *
 * 1. **A win must be outright.** If two routes are within [TIE_TOLERANCE] of each
 *    other on an axis, neither is labelled — telling a rider one route is "fastest"
 *    when it saves nine seconds over the next is worse than saying nothing, because
 *    they will believe it and choose on it.
 * 2. **One label per route.** A route that is both shortest and fastest gets the
 *    higher-priority label only. Two badges on one chip implies a distinction
 *    between them that is not there.
 * 3. **Curviest ranks first.** This is an app for choosing roads by how they ride,
 *    so when a route genuinely is the twistiest of the set that is the most
 *    decision-shaped thing to say about it.
 *
 * Curvature is only considered where the routing service actually supplied it — a
 * hardcoded zero would otherwise make every route in a degraded set tie for
 * curviest, or worse, hand the label to whichever one happened to be listed first.
 */
fun routeSuperlatives(routes: List<Route>): Map<Int, RouteSuperlative> {
    if (routes.size < 2) return emptyMap()

    val assigned = mutableMapOf<Int, RouteSuperlative>()
    val claimed = mutableSetOf<RouteSuperlative>()

    fun award(superlative: RouteSuperlative, winner: Int?) {
        if (winner == null || superlative in claimed || winner in assigned) return
        assigned[winner] = superlative
        claimed.add(superlative)
    }

    val curvatureUsable = routes.all { it.curvatureAvailable && !it.isEstimate }
    if (curvatureUsable) {
        award(
            RouteSuperlative.CURVIEST,
            outrightWinner(
                routes.map { it.curvatureScore },
                highest = true,
                tolerance = CURVATURE_TIE_TOLERANCE,
                // Zero curves per km is a real measurement — a dead-straight fen
                // road — not a missing value, and the unavailable case is already
                // excluded above. Discarding it would drop the comparison to a
                // single usable figure and lose the badge on a set where one route
                // genuinely is the twisty one.
                requirePositive = false
            )
        )
    }
    award(RouteSuperlative.FASTEST, outrightWinner(routes.map { it.duration }, highest = false))
    award(RouteSuperlative.SHORTEST, outrightWinner(routes.map { it.distance }, highest = false))

    return assigned
}

/**
 * Relative margin an alternative must beat the runner-up by to count as a winner.
 *
 * 2% of the runner-up's value: on a 90-minute ride that is nearly two minutes, and
 * on a 20-minute one it is under half a minute. A fixed threshold cannot serve both
 * — 2 minutes is decisive on the short ride and noise on the long one.
 */
private const val TIE_TOLERANCE = 0.02

/**
 * Curvature needs a far wider margin than time or distance before a difference is
 * worth a badge.
 *
 * Curves-per-km is a summary of a whole route, and it is coarse: 10.2 against 10.5
 * clears the 2% bar on the arithmetic while describing two roads that ride
 * identically. A rider picking the chip marked "Curviest" is expecting a noticeably
 * twistier road, so the label has to mean one — 10% is about where the difference
 * starts being something you feel through the bars rather than something visible
 * only in the number.
 */
private const val CURVATURE_TIE_TOLERANCE = 0.10

/**
 * The index of the single best value, or null when nothing wins outright.
 *
 * Null covers three cases that all mean "do not label this": no usable values, a
 * dead heat, and a margin inside [tolerance].
 */
private fun outrightWinner(
    values: List<Double>,
    highest: Boolean,
    tolerance: Double = TIE_TOLERANCE,
    /** False where zero is a real reading rather than a missing one. */
    requirePositive: Boolean = true
): Int? {
    val usable = values.withIndex().filter {
        it.value.isFinite() && (it.value > 0.0 || (!requirePositive && it.value >= 0.0))
    }
    if (usable.size < 2) return null

    val sorted = if (highest) usable.sortedByDescending { it.value } else usable.sortedBy { it.value }
    val best = sorted[0]
    val runnerUp = sorted[1]
    // A zero runner-up makes the relative margin meaningless (and infinite), so
    // fall back to asking whether the best is a real reading at all.
    if (runnerUp.value == 0.0) return if (best.value > 0.0) best.index else null

    val margin = kotlin.math.abs(best.value - runnerUp.value) / runnerUp.value
    return if (margin >= tolerance) best.index else null
}
