package com.motorider.fragments;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
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
    private Button btnStartNavigation;
    private Button btnPlanRoute;
    private GeoPoint currentLocation;
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
            locationOverlay.enableMyLocation();
            locationOverlay.runOnFirstFix(new Runnable() {
                @Override
                public void run() {
                    GeoPoint loc = locationOverlay.getMyLocation();
                    if (loc != null) {
                        currentLocation = loc;
                        if (mapView.getController() != null) {
                            requireActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mapView.getController().setCenter(currentLocation);
                                    mapView.getController().setZoom(15.0);
                                }
                            });
                        }
                    }
                }
            });
            
            if (mapView.getOverlays() != null) {
                mapView.getOverlays().add(locationOverlay);
            }
            
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
                        showRoutePlanningDialog();
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
    
    private void showRoutePlanningDialog() {
        try {
            RoutePlanningDialogFragment dialog = RoutePlanningDialogFragment.newInstance(new RoutePlanningDialogFragment.OnRoutePlannedListener() {
                @Override
                public void onRoutePlanned(Route route) {
                    currentRoute = route;
                    List<GeoPoint> routePoints = new ArrayList<>();
                    for (Waypoint waypoint : route.getWaypoints()) {
                        routePoints.add(waypoint.getLocation());
                    }
                    if (mapRenderer != null && mapView != null) {
                        mapRenderer.renderMotorcycleRoute(mapView, routePoints);
                    }
                    if (isAdded() && getContext() != null) {
                        Toast.makeText(getContext(), 
                            "Route: " + String.format("%.1f km", route.getDistance()) + 
                            ", Duration: " + String.format("%.0f min", route.getDuration() / 60.0), 
                            Toast.LENGTH_LONG).show();
                    }
                    Log.d(TAG, "Route displayed: " + route.getDistance() + " km");
                }
            });
            dialog.show(getParentFragmentManager(), "routePlanning");
        } catch (Exception e) {
            Log.e(TAG, "Error showing route planning dialog", e);
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
                    if (isAdded() && getContext() != null) {
                        Toast.makeText(getContext(), "Location permission is required for navigation", 
                            Toast.LENGTH_LONG).show();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling permission result", e);
            }
        }
    }
    

    
    private void startNavigation() {
        try {
            if (currentRoute == null) {
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "Please plan a route first", 
                        Toast.LENGTH_SHORT).show();
                }
                return;
            }
            
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), "Navigation started", 
                    Toast.LENGTH_SHORT).show();
            }
            
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
            if (locationOverlay != null) {
                locationOverlay.disableMyLocation();
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
            if (locationOverlay != null) {
                locationOverlay.disableMyLocation();
                if (mapView != null && mapView.getOverlays() != null) {
                    mapView.getOverlays().remove(locationOverlay);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroyView", e);
        }
    }
}