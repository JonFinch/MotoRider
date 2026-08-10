package com.motorider.utils

import org.osmdroid.util.GeoPoint

private const val EARTH_RADIUS = 6371000.0

/** Metres per degree of latitude (mean). Good to ~0.5% anywhere on Earth. */
private const val METERS_PER_DEGREE = 111319.49

/** Calculate distance between two GeoPoints in meters using Haversine formula */
fun GeoPoint.distanceToMeters(other: GeoPoint): Double {
    val lat1 = Math.toRadians(this.latitude)
    val lat2 = Math.toRadians(other.latitude)
    val dLat = Math.toRadians(other.latitude - this.latitude)
    val dLon = Math.toRadians(other.longitude - this.longitude)

    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1) * Math.cos(lat2) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)))
    return EARTH_RADIUS * c
}

/** Result of nearest point calculation */
data class NearestPointResult(
    val point: GeoPoint,
    val distance: Double,
    val segmentIndex: Int,
    val distanceAlongRoute: Double
)

/**
 * Local flat-Earth projection anchored at [lat0].
 *
 * Over the distances navigation cares about (a route segment is metres to a few
 * hundred metres, and a GPS fix is within tens of metres of the route) the
 * curvature of the Earth is irrelevant, so projecting to a local metric plane
 * lets us use ordinary 2D vector maths — which is both exact for this purpose
 * and far cheaper than spherical trigonometry at 1 Hz.
 */
private class LocalPlane(lat0: Double) {
    private val kx = METERS_PER_DEGREE * Math.cos(Math.toRadians(lat0))
    private val ky = METERS_PER_DEGREE
    private val lat0 = lat0
    private var lon0 = 0.0

    fun anchor(lon: Double) { lon0 = lon }

    fun x(p: GeoPoint): Double = (p.longitude - lon0) * kx
    fun y(p: GeoPoint): Double = (p.latitude - lat0) * ky
}

/**
 * Calculate the perpendicular (cross-track) distance from [point] to the line
 * through [a] and [b], in metres.
 *
 * Note this is the distance to the *infinite* line, not the segment — use
 * [nearestPointOnPolyline] when the segment endpoints matter.
 */
fun perpendicularDistance(point: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
    val plane = LocalPlane(point.latitude).apply { anchor(point.longitude) }

    val ax = plane.x(a); val ay = plane.y(a)
    val bx = plane.x(b); val by = plane.y(b)
    val px = plane.x(point); val py = plane.y(point)

    val dx = bx - ax
    val dy = by - ay
    val segLenSq = dx * dx + dy * dy

    // Degenerate segment — fall back to the point distance.
    if (segLenSq < 1e-9) return a.distanceToMeters(point)

    // |cross product| / |segment| is the perpendicular distance.
    val cross = dx * (py - ay) - dy * (px - ax)
    return Math.abs(cross) / Math.sqrt(segLenSq)
}

/**
 * Project a point onto a line segment, clamping to the nearest endpoint when the
 * perpendicular foot falls outside the segment.
 */
fun projectPointOntoSegment(point: GeoPoint, a: GeoPoint, b: GeoPoint): GeoPoint {
    val t = segmentProjectionFactor(point, a, b)
    return GeoPoint(
        a.latitude + t * (b.latitude - a.latitude),
        a.longitude + t * (b.longitude - a.longitude)
    )
}

/**
 * How far along the segment a→b the perpendicular foot of [point] lies, as a
 * fraction clamped to [0, 1].
 */
fun segmentProjectionFactor(point: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
    val plane = LocalPlane(point.latitude).apply { anchor(point.longitude) }

    val ax = plane.x(a); val ay = plane.y(a)
    val bx = plane.x(b); val by = plane.y(b)
    val px = plane.x(point); val py = plane.y(point)

    val dx = bx - ax
    val dy = by - ay
    val segLenSq = dx * dx + dy * dy
    if (segLenSq < 1e-9) return 0.0

    val t = ((px - ax) * dx + (py - ay) * dy) / segLenSq
    return t.coerceIn(0.0, 1.0)
}

/**
 * Find the nearest point on a polyline to a given point.
 *
 * [cumulativeDistances] may be supplied to avoid recomputing the whole route's
 * distance table on every GPS fix; it must come from [calculateCumulativeDistances]
 * for the same geometry.
 *
 * [searchFrom]/[searchTo] restrict the search to a window of segment indices. A
 * window matters on routes that pass close to themselves — round trips especially,
 * where the outbound and return legs can be metres apart and an unconstrained
 * search would snap the rider onto the wrong leg and report the ride as finished.
 */
