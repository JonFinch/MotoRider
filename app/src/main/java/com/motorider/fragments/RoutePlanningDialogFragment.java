package com.motorider.fragments;

import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.motorider.R;
import com.motorider.models.Route;
import com.motorider.models.RouteType;
import com.motorider.models.Waypoint;
import com.motorider.services.RouteService;
import com.motorider.utils.RouteUtils;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

public class RoutePlanningDialogFragment extends androidx.fragment.app.DialogFragment {
    
    private static final String TAG = "RoutePlanningDialog";
    
    public interface OnRoutePlannedListener {
        void onRoutePlanned(Route route);
    }
    
    private OnRoutePlannedListener listener;
    private RouteType selectedRouteType = RouteType.MOTORCYCLE;
    private RouteType selectedRoutePreference = RouteType.DIRECT;
    private List<Waypoint> waypoints = new ArrayList<>();
    private RouteService routeService = new RouteService();
    private int geocodingRequestsPending = 0;
    private List<GeocodingResult> geocodingResults = new ArrayList<>();
    
    private EditText etStart;
    private EditText etEnd;
    private LinearLayout intermediateWaypointsContainer;
    private MaterialButton btnCurvature;
    private MaterialButton btnAvoidances;
    private MaterialButton btnMoreOptions;
    
    public static RoutePlanningDialogFragment newInstance(OnRoutePlannedListener listener) {
        RoutePlanningDialogFragment fragment = new RoutePlanningDialogFragment();
        fragment.listener = listener;
        return fragment;
    }
    
    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View contentView = inflater.inflate(R.layout.route_planning_panel, null);
        
        initializeViews(contentView);
        
        builder.setView(contentView)
               .setCancelable(true);
        
