package com.motorider.maps

import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.GeoPoint
import java.util.ArrayList

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
}
