package com.motorider.maps;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.util.GeoPoint;
import java.util.ArrayList;
import java.util.List;

public class MotorcycleMapRenderer {

    /** Keeps a reference to the last rendered route so re-planning replaces it
     * instead of stacking overlays on top of one another. */
    private Polyline currentRouteLine;

    /**
     * Render a motorcycle-friendly route on the map.
     *
     * The points passed in are the ordered coordinates that make up the route.
     * When the route was calculated against the OSRM/OSM server this is the full
     * road-following geometry, so the line must be drawn as a {@link Polyline}
     * (an open, unfilled path). Using a Polygon here would close the shape back
     * to the start and fill it, which is why the route previously appeared as a
     * straight line across the map.
     *
     * @param mapView The map view to render on
     * @param waypoints Ordered list of points that make up the route path
     */
    public void renderMotorcycleRoute(MapView mapView, List<GeoPoint> waypoints) {
        if (mapView == null || mapView.getOverlays() == null
                || waypoints == null || waypoints.isEmpty()) {
            return;
        }

        // Remove the previously drawn route (if any) so we don't stack lines.
        if (currentRouteLine != null) {
            mapView.getOverlays().remove(currentRouteLine);
            currentRouteLine = null;
        }

        Polyline routeLine = new Polyline();
        // Defensive copy - osmdroid keeps a reference to the supplied list.
        routeLine.setPoints(new ArrayList<>(waypoints));

        routeLine.getOutlinePaint().setColor(0xFFAA00FF);
        routeLine.getOutlinePaint().setStrokeWidth(10f);
        routeLine.getOutlinePaint().setStrokeCap(android.graphics.Paint.Cap.ROUND);
        routeLine.getOutlinePaint().setStrokeJoin(android.graphics.Paint.Join.ROUND);
        routeLine.getOutlinePaint().setAntiAlias(true);

        mapView.getOverlays().add(routeLine);
        currentRouteLine = routeLine;
        mapView.invalidate();
    }

    /**
     * Render curvature indicators on the map
     * @param mapView The map view to render on
     * @param waypoints List of waypoints with curvature data
     */
    public void renderCurvatureIndicators(MapView mapView, List<GeoPoint> waypoints) {
        // This would render visual indicators for sharp turns
        // Implementation would depend on specific curvature values
    }
}
