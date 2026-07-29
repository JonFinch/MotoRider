package com.motorider.utils;

import com.motorider.models.Waypoint;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RouteUtils {
    
    public interface GeocodingCallback {
        void onResult(GeoPoint geoPoint);
        void onError(String error);
    }

    public static void geocodeLocation(String locationName, GeocodingCallback callback) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        final android.os.Handler[] mainHandler = new android.os.Handler[1];
        try {
            mainHandler[0] = new android.os.Handler(android.os.Looper.getMainLooper());
        } catch (Exception e) {
            // No main looper available (e.g., in unit tests)
        }
        
        executor.execute(() -> {
            try {
                String encoded = URLEncoder.encode(locationName, StandardCharsets.UTF_8.name());
                URL urlObj = new URL("https://nominatim.openstreetmap.org/search?q=" + 
                    encoded + "&format=json&limit=1");
                
                HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "MotoRider/1.0");
                
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                    
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    
                    GeoPoint parsed = parseGeocodingResponse(response.toString());
                    if (parsed != null) {
                        if (callback != null) callback.onResult(parsed);
                    } else {
                        if (callback != null) callback.onError("Location not found: " + locationName);
                    }
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                android.util.Log.w("RouteUtils", "Geocoding failed for: " + locationName, e);
                final String errorMsg = "Network error: " + e.getMessage();
                if (callback != null) {
                    if (mainHandler[0] != null) {
                        mainHandler[0].post(() -> callback.onError(errorMsg));
                    } else {
                        callback.onError(errorMsg);
                    }
                }
            }
            
            executor.shutdown();
        });
    }
    
    /**
     * Calculate curvature score for a route based on turns
     * @param waypoints List of waypoints along the route
     * @return Curvature score between 0 and 100
     */
    public static double calculateCurvatureScore(List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.size() < 3) {
            return 0.0;
        }
        
        double totalCurvature = 0.0;
        int validPoints = 0;
        
        // Calculate curvature between consecutive waypoints
        for (int i = 1; i < waypoints.size() - 1; i++) {
            Waypoint prev = waypoints.get(i - 1);
            Waypoint current = waypoints.get(i);
            Waypoint next = waypoints.get(i + 1);
            
            // Simple curvature calculation based on angle between segments
            double curvature = calculateAngle(prev, current, next);
            totalCurvature += Math.min(curvature, 90.0); // Cap at 90 degrees
            validPoints++;
        }
        
        if (validPoints > 0) {
            return (totalCurvature / validPoints) * 100.0 / 90.0;
        }
        
        return 0.0;
    }
    
    /**
     * Calculate the angle between three points
     * @param prev Previous point
     * @param current Current point
     * @param next Next point
     * @return Angle in degrees
     */
    private static double calculateAngle(Waypoint prev, Waypoint current, Waypoint next) {
        GeoPoint p = prev.getLocation();
        GeoPoint c = current.getLocation();
        GeoPoint n = next.getLocation();
        
        if (p == null || c == null || n == null) {
            return 45.0;
        }
        
        double lat1 = Math.toRadians(p.getLatitude());
        double lon1 = Math.toRadians(p.getLongitude());
        double lat2 = Math.toRadians(c.getLatitude());
        double lon2 = Math.toRadians(c.getLongitude());
        double lat3 = Math.toRadians(n.getLatitude());
        double lon3 = Math.toRadians(n.getLongitude());
        
        double angle1 = Math.atan2(
            Math.sin(lon1 - lon2) * Math.cos(lat1),
            Math.cos(lat2) * Math.tan(lat1) - Math.sin(lat2) * Math.cos(lon1 - lon2)
        );
        
        double angle2 = Math.atan2(
            Math.sin(lon3 - lon2) * Math.cos(lat3),
            Math.cos(lat2) * Math.tan(lat3) - Math.sin(lat2) * Math.cos(lon3 - lon2)
        );
        
        double angle = Math.toDegrees(Math.abs(angle1 - angle2));
        if (angle > 180.0) {
            angle = 360.0 - angle;
        }
        
        return angle;
    }
    
    /**
     * Calculate elevation gain for the route
     * @param waypoints List of waypoints along the route
     * @return Total elevation gain in meters
     */
    public static double calculateElevationGain(List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.size() < 2) {
            return 0.0;
        }
        
        double elevationGain = 0.0;
        double previousElevation = waypoints.get(0).getElevation();
        
        for (int i = 1; i < waypoints.size(); i++) {
            double currentElevation = waypoints.get(i).getElevation();
            if (currentElevation > previousElevation) {
                elevationGain += (currentElevation - previousElevation);
            }
            previousElevation = currentElevation;
        }
        
        return elevationGain;
    }

    /**
     * @deprecated Use geocodeLocation(String, GeocodingCallback) instead.
     * This method blocks the calling thread and causes ANRs.
     */
    @Deprecated
    public static GeoPoint stringToGeoPoint(String locationName) {
        android.util.Log.w("RouteUtils", "stringToGeoPoint is deprecated and blocks the UI thread. Use geocodeLocation with a callback instead.");
        return null;
    }
    
    public static class OsrmResult {
        public List<GeoPoint> geometry;
        public double distance;
        public double duration;

        public OsrmResult(List<GeoPoint> geometry, double distance, double duration) {
            this.geometry = geometry;
            this.distance = distance;
            this.duration = duration;
        }
    }

    /**
     * Parse an OSRM {@code /route/v1} response (requested with
     * {@code geometries=geojson&overview=full}) into the full road-following
     * geometry plus the route-level distance and duration.
     *
     * <p>The response is parsed with a real JSON parser rather than by scanning
     * the raw string. This ensures the entire nested {@code coordinates} array is
     * read and that the distance/duration come from the top-level route object
     * (not from the first per-leg summary, which previously produced wrong
     * totals for multi-waypoint routes).</p>
     *
     * @param jsonResponse the raw OSRM JSON body
     * @return the parsed result, or {@code null} if the response is missing,
     *         not {@code "Ok"}, or contains no usable geometry
     */
    public static OsrmResult parseOsrmResponse(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            return null;
        }

        try {
            JSONObject root = new JSONObject(jsonResponse);

            // OSRM signals success with "code":"Ok"; anything else (NoRoute,
            // InvalidUrl, etc.) means there is no route to draw.
            if (!"Ok".equals(root.optString("code"))) {
                return null;
            }

            JSONArray routes = root.optJSONArray("routes");
            if (routes == null || routes.length() == 0) {
                return null;
            }

            JSONObject route = routes.getJSONObject(0);

            // Route-level totals (metres / seconds).
            double distance = route.optDouble("distance", 0.0);
            double duration = route.optDouble("duration", 0.0);

            JSONObject geometry = route.optJSONObject("geometry");
            if (geometry == null) {
                return null;
            }

            // GeoJSON LineString: [[lon, lat], [lon, lat], ...]
            JSONArray coordinates = geometry.optJSONArray("coordinates");
            if (coordinates == null || coordinates.length() == 0) {
                return null;
            }

            List<GeoPoint> points = new java.util.ArrayList<>(coordinates.length());
            for (int i = 0; i < coordinates.length(); i++) {
                JSONArray pair = coordinates.optJSONArray(i);
                if (pair == null || pair.length() < 2) {
                    continue;
                }
                double lon = pair.getDouble(0);
                double lat = pair.getDouble(1);
                points.add(new GeoPoint(lat, lon));
            }

            if (points.isEmpty()) {
                return null;
            }

            return new OsrmResult(points, distance, duration);
        } catch (Exception e) {
            try { android.util.Log.w("RouteUtils", "Failed to parse OSRM response", e); } catch (Exception ignored) {}
            return null;
        }
    }

    public static GeoPoint parseGeocodingResponse(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            return null;
        }

        try {
            JSONArray results = new JSONArray(jsonResponse);
            if (results.length() == 0) {
                return null;
            }

            JSONObject first = results.getJSONObject(0);
            if (!first.has("lat") || !first.has("lon")) {
                return null;
            }

            // Nominatim returns lat/lon as quoted strings; getDouble coerces
            // both quoted-string and bare-numeric forms.
            double lat = first.getDouble("lat");
            double lon = first.getDouble("lon");
            return new GeoPoint(lat, lon);
        } catch (Exception e) {
            return null;
        }
    }
}