        return builder.create();
    }
    
    private void initializeViews(View contentView) {
        etStart = contentView.findViewById(R.id.et_start);
        etEnd = contentView.findViewById(R.id.et_end);
        intermediateWaypointsContainer = contentView.findViewById(R.id.intermediate_waypoints);
        
        if (etStart == null || etEnd == null || intermediateWaypointsContainer == null) {
            Log.e(TAG, "Required views not found in layout");
            return;
        }
        
        btnCurvature = contentView.findViewById(R.id.btn_curvature);
        btnAvoidances = contentView.findViewById(R.id.btn_avoidances);
        btnMoreOptions = contentView.findViewById(R.id.btn_more_options);
        
        // Route type buttons
        ImageButton btnMotorcycle = contentView.findViewById(R.id.btn_motorcycle);
        ImageButton btnTruck = contentView.findViewById(R.id.btn_truck);
        ImageButton btnCar = contentView.findViewById(R.id.btn_car);
        ImageButton btnBike = contentView.findViewById(R.id.btn_bike);
        
        if (btnMotorcycle != null) {
            btnMotorcycle.setOnClickListener(v -> selectRouteType(RouteType.MOTORCYCLE, btnMotorcycle, btnTruck, btnCar, btnBike));
        }
        if (btnTruck != null) {
            btnTruck.setOnClickListener(v -> selectRouteType(RouteType.TRUCK, btnTruck, btnMotorcycle, btnCar, btnBike));
        }
        if (btnCar != null) {
            btnCar.setOnClickListener(v -> selectRouteType(RouteType.CAR, btnCar, btnMotorcycle, btnTruck, btnBike));
        }
        if (btnBike != null) {
            btnBike.setOnClickListener(v -> selectRouteType(RouteType.BIKE, btnBike, btnMotorcycle, btnTruck, btnCar));
        }
        
        selectRouteType(RouteType.MOTORCYCLE, btnMotorcycle, btnTruck, btnCar, btnBike);
        
        if (btnCurvature != null) {
            btnCurvature.setOnClickListener(v -> showCurvatureOptionsMenu());
        }
        
        if (btnAvoidances != null) {
            btnAvoidances.setOnClickListener(v -> showAvoidancesDialog());
        }
        
        if (btnMoreOptions != null) {
            btnMoreOptions.setOnClickListener(v -> showMoreOptionsDialog());
        }
        
        // Add waypoint button
        MaterialButton btnAddWaypoint = contentView.findViewById(R.id.btn_add_waypoint);
        if (btnAddWaypoint != null) {
            btnAddWaypoint.setOnClickListener(v -> addIntermediateWaypoint());
        }
        
        // Plan route button
        MaterialButton btnPlanRoute = contentView.findViewById(R.id.btn_plan_route);
        if (btnPlanRoute != null) {
            btnPlanRoute.setOnClickListener(v -> planRoute());
        }
        
        // Set location buttons
        ImageButton btnSetStart = contentView.findViewById(R.id.btn_set_start);
        ImageButton btnSetEnd = contentView.findViewById(R.id.btn_set_end);
        
        if (btnSetStart != null) {
            btnSetStart.setOnClickListener(v -> {
                Log.d(TAG, "Set start location tapped");
            });
        }
        
        if (btnSetEnd != null) {
            btnSetEnd.setOnClickListener(v -> {
                Log.d(TAG, "Set end location tapped");
            });
        }
        
        // Remove buttons
        ImageButton btnRemoveStart = contentView.findViewById(R.id.btn_remove_start);
        ImageButton btnRemoveEnd = contentView.findViewById(R.id.btn_remove_end);
        
        if (btnRemoveStart != null) {
            btnRemoveStart.setOnClickListener(v -> {
                if (etStart != null) etStart.setText("");
            });
        }
        
        if (btnRemoveEnd != null) {
            btnRemoveEnd.setOnClickListener(v -> {
                if (etEnd != null) etEnd.setText("");
            });
        }
    }
    
    private void showCurvatureOptionsMenu() {
        final RouteType[] preferences = RouteType.values();
        String[] displayNames = new String[preferences.length];
        for (int i = 0; i < preferences.length; i++) {
            displayNames[i] = preferences[i].getDisplayName();
        }
        
        int selectedIndex = selectedRoutePreference.ordinal();
        
        new AlertDialog.Builder(requireActivity())
            .setTitle("Route Preference")
            .setSingleChoiceItems(displayNames, selectedIndex, (dialog, which) -> {
                selectedRoutePreference = preferences[which];
                if (btnCurvature != null) {
                    btnCurvature.setText(preferences[which].getDisplayName());
                }
                dialog.dismiss();
            })
            .setPositiveButton("Done", null)
            .show();
        
        Log.d(TAG, "Selected route preference: " + selectedRoutePreference.getDisplayName());
    }
    
    private void showAvoidancesDialog() {
        android.widget.Toast.makeText(requireContext(), 
            "Avoidances: Coming soon", android.widget.Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Avoidances button tapped");
    }
    
    private void showMoreOptionsDialog() {
        android.widget.Toast.makeText(requireContext(), 
            "More options: Coming soon", android.widget.Toast.LENGTH_SHORT).show();
        Log.d(TAG, "More options button tapped");
    }
    
    private void selectRouteType(RouteType type, ImageButton selected, ImageButton... others) {
        selectedRouteType = type;
        
        // Update visual selection
        for (ImageButton btn : others) {
            btn.setAlpha(0.5f);
        }
        selected.setAlpha(1.0f);
        
        Log.d(TAG, "Selected route type: " + type.getDisplayName());
    }
    
    private void addIntermediateWaypoint() {
        // Create a new intermediate waypoint card
        MaterialCardView waypointCard = new MaterialCardView(requireContext());
        waypointCard.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        waypointCard.setCardBackgroundColor(getResources().getColor(android.R.color.transparent));
        waypointCard.setCardElevation(0);
        waypointCard.setRadius(12);
        
        LinearLayout waypointLayout = new LinearLayout(requireContext());
        waypointLayout.setOrientation(LinearLayout.HORIZONTAL);
        waypointLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        waypointLayout.setPadding(12, 12, 12, 12);
        
        // Add waypoint icon
        android.widget.ImageView icon = new android.widget.ImageView(requireContext());
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(24, 24);
        iconParams.setMargins(0, 0, 8, 0);
        icon.setLayoutParams(iconParams);
        icon.setImageResource(R.drawable.ic_set_location);
        waypointLayout.addView(icon);
        
        // Add waypoint edit text
        EditText etWaypoint = new EditText(requireContext());
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, 
            LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        textParams.setMargins(8, 0, 8, 0);
        etWaypoint.setLayoutParams(textParams);
        etWaypoint.setHint("Waypoint " + (intermediateWaypointsContainer.getChildCount() + 1));
        etWaypoint.setPadding(8, 0, 8, 0);
        etWaypoint.setTextSize(14);
        waypointLayout.addView(etWaypoint);
        
        // Add remove button
        ImageButton btnRemove = new ImageButton(requireContext());
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(32, 32);
        btnParams.setMargins(0, 0, 0, 0);
        btnRemove.setLayoutParams(btnParams);
        btnRemove.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnRemove.setImageResource(R.drawable.ic_close);
        btnRemove.setOnClickListener(v -> {
            intermediateWaypointsContainer.removeView(waypointCard);
        });
        waypointLayout.addView(btnRemove);
        
        waypointCard.addView(waypointLayout);
        intermediateWaypointsContainer.addView(waypointCard);
        
        Log.d(TAG, "Added intermediate waypoint");
    }
    
    private void planRoute() {
        try {
            if (etStart == null || etEnd == null || intermediateWaypointsContainer == null) {
                android.widget.Toast.makeText(requireContext(), 
                    "Error: UI not initialized", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            
            String startName = etStart.getText().toString().trim();
            String endName = etEnd.getText().toString().trim();
            
            if (startName.isEmpty() && endName.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), 
                    "Please enter at least start or end location", 
                    android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            
            waypoints.clear();
            geocodingResults.clear();
            geocodingRequestsPending = 0;
            
            List<String> startNames = new ArrayList<>();
            List<String> endNames = new ArrayList<>();
            List<String> intermediateNames = new ArrayList<>();
            
            if (!startName.isEmpty()) {
                startNames.add(startName);
                geocodingRequestsPending++;
            }
            
            for (int i = 0; i < intermediateWaypointsContainer.getChildCount(); i++) {
                View child = intermediateWaypointsContainer.getChildAt(i);
                if (child instanceof MaterialCardView) {
                    MaterialCardView card = (MaterialCardView) child;
                    View layout = card.getChildAt(0);
                    if (layout instanceof LinearLayout) {
                        View etView = ((LinearLayout) layout).getChildAt(1);
                        if (etView instanceof EditText) {
                            EditText et = (EditText) etView;
                            String waypointName = et.getText().toString().trim();
                            
                            if (!waypointName.isEmpty()) {
                                intermediateNames.add(waypointName);
                                geocodingRequestsPending++;
                            }
                        }
                    }
                }
            }
            
            if (!endName.isEmpty()) {
                endNames.add(endName);
                geocodingRequestsPending++;
            }
            
            if (geocodingRequestsPending == 0) {
                android.widget.Toast.makeText(requireContext(), 
                    "Please provide at least start and end locations", 
                    android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            
            int startIdx = 0;
            int endIdx = startNames.size();
            
            for (int i = 0; i < startNames.size(); i++) {
                final int index = i;
                RouteUtils.geocodeLocation(startNames.get(i), new RouteUtils.GeocodingCallback() {
                    @Override
                    public void onResult(GeoPoint geoPoint) {
                        geocodingResults.add(new GeocodingResult(index, geoPoint));
                        onGeocodingComplete();
                    }
                    
                    @Override
                    public void onError(String error) {
                        android.widget.Toast.makeText(requireContext(), 
                            error, android.widget.Toast.LENGTH_SHORT).show();
                        onGeocodingComplete();
                    }
                });
            }
            
            for (int i = 0; i < intermediateNames.size(); i++) {
                final int index = startNames.size() + i;
                RouteUtils.geocodeLocation(intermediateNames.get(i), new RouteUtils.GeocodingCallback() {
                    @Override
                    public void onResult(GeoPoint geoPoint) {
                        geocodingResults.add(new GeocodingResult(index, geoPoint));
                        onGeocodingComplete();
                    }
                    
                    @Override
                    public void onError(String error) {
                        android.widget.Toast.makeText(requireContext(), 
                            error, android.widget.Toast.LENGTH_SHORT).show();
                        onGeocodingComplete();
                    }
                });
            }
            
            for (int i = 0; i < endNames.size(); i++) {
                final int index = startNames.size() + intermediateNames.size() + i;
                RouteUtils.geocodeLocation(endNames.get(i), new RouteUtils.GeocodingCallback() {
                    @Override
                    public void onResult(GeoPoint geoPoint) {
                        geocodingResults.add(new GeocodingResult(index, geoPoint));
                        onGeocodingComplete();
                    }
                    
                    @Override
                    public void onError(String error) {
                        android.widget.Toast.makeText(requireContext(), 
                            error, android.widget.Toast.LENGTH_SHORT).show();
                        onGeocodingComplete();
                    }
                });
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error planning route", e);
            android.widget.Toast.makeText(requireContext(), 
                "Error planning route: " + e.getMessage(), 
                android.widget.Toast.LENGTH_LONG).show();
        }
    }
    
    private void onGeocodingComplete() {
        geocodingRequestsPending--;
        
        if (geocodingRequestsPending > 0) {
            return;
        }
        
        waypoints.clear();
        
        for (GeocodingResult result : geocodingResults) {
            if (result.geoPoint != null) {
                String name;
                if (result.index == 0) {
                    name = etStart.getText().toString().trim();
                } else if (result.index == startCount()) {
                    name = etEnd.getText().toString().trim();
                } else {
                    int waypointIdx = result.index - startCount() + 1;
                    name = "Waypoint " + waypointIdx;
                }
                waypoints.add(new Waypoint(name, result.geoPoint));
            }
        }
        
        if (waypoints.size() < 2) {
            android.widget.Toast.makeText(requireContext(), 
                "Could not locate enough addresses. Please check spelling and try again.", 
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        
        Waypoint start = waypoints.get(0);
        Waypoint end = waypoints.get(waypoints.size() - 1);
        List<Waypoint> intermediate = waypoints.size() > 2 ? 
            waypoints.subList(1, waypoints.size() - 1) : new ArrayList<>();
        
        Route route = routeService.calculateMotorcycleRoute(start, end, intermediate, selectedRoutePreference);
        
        if (route != null) {
            route.setRouteType(selectedRouteType);
            
            if (listener != null) {
                listener.onRoutePlanned(route);
            }
        }
        
        dismiss();
        Log.d(TAG, "Route planned with " + waypoints.size() + " waypoints, preference: " + selectedRoutePreference.getDisplayName());
    }
    
    private int startCount() {
        String startName = etStart.getText().toString().trim();
        return startName.isEmpty() ? 0 : 1;
    }
    
    private static class GeocodingResult {
        int index;
        GeoPoint geoPoint;
        
        GeocodingResult(int index, GeoPoint geoPoint) {
            this.index = index;
            this.geoPoint = geoPoint;
        }
    }
}