fun nearestPointOnPolyline(
    point: GeoPoint,
    geometry: List<GeoPoint>,
    cumulativeDistances: DoubleArray = calculateCumulativeDistances(geometry),
    searchFrom: Int = 0,
    searchTo: Int = geometry.size - 2
): NearestPointResult {
    if (geometry.isEmpty()) {
        return NearestPointResult(GeoPoint(0.0, 0.0), 0.0, 0, 0.0)
    }
    if (geometry.size < 2) {
        val only = geometry[0]
        return NearestPointResult(only, point.distanceToMeters(only), 0, 0.0)
    }

    val first = searchFrom.coerceIn(0, geometry.size - 2)
    val last = searchTo.coerceIn(first, geometry.size - 2)

    var minDistance = Double.MAX_VALUE
    var nearestPoint = geometry[first]
    var nearestSegmentIndex = first
    var nearestDistanceAlong = cumulativeDistances[first]

    for (i in first..last) {
        val a = geometry[i]
        val b = geometry[i + 1]
        val t = segmentProjectionFactor(point, a, b)
        val proj = GeoPoint(
            a.latitude + t * (b.latitude - a.latitude),
            a.longitude + t * (b.longitude - a.longitude)
        )
        val dist = point.distanceToMeters(proj)

        if (dist < minDistance) {
            minDistance = dist
            nearestPoint = proj
            nearestSegmentIndex = i
            nearestDistanceAlong = cumulativeDistances[i] +
                t * (cumulativeDistances[i + 1] - cumulativeDistances[i])
        }
    }

    return NearestPointResult(
        point = nearestPoint,
        distance = minDistance,
        segmentIndex = nearestSegmentIndex,
        distanceAlongRoute = nearestDistanceAlong
    )
}

/**
 * Calculate the bearing from one point to another, in degrees [0, 360).
 *
 * Bearing is measured clockwise from true north.
 */
fun calculateBearing(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lon1 = Math.toRadians(from.longitude)
    val lat2 = Math.toRadians(to.latitude)
    val lon2 = Math.toRadians(to.longitude)

    val dLon = lon2 - lon1

    val y = Math.sin(dLon) * Math.cos(lat2)
    val x = Math.cos(lat1) * Math.sin(lat2) -
        Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)

    var bearing = Math.toDegrees(Math.atan2(y, x))
    bearing = (bearing + 360.0) % 360.0

    return bearing
}

/**
 * Signed change in heading from [fromBearing] to [toBearing], in degrees (-180, 180].
 *
 * Positive means turning clockwise (to the right), negative anticlockwise (left).
 * Handles the 0°/360° wrap, which a plain subtraction does not: a turn from 350°
 * to 10° is a 20° right, not a 340° left.
 */
fun signedBearingDelta(fromBearing: Double, toBearing: Double): Double {
    var delta = (toBearing - fromBearing) % 360.0
    if (delta > 180.0) delta -= 360.0
    if (delta <= -180.0) delta += 360.0
    return delta
}

/**
 * Calculate distance between two points in meters using the Haversine formula.
 */
fun calculateDistance(a: GeoPoint, b: GeoPoint): Double {
    return a.distanceToMeters(b)
}

/**
 * Calculate cumulative distance along a polyline, returning an array where
 * element [i] is the total distance from the start of the route to point [i].
 */
fun calculateCumulativeDistances(geometry: List<GeoPoint>): DoubleArray {
    if (geometry.size < 2) return DoubleArray(1)

    val distances = DoubleArray(geometry.size)
    distances[0] = 0.0

    for (i in 1 until geometry.size) {
        distances[i] = distances[i - 1] + geometry[i - 1].distanceToMeters(geometry[i])
    }

    return distances
}

/**
 * Distance from the start of the route to the vertex at [pointIndex].
 */
fun distanceAlongRouteAt(pointIndex: Int, cumulativeDistances: DoubleArray): Double {
    if (cumulativeDistances.isEmpty()) return 0.0
    return cumulativeDistances[pointIndex.coerceIn(0, cumulativeDistances.size - 1)]
}

/**
 * Calculate total route distance in meters.
 */
fun totalRouteDistance(geometry: List<GeoPoint>): Double {
    if (geometry.size < 2) return 0.0
    return calculateCumulativeDistances(geometry).last()
}

