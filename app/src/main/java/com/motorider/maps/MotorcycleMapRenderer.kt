package com.motorider.maps

import android.util.TypedValue
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.GeoPoint
import java.util.ArrayList

/** Screen-edge margin kept around a framed route so the polyline isn't flush against it. */
private const val FRAME_ROUTE_BORDER_DP = 48f

/**
 * Floor on a framed route's box span, in degrees. A route that is a single point, or
 * several coincident points (an unrouted round trip, a still-loading estimate),
 * produces a zero-size [BoundingBox]; without a floor osmdroid would zoom in to its
 * maximum zoom level for it, which reads as the map lurching to a meaningless close-up.
 */
private const val MIN_FRAME_SPAN_DEGREES = 0.003

// Padding is expressed as a fraction of the route's own span rather than a fixed
// number of pixels so it scales with the route instead of looking cramped on a long
// ride and huge on a short one. South gets extra because the planning sheet docks
// along the bottom of the screen once a route exists, covering that half of the map.
private const val HORIZONTAL_PADDING_FRACTION = 0.15
private const val NORTH_PADDING_FRACTION = 0.15
private const val SOUTH_PADDING_FRACTION = 0.55

/**
 * Bounding box to frame [points] in, padded for on-screen breathing room and to keep
 * the route clear of the planning sheet - or null if there are no points to frame.
 */
fun routeFramingBox(points: List<GeoPoint>): BoundingBox? {
    if (points.isEmpty()) return null

    val raw = BoundingBox.fromGeoPoints(points)
    val latSpan = maxOf(raw.latNorth - raw.latSouth, MIN_FRAME_SPAN_DEGREES)
    val lonSpan = maxOf(raw.lonEast - raw.lonWest, MIN_FRAME_SPAN_DEGREES)
    val centerLat = (raw.latNorth + raw.latSouth) / 2
    val centerLon = (raw.lonEast + raw.lonWest) / 2

    return BoundingBox(
        centerLat + latSpan / 2 + latSpan * NORTH_PADDING_FRACTION,
        centerLon + lonSpan / 2 + lonSpan * HORIZONTAL_PADDING_FRACTION,
        centerLat - latSpan / 2 - latSpan * SOUTH_PADDING_FRACTION,
        centerLon - lonSpan / 2 - lonSpan * HORIZONTAL_PADDING_FRACTION
    )
}

class MotorcycleMapRenderer {

    private var _currentRouteLine: Polyline? = null

    /**
     * Render a motorcycle-friendly route on the map.
     */
    fun renderMotorcycleRoute(mapView: MapView?, waypoints: List<GeoPoint>?) {
        if (mapView == null || mapView.overlays == null || waypoints.isNullOrEmpty()) return

        _currentRouteLine?.let {
            mapView.overlays.remove(it)
            _currentRouteLine = null
        }

        val routeLine = Polyline(mapView, false).apply {
            setPoints(ArrayList(waypoints))
            outlinePaint.apply {
                color = 0xFFAA00FF.toInt()
                strokeWidth = 10f
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
                isAntiAlias = true
            }
        }

        mapView.overlays.add(routeLine)
        _currentRouteLine = routeLine
        mapView.invalidate()
    }

    /**
     * Move the camera to frame [waypoints]. Callers must not invoke this while
     * navigating - NavigationMapCamera drives the map itself at that point, and this
     * would fight it.
     */
    fun frameRoute(mapView: MapView?, waypoints: List<GeoPoint>?) {
        val view = mapView ?: return
        val box = routeFramingBox(waypoints.orEmpty()) ?: return
        val borderPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, FRAME_ROUTE_BORDER_DP, view.resources.displayMetrics
        ).toInt()

        // zoomToBoundingBox reads the MapView's current width/height to compute the
        // zoom level; right after the map is first composed those are still 0, since
        // no layout pass has run yet, and osmdroid's own docs warn it zooms to level 0
        // instead of the box in that case. addOnFirstLayoutListener defers the call
        // until the view actually has a size. Once a layout has already happened (the
        // common case - replanning, or switching between route alternatives) it's a
        // no-op, so zoom straight away instead.
        if (view.isLayoutOccurred) {
            view.zoomToBoundingBox(box, true, borderPx)
        } else {
            view.addOnFirstLayoutListener { _, _, _, _, _ ->
                view.zoomToBoundingBox(box, true, borderPx)
            }
        }
    }
}
