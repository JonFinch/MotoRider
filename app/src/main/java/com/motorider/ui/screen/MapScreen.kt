package com.motorider.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.EditLocation
import androidx.compose.material.icons.filled.Motorcycle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorider.maps.MotorcycleMapRenderer
import com.motorider.models.Avoidance
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import com.motorider.services.RouteService
import com.motorider.ui.component.OsmMapView
import com.motorider.ui.component.RoutePlanningSheet
import com.motorider.ui.theme.Primary
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@Composable
fun MapScreen() {
    val context = LocalContext.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var currentLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var currentRoute by remember { mutableStateOf<Route?>(null) }
    var showRoutePlanning by remember { mutableStateOf(false) }

    val mapRenderer = remember { MotorcycleMapRenderer() }
    val routeService = remember { RouteService() }

    Box(modifier = Modifier.fillMaxSize()) {
        OsmMapView(
            modifier = Modifier.fillMaxSize(),
            onLocationReceived = { location ->
                currentLocation = location
            },
            onMapViewReady = { mv ->
                mapView = mv
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (currentRoute != null) {
                        Toast.makeText(context, "Navigation started", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Please plan a route first", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(Icons.Default.Motorcycle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start Navigation", fontSize = 14.sp)
            }

            Button(
                onClick = { showRoutePlanning = true },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(Icons.Default.EditLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Plan Route", fontSize = 14.sp)
            }
        }
    }

    if (showRoutePlanning) {
        RoutePlanningSheet(
            onPlanRoute = { start, end, intermediates, vehicleType, preference, avoidances ->
                showRoutePlanning = false
                calculateAndRenderRoute(
                    context = context,
                    routeService = routeService,
                    mapRenderer = mapRenderer,
                    mapView = mapView,
                    startName = start,
                    endName = end,
                    intermediateNames = intermediates,
                    vehicleType = vehicleType,
                    preference = preference,
                    avoidances = avoidances,
                    currentLocation = currentLocation,
                    onRouteReady = { route -> currentRoute = route }
                )
            },
            onDismiss = { showRoutePlanning = false }
        )
    }
}

private fun calculateAndRenderRoute(
    context: android.content.Context,
    routeService: RouteService,
    mapRenderer: MotorcycleMapRenderer,
    mapView: MapView?,
    startName: String,
    endName: String,
    intermediateNames: List<String>,
    vehicleType: RouteType,
    preference: RouteType,
    avoidances: Set<Avoidance>,
    currentLocation: GeoPoint?,
    onRouteReady: (Route) -> Unit
) {
    val startLocation = currentLocation ?: GeoPoint(51.5074, -0.1278)
    val endLocation = GeoPoint(51.5074 + 0.02, -0.1278 + 0.02)

    val start = Waypoint(startName.ifBlank { "Start" }, startLocation)
    val end = Waypoint(endName.ifBlank { "End" }, endLocation)
    val intermediates = intermediateNames.mapIndexed { index, name ->
        Waypoint(
            name.ifBlank { "Waypoint ${index + 1}" },
            GeoPoint(startLocation.latitude + (index + 1) * 0.005, startLocation.longitude + (index + 1) * 0.005)
        )
    }

    routeService.calculateRouteAsync(
        start, end, intermediates, preference, avoidances,
        object : RouteService.RouteCalculationCallback {
            override fun onRouteCalculated(route: Route) {
                route.routeType = vehicleType
                route.routeGeometry?.let { geometry ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        mapRenderer.renderMotorcycleRoute(mapView, geometry)
                        mapView?.invalidate()
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onRouteReady(route)
                    Toast.makeText(context, "Route planned: ${"%.1f".format(route.distance)} km", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(error: String) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "Route calculation failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    )
}
