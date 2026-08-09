package com.motorider.navigation

/**
 * Decides where the navigation map camera should point.
 *
 * Pure logic, deliberately free of osmdroid and Android types so the behaviour a
 * rider actually feels — how fast the zoom reacts, how steady the heading is, how
 * long a pinch is respected — can be tested without a device.
 */
class NavigationCamera(private val config: CameraConfig = CameraConfig()) {

    data class CameraConfig(
        /** Speed/zoom pairs, interpolated between. Slower riding gets a closer map. */
        val zoomByMph: List<Pair<Float, Double>> = listOf(
            0f to 17.5,
            20f to 17.0,
            40f to 16.25,
            60f to 15.75,
            80f to 15.25
        ),
        /** How long a manual pinch keeps control of the zoom. */
        val userZoomHoldMs: Long = 10_000,
        /**
         * Below this speed GPS bearing is mostly noise — a stationary phone reports
         * wildly swinging headings — so the last good heading is held instead.
         */
        val minBearingSpeedMps: Float = 2.0f,
        /** Easing applied to zoom changes; lower is smoother and slower to react. */
        val zoomSmoothing: Double = 0.25,
        /** Easing applied to heading changes. */
        val bearingSmoothing: Float = 0.35f,
        /** Zoom differences smaller than this are not worth issuing to the map. */
        val zoomEpsilon: Double = 0.05
    )

    /**
     * @param zoom the zoom to apply, or null when the rider's own pinch is still in
     *   force and the camera should leave the zoom alone.
     * @param bearing smoothed heading in degrees, clockwise from north.
     */
    data class Target(val zoom: Double?, val bearing: Float)

    private var smoothedZoom: Double? = null
    private var smoothedBearing: Float? = null
    private var lastAppliedZoom: Double? = null
    private var userZoomUntilMs: Long = 0

    /** Call whenever the rider pinches, to hand them the zoom for a while. */
    fun onUserZoomGesture(nowMs: Long) {
        userZoomUntilMs = nowMs + config.userZoomHoldMs
    }

    /** True while a recent pinch still owns the zoom. */
    fun isUserControllingZoom(nowMs: Long): Boolean = nowMs < userZoomUntilMs

    /** Forget all easing state, e.g. when navigation restarts. */
    fun reset() {
        smoothedZoom = null
        smoothedBearing = null
        lastAppliedZoom = null
        userZoomUntilMs = 0
    }

    fun update(speedMps: Float, rawBearing: Float, nowMs: Long): Target {
        val bearing = smoothBearing(speedMps, rawBearing)

        if (isUserControllingZoom(nowMs)) {
            // Keep easing state in step with the speed while the rider holds the
            // zoom, so handing control back does not jump the map.
            smoothedZoom = easedZoom(speedMps)
            return Target(zoom = null, bearing = bearing)
        }

        val zoom = easedZoom(speedMps)
        val last = lastAppliedZoom
        if (last != null && Math.abs(zoom - last) < config.zoomEpsilon) {
            return Target(zoom = null, bearing = bearing)
        }
        lastAppliedZoom = zoom
        return Target(zoom = zoom, bearing = bearing)
    }

    /** The zoom this speed maps to, before easing. Exposed for tests and tuning. */
    fun targetZoomForSpeed(speedMps: Float): Double {
        val mph = speedMps / MPH_IN_MPS
        val table = config.zoomByMph
        if (table.isEmpty()) return DEFAULT_ZOOM

        val first = table.first()
        if (mph <= first.first) return first.second
        val last = table.last()
        if (mph >= last.first) return last.second

        for (i in 0 until table.size - 1) {
            val (lowMph, lowZoom) = table[i]
            val (highMph, highZoom) = table[i + 1]
            if (mph in lowMph..highMph) {
                val span = highMph - lowMph
                val t = if (span <= 0f) 0.0 else ((mph - lowMph) / span).toDouble()
                return lowZoom + t * (highZoom - lowZoom)
            }
        }
        return last.second
    }

    private fun easedZoom(speedMps: Float): Double {
        val target = targetZoomForSpeed(speedMps)
        val current = smoothedZoom
        val eased = if (current == null) {
            target
        } else {
            current + config.zoomSmoothing * (target - current)
        }
        smoothedZoom = eased
        return eased
    }

    /**
     * Smooth the heading through its sine and cosine rather than the angle itself.
     * Averaging degrees directly would swing the map right round the compass every
     * time a rider crosses north, as 359° and 1° average to 180°.
     */
    private fun smoothBearing(speedMps: Float, rawBearing: Float): Float {
        val previous = smoothedBearing

        if (speedMps < config.minBearingSpeedMps) {
            return previous ?: rawBearing.normaliseDegrees()
        }

        val target = rawBearing.normaliseDegrees()
        if (previous == null) {
            smoothedBearing = target
            return target
        }

        val a = config.bearingSmoothing
        val prevRad = Math.toRadians(previous.toDouble())
        val targetRad = Math.toRadians(target.toDouble())
        val sin = (1 - a) * Math.sin(prevRad) + a * Math.sin(targetRad)
        val cos = (1 - a) * Math.cos(prevRad) + a * Math.cos(targetRad)
        val result = Math.toDegrees(Math.atan2(sin, cos)).toFloat().normaliseDegrees()

        smoothedBearing = result
        return result
    }

    private companion object {
        const val MPH_IN_MPS = 0.44704f
        const val DEFAULT_ZOOM = 16.0
    }
}

private fun Float.normaliseDegrees(): Float {
    val wrapped = this % 360f
    return if (wrapped < 0f) wrapped + 360f else wrapped
}
