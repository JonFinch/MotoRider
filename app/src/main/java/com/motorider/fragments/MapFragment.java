package com.motorider.fragments;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.motorider.R;
import com.motorider.maps.MotorcycleMapRenderer;
import com.motorider.services.RouteService;
import com.motorider.models.Route;
import com.motorider.models.Waypoint;
import com.motorider.utils.RouteUtils;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.List;

public class MapFragment extends Fragment {
    
    private static final String TAG = "MapFragment";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    
    private MapView mapView;
    private MyLocationNewOverlay locationOverlay;
    private MotorcycleMapRenderer mapRenderer;
    private RouteService routeService;
    private Button btnStartNavigation;
    private Button btnPlanRoute;
    private GeoPoint currentLocation;
    private GeoPoint destinationLocation;
    private Route currentRoute;
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        try {
            View view = inflater.inflate(R.layout.fragment_map, container, false);
            
            // Initialize map view safely
            initializeMapView(view);
            initializeButtons(view);
            requestLocationPermission();
            
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error creating MapFragment view", e);
            Toast.makeText(requireContext(), "Error initializing map: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
            return inflater.inflate(R.layout.fragment_map, container, false);
        }
    }

    private void initializeMapView(View view) {
        try {
            mapView = view.findViewById(R.id.mapview);
            
            // Check if map view is valid
            if (mapView == null) {
                Log.e(TAG, "MapView is null - cannot initialize");
                return;
            }
            
            // Set tile source to OpenStreetMap
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            
            // Set initial zoom level (check controller is available)
            if (mapView.getController() != null) {
                mapView.getController().setZoom(12.0);
                mapView.getController().setCenter(new GeoPoint(40.7128, -74.0060)); // Default to NYC
            }
            
            // Add map listener for debugging
            mapView.addMapListener(new MapListener() {
                @Override
                public boolean onScroll(ScrollEvent event) {
                    return false;
                }
                
                @Override
                public boolean onZoom(ZoomEvent event) {
                    return false;
                }
            });
            
            // Initialize location overlay
            locationOverlay = new MyLocationNewOverlay(mapView);
            // Only enable location if permission is granted (permission request happens later)
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) 
                    == PackageManager.PERMISSION_GRANTED) {
                locationOverlay.enableMyLocation();
            }
            
            mapView.getOverlays().add(locationOverlay);
            
            // Initialize route service and renderer
            routeService = new RouteService();
            mapRenderer = new MotorcycleMapRenderer();
            
            Log.d(TAG, "MapView initialized with MAPNIK tile source");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing map view", e);
        }
    }
    
    private void initializeButtons(View view) {
        try {
            btnStartNavigation = view.findViewById(R.id.btn_start_navigation);
            btnPlanRoute = view.findViewById(R.id.btn_plan_route);
            
            if (btnStartNavigation != null) {
                btnStartNavigation.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startNavigation();
                    }
                });
            }
            
            if (btnPlanRoute != null) {
                btnPlanRoute.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        planRoute();
                    }
                });
            }
            
            Log.d(TAG, "Buttons initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing buttons", e);
        }
    }
    
    private void requestLocationPermission() {
        try {
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(), 
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 
                    LOCATION_PERMISSION_REQUEST_CODE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting location permission", e);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            try {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (locationOverlay != null) {
                        locationOverlay.enableMyLocation();
                    }
                    Log.d(TAG, "Location permission granted");
                } else {
                    Toast.makeText(requireContext(), "Location permission is required for navigation", 
                        Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling permission result", e);
            }
        }
    }
    
    private void planRoute() {
        try {
            if (currentLocation == null) {
                Toast.makeText(requireContext(), "Location not available yet. Please wait...", 
                    Toast.LENGTH_LONG).show();
                return;
            }
            
            // Create a sample route for demonstration
            Waypoint start = new Waypoint("Start", currentLocation);
            Waypoint end = new Waypoint("Destination", new GeoPoint(40.7589, -73.9851)); // Times Square
            List<Waypoint> waypoints = new ArrayList<>();
            waypoints.add(start);
            waypoints.add(end);
            
            // Calculate motorcycle route
            Route route = routeService.calculateMotorcycleRoute(start, end, waypoints);
            currentRoute = route;
            
            // Get route points for rendering
            List<GeoPoint> routePoints = new ArrayList<>();
            for (Waypoint waypoint : waypoints) {
                routePoints.add(waypoint.getLocation());
            }
            
            // Render the route on the map
            if (mapRenderer != null && mapView != null) {
                mapRenderer.renderMotorcycleRoute(mapView, routePoints);
            }
            
            // Show route information
            Toast.makeText(requireContext(), 
                "Route planned: " + String.format("%.1f km", route.getDistance()) + 
                ", Duration: " + String.format("%.0f min", route.getDuration() / 60.0), 
                Toast.LENGTH_LONG).show();
            
            Log.d(TAG, "Route planned: " + route.getDistance() + " km");
        } catch (Exception e) {
            Log.e(TAG, "Error planning route", e);
            Toast.makeText(requireContext(), "Error planning route: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
        }
    }
    
    private void startNavigation() {
        try {
            if (currentRoute == null) {
                Toast.makeText(requireContext(), "Please plan a route first", 
                    Toast.LENGTH_SHORT).show();
                return;
            }
            
            Toast.makeText(requireContext(), "Navigation started", 
                Toast.LENGTH_SHORT).show();
            
            Log.d(TAG, "Navigation started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting navigation", e);
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        try {
            if (mapView != null) {
                mapView.onResume();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume", e);
        }
    }
    
    @Override
    public void onPause() {
        super.onPause();
        try {
            if (mapView != null) {
                mapView.onPause();
            }
            Configuration.getInstance().save(requireContext(), 
                requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
        } catch (Exception e) {
            Log.e(TAG, "Error in onPause", e);
        }
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            if (mapView != null) {
                mapView.onDetach();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroyView", e);
        }
    }
}