/**
 * Exponential moving average filter for GPS distance readings.
 *
 * Smooths noisy GPS distance measurements to reduce false off-route detections.
 *
 * @param alpha Smoothing factor (0.0-1.0). Higher values react faster to changes.
 */
class DistanceEMAFilter(private val alpha: Float = 0.3f) {
    private var smoothedValue = 0.0f
    private var initialized = false

    /**
     * Update the filter with a new distance value and return the smoothed result.
     */
    fun update(value: Float): Float {
        if (!initialized) {
            smoothedValue = value
            initialized = true
            return value
        }
        smoothedValue = alpha * value + (1.0f - alpha) * smoothedValue
        return smoothedValue
    }

    /** Reset the filter to its uninitialised state. */
    fun reset() {
        smoothedValue = 0.0f
        initialized = false
    }
}

/**
 * Off-route detector.
 *
 * Declaring off-route triggers a recalculation, so a false positive costs the rider
 * a wrong turn's worth of confusion. Two independent conditions must therefore hold:
 * [confirmationCount] consecutive *raw* readings beyond [thresholdMeters], and a
 * smoothed distance beyond it too.
 *
 * The raw run is what rejects spikes: a lone bad fix is followed by a good one,
 * which resets the run. (The smoothed value alone cannot do this — an EMA carries a
 * large spike above the threshold for several samples afterwards, so a single
 * outlier would still confirm.) The smoothed value adds hysteresis, keeping a rider
 * hovering either side of the threshold from flapping.
 *
 * @param thresholdMeters Distance threshold in meters (default 50 m).
 * @param confirmationCount Number of consecutive off-route readings to confirm (default 3).
 */
class OffRouteDetector(private val thresholdMeters: Float = 50f, private val confirmationCount: Int = 3) {
    private val distanceFilter = DistanceEMAFilter(0.3f)
    private var consecutiveOffRoute = 0

    /**
     * Update the detector with a new perpendicular distance reading.
     *
     * @param distance Current perpendicular distance from the route in meters.
     * @return `true` if the device is considered off-route.
     */
    fun update(distance: Float): Boolean {
        val smoothed = distanceFilter.update(distance)

        if (distance <= thresholdMeters) {
            consecutiveOffRoute = 0
            return false
        }

        consecutiveOffRoute++
        return consecutiveOffRoute >= confirmationCount && smoothed > thresholdMeters
    }

    /** Reset the detector to its initial state. */
    fun reset() {
        distanceFilter.reset()
        consecutiveOffRoute = 0
    }
}


/**
 * Movement needed before a bearing derived from two fixes can be trusted.
 *
 * A stationary GPS wanders several metres between fixes, and deriving a heading
 * from that noise spins the map and the rider's marker while they sit at lights.
 * At any riding speed consecutive fixes are far further apart than this.
 */
const val MIN_BEARING_DERIVATION_METERS = 8.0

/**
 * Speed below which a reported bearing is not worth believing.
 *
 * A GPS that is not moving still fills the bearing field, with whatever the last
 * heading was or simply zero. Taking it at face value points the rider due north
 * while they sit at a junction.
 */
const val MIN_TRUSTED_BEARING_SPEED_MPS = 1.0f

/**
 * The direction of travel to use for a fix, or null when there is nothing to go on.
 *
 * Preference order: the reported bearing when the fix is moving fast enough for it
 * to mean anything, then what the movement since the last fix implies, then
 * whatever was last settled on.
 *
 * The last of those matters as much as the first. This used to substitute 0f
 * whenever the GPS withheld a bearing, which is not "unknown" but "due north": the
 * map swung to north-up and the rider's marker span round to face it every time
 * they slowed. Holding the previous heading is both truthful and stable.
 *
 * The speed gate matters because a fix can carry a bearing field that means
 * nothing — the Android emulator reports a course of exactly 0 for every synthetic
 * fix, and a real receiver does much the same at a standstill. Deriving from two
 * positions is the more reliable signal whenever the fix itself is not moving.
 */
fun bearingForFix(
    reported: Float?,
    speedMps: Float,
    previous: GeoPoint?,
    current: GeoPoint,
    lastKnown: Float?
): Float? {
    if (reported != null && speedMps >= MIN_TRUSTED_BEARING_SPEED_MPS) return reported
    if (previous != null && previous.distanceToMeters(current) >= MIN_BEARING_DERIVATION_METERS) {
        return calculateBearing(previous, current).toFloat()
    }
    return reported ?: lastKnown
}
