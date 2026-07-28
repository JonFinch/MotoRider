package com.motorider.services;

import com.motorider.models.Avoidance;
import com.motorider.models.Route;
import com.motorider.models.RouteType;
import com.motorider.models.Waypoint;
import com.motorider.utils.RouteUtils;

import org.osmdroid.util.GeoPoint;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RouteService {

    public interface RouteCalculationCallback {
        void onRouteCalculated(Route route);
        void onError(String error);
    }

    public Route calculateMotorcycleRoute(Waypoint start, Waypoint end, List<Waypoint> waypoints) {
        return calculateMotorcycleRoute(start, end, waypoints, RouteType.MOTORCYCLE, null);
    }

    public Route calculateMotorcycleRoute(Waypoint start, Waypoint end, List<Waypoint> waypoints, RouteType routePreference) {
        return calculateMotorcycleRoute(start, end, waypoints, routePreference, null);
    }

    public Route calculateMotorcycleRoute(Waypoint start, Waypoint end, List<Waypoint> waypoints, RouteType routePreference, Set<Avoidance> avoidances) {
        return fetchRouteFromOsrm(start, end, waypoints, routePreference, avoidances);
    }

    public void calculateRouteAsync(Waypoint start, Waypoint end, List<Waypoint> waypoints, RouteType routePreference, Set<Avoidance> avoidances, RouteCalculationCallback callback) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                List<Waypoint> allWaypoints = new ArrayList<>();
                allWaypoints.add(start);
                if (waypoints != null) {
                    for (Waypoint wp : waypoints) {
                        if (wp != start && wp != end) {
                            allWaypoints.add(wp);
                        }
                    }
                }
                allWaypoints.add(end);

                Route route = fetchRouteFromOsrm(start, end, waypoints, routePreference, avoidances);
                if (callback != null) callback.onRouteCalculated(route);
            } catch (Exception e) {
                if (callback != null) callback.onError(e.getMessage());
            }
            executor.shutdown();
        });
    }

    private Route fetchRouteFromOsrm(Waypoint start, Waypoint end, List<Waypoint> waypoints, RouteType routePreference, Set<Avoidance> avoidances) {
        Route route = new Route(routePreference.getDisplayName() + " Route", buildFullWaypointList(start, end, waypoints));
        route.setAvoidances(avoidances);

        List<GeoPoint> allPoints = new ArrayList<>();
        allPoints.add(start.getLocation());
        if (waypoints != null) {
            for (Waypoint wp : waypoints) {
                if (wp.getLocation() != start.getLocation() && wp.getLocation() != end.getLocation()) {
                    allPoints.add(wp.getLocation());
                }
            }
        }
        allPoints.add(end.getLocation());

        try {
            StringBuilder coords = new StringBuilder();
            for (int i = 0; i < allPoints.size(); i++) {
                GeoPoint pt = allPoints.get(i);
                if (pt != null) {
                    if (i > 0) coords.append(';');
                    coords.append(pt.getLongitude()).append(',').append(pt.getLatitude());
                }
            }

            String urlStr = "https://router.project-osrm.org/route/v1/driving/" +
                coords.toString() +
                "?geometries=geojson&overview=full&alternatives=false&steps=false";

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "MotoRider/1.0");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                RouteUtils.OsrmResult osrm = RouteUtils.parseOsrmResponse(response.toString());
                if (osrm != null) {
                    route.setRouteGeometry(osrm.geometry);
                    route.setDistance(osrm.distance / 1000.0);
                    route.setDuration(osrm.duration);
                    applyAvoidancePenalty(route);
                    calculateCurvatureAndElevation(route, routePreference);
                    return route;
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            try { android.util.Log.w("RouteService", "OSRM routing failed, using straight-line estimate", e); } catch (Exception ignored) {}
        }

        calculateStraightLineMetrics(route, routePreference);
        return route;
    }

    private List<Waypoint> buildFullWaypointList(Waypoint start, Waypoint end, List<Waypoint> waypoints) {
        List<Waypoint> all = new ArrayList<>();
        all.add(start);
        if (waypoints != null) {
            for (Waypoint wp : waypoints) {
                if (wp != start && wp != end && !all.contains(wp)) {
                    all.add(wp);
                }
            }
        }
        all.add(end);
        return all;
    }

    private void applyAvoidancePenalty(Route route) {
        Set<Avoidance> avoidances = route.getAvoidances();
        if (avoidances != null && !avoidances.isEmpty()) {
            double penaltyPerItem = 0.05 * avoidances.size();
            double penalty = Math.min(penaltyPerItem, 0.25);
            route.setDistance(route.getDistance() * (1.0 + penalty));
            route.setDuration(route.getDuration() * (1.0 + penalty));
        }
    }

    private void calculateCurvatureAndElevation(Route route, RouteType routePreference) {
        List<Waypoint> waypoints = route.getWaypoints();
        if (waypoints != null && waypoints.size() >= 3) {
            double baseCurvature = RouteUtils.calculateCurvatureScore(waypoints);
            route.setCurvatureScore(baseCurvature * routePreference.getCurvatureWeight());
        }
        if (waypoints != null) {
            route.setElevationGain(RouteUtils.calculateElevationGain(waypoints));
        }
    }

    private void calculateStraightLineMetrics(Route route, RouteType routePreference) {
        List<Waypoint> waypoints = route.getWaypoints();
        if (waypoints == null || waypoints.isEmpty()) return;

        double totalDistance = 0.0;
        double totalDuration = 0.0;

        for (int i = 1; i < waypoints.size(); i++) {
            GeoPoint prev = waypoints.get(i - 1).getLocation();
            GeoPoint current = waypoints.get(i).getLocation();

            if (prev != null && current != null) {
                double lat1 = Math.toRadians(prev.getLatitude());
                double lat2 = Math.toRadians(current.getLatitude());
                double dLat = Math.toRadians(current.getLatitude() - prev.getLatitude());
                double dLon = Math.toRadians(current.getLongitude() - prev.getLongitude());

                double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(lat1) * Math.cos(lat2) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2);
                double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

                double segmentDistance = 6371000.0 * c;
                totalDistance += segmentDistance;

                double speed = 60000.0 * routePreference.getSpeedFactor();
                totalDuration += segmentDistance / speed;
            }
        }

        route.setDistance(totalDistance / 1000.0);
        route.setDuration(totalDuration);
        applyAvoidancePenalty(route);
        calculateCurvatureAndElevation(route, routePreference);
    }
}