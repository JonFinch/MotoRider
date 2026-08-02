package com.motorider.ui.screen

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorider.maps.MotorcycleMapRenderer
import com.motorider.models.Avoidance
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import com.motorider.services.NavigationService
import com.motorider.services.RouteService
import com.motorider.ui.component.OsmMapView
import com.motorider.ui.theme.*
import com.motorider.utils.RouteUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.concurrent.atomic.AtomicInteger

enum class Screen { Plan, QuickRide, Search }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen() {
    val context = LocalContext.current

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var currentLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var currentRoutes by remember { mutableStateOf<List<Route>>(emptyList()) }
    var selectedRouteIndex by remember { mutableIntStateOf(0) }

    val mapRenderer = remember { MotorcycleMapRenderer() }
    val routeService = remember { RouteService() }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var currentScreen by remember { mutableStateOf(Screen.Plan) }
    val scope = rememberCoroutineScope()

    var routeInfoVisible by remember { mutableStateOf(false) }
    var isGeocoding by remember { mutableStateOf(false) }

    var lastStartText by remember { mutableStateOf("") }
    var lastEndText by remember { mutableStateOf("") }
    var lastIntermediates by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastLegPrefs by remember { mutableStateOf<List<RouteType>>(emptyList()) }
    var lastAvoidances by remember { mutableStateOf<Set<Avoidance>>(emptySet()) }

    LaunchedEffect(selectedRouteIndex, currentRoutes) {
        val route = currentRoutes.getOrNull(selectedRouteIndex) ?: return@LaunchedEffect
        route.routeGeometry?.let { geometry ->
            mapRenderer.renderMotorcycleRoute(mapView, geometry)
            mapView?.invalidate()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "MotoRider",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = BrandBlue,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DrawerItem(Screen.Plan, "Plan", Icons.Outlined.EditLocation, currentScreen) {
                    currentScreen = it
                    scope.launch { drawerState.close() }
                }
                DrawerItem(Screen.QuickRide, "Quick Ride", Icons.Outlined.Refresh, currentScreen) {
                    currentScreen = it
                    scope.launch { drawerState.close() }
                }
                DrawerItem(Screen.Search, "Search", Icons.Outlined.Search, currentScreen) {
                    currentScreen = it
                    scope.launch { drawerState.close() }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    "Life is too short for a boring ride.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariantLight,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (currentScreen) {
                                Screen.Plan -> "Plan Route"
                                Screen.QuickRide -> "Quick Ride"
                                Screen.Search -> "Search"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                    )
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                OsmMapView(
                    modifier = Modifier.fillMaxSize(),
                    onLocationReceived = { location -> currentLocation = location },
                    onMapViewReady = { mv -> mapView = mv }
                )

                AnimatedVisibility(
                    visible = routeInfoVisible && currentRoutes.isNotEmpty(),
                    enter = slideInVertically(tween(300)) { it },
                    exit = slideOutVertically(tween(300)) { it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    RouteInfoCard(
                        routes = currentRoutes,
                        selectedIndex = selectedRouteIndex,
                        onSelectRoute = { selectedRouteIndex = it },
                        onBackToPlanning = { routeInfoVisible = false }
                    )
                }

                if (isGeocoding) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shadowElevation = 4.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = BrandBlue
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("Finding route...", fontSize = 15.sp)
                            }
                        }
                    }
                }

                when (currentScreen) {
                    Screen.Plan -> PlanPanel(
                        currentLocation = currentLocation,
                        initialStart = lastStartText,
                        initialEnd = lastEndText,
                        initialIntermediates = lastIntermediates,
                        onPlanRoute = { start, end, intermediates, legPrefs, avoidances ->
                            lastStartText = start
                            lastEndText = end
                            lastIntermediates = intermediates
                            lastLegPrefs = legPrefs
                            lastAvoidances = avoidances
                            isGeocoding = true
                            planRoute(
                                context, routeService, mapRenderer, mapView,
                                start, end, intermediates,
                                legPrefs.firstOrNull() ?: RouteType.CURVY,
                                avoidances, currentLocation
                            ) { routes ->
                                currentRoutes = routes
                                selectedRouteIndex = 0
                                routeInfoVisible = true
                                isGeocoding = false
                            }
                        }
                    )
                    Screen.QuickRide -> QuickRidePanel(
                        currentLocation = currentLocation,
                        onGenerateRoundTrip = { dist, dir ->
                            currentLocation?.let { loc ->
                                isGeocoding = true
                                generateRoundTrip(
                                    context, routeService, loc, dist, dir,
                                    RouteType.CURVY, emptySet()
                                ) { routes ->
                                    currentRoutes = routes
                                    selectedRouteIndex = 0
                                    routeInfoVisible = true
                                    isGeocoding = false
                                }
                            } ?: Toast.makeText(context, "Waiting for GPS...", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Screen.Search -> SearchPanel()
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(
    screen: Screen,
    label: String,
    icon: ImageVector,
    current: Screen,
    onSelect: (Screen) -> Unit
) {
    val selected = current == screen
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        selected = selected,
        onClick = { onSelect(screen) },
        modifier = Modifier.padding(horizontal = 12.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = BrandBlue.copy(alpha = 0.1f),
            selectedIconColor = BrandBlue,
            selectedTextColor = BrandBlue
        )
    )
}

// ─── Plan Panel ─────────────────────────────────────────────────────────────

@Composable
private fun BoxScope.PlanPanel(
    currentLocation: GeoPoint?,
    initialStart: String,
    initialEnd: String,
    initialIntermediates: List<String>,
    onPlanRoute: (String, String, List<String>, List<RouteType>, Set<Avoidance>) -> Unit
) {
    var startText by remember { mutableStateOf(initialStart) }
    var endText by remember { mutableStateOf(initialEnd) }
    var intermediates by remember { mutableStateOf(initialIntermediates) }
    var selectedAvoidances by remember { mutableStateOf<Set<Avoidance>>(emptySet()) }
    var showPreferenceDialog by remember { mutableStateOf(false) }
    var showAvoidanceDialog by remember { mutableStateOf(false) }
    var editingLegIndex by remember { mutableStateOf(-1) }

    val legPrefs = remember { mutableStateListOf(RouteType.CURVY) }
    LaunchedEffect(intermediates.size) {
        val needed = 1 + intermediates.size
        while (legPrefs.size < needed) legPrefs.add(RouteType.CURVY)
    }

    Surface(
        modifier = Modifier.align(Alignment.BottomCenter),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 12.dp,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LegChip("Leg 1", legPrefs.getOrElse(0) { RouteType.CURVY }) {
                    editingLegIndex = 0; showPreferenceDialog = true
                }
                intermediates.forEachIndexed { i, _ ->
                    LegChip("Leg ${i + 2}", legPrefs.getOrElse(i + 1) { RouteType.CURVY }) {
                        editingLegIndex = i + 1; showPreferenceDialog = true
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            WaypointField(
                icon = Icons.Default.Home, tint = BrandBlue,
                hint = "Start location", value = startText,
                onValueChange = { startText = it },
                bgColor = StartLocationBgLight,
                currentLocation = currentLocation
            )

            intermediates.forEachIndexed { i, wp ->
                Spacer(Modifier.height(2.dp))
                WaypointField(
                    icon = Icons.Default.MyLocation, tint = AccentOrange,
                    hint = "Via point ${i + 1}", value = wp,
                    onValueChange = { value -> intermediates = intermediates.toMutableList().also { it[i] = value } },
                    bgColor = ViaLocationBgLight,
                    currentLocation = currentLocation,
                    onRemove = { intermediates = intermediates.toMutableList().also { it.removeAt(i) } }
                )
            }

            Spacer(Modifier.height(2.dp))
            WaypointField(
                icon = Icons.Default.Flag, tint = ErrorRed,
                hint = "End location", value = endText,
                onValueChange = { endText = it },
                bgColor = EndLocationBgLight,
                currentLocation = currentLocation
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = { showPreferenceDialog = true },
                    label = { Text("Curvature") },
                    leadingIcon = { Icon(Icons.Outlined.Timeline, null, Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = { showAvoidanceDialog = true },
                    label = { Text(if (selectedAvoidances.isEmpty()) "Avoidances" else "Avoidances (${selectedAvoidances.size})") },
                    leadingIcon = { Icon(Icons.Outlined.Shield, null, Modifier.size(16.dp)) }
                )
                IconButton(onClick = { intermediates = intermediates + "" }) {
                    Icon(Icons.Default.Add, "Add via", tint = BrandBlue)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val im = intermediates.filter { it.isNotBlank() }
                        onPlanRoute(startText, endText, im, legPrefs.toList(), selectedAvoidances)
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Go", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showPreferenceDialog) {
        PreferenceDialog(
            legPrefs.getOrElse(editingLegIndex) { RouteType.CURVY },
            onSelect = { legPrefs[editingLegIndex] = it; showPreferenceDialog = false },
            onDismiss = { showPreferenceDialog = false }
        )
    }
    if (showAvoidanceDialog) {
        AvoidanceDialog(
            selectedAvoidances,
            onUpdate = { selectedAvoidances = it; showAvoidanceDialog = false },
            onDismiss = { showAvoidanceDialog = false }
        )
    }
}

// ─── Quick Ride Panel ───────────────────────────────────────────────────────

@Composable
private fun BoxScope.QuickRidePanel(
    currentLocation: GeoPoint?,
    onGenerateRoundTrip: (Double, Int) -> Unit
) {
    var selectedDistance by remember { mutableDoubleStateOf(30.0) }
    var selectedDirection by remember { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier.align(Alignment.BottomCenter),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Round Trip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Text("Distance", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariantLight)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val distances = listOf(30.0 to "30", 50.0 to "50", 80.0 to "80", 120.0 to "120", 200.0 to "200")
                distances.forEach { (d, l) ->
                    FilterChip(
                        selected = selectedDistance == d,
                        onClick = { selectedDistance = d },
                        label = { Text(l, fontSize = 13.sp) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Direction", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariantLight)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val dirs = listOf(0 to "N", 45 to "NE", 90 to "E", 135 to "SE", 180 to "S", 225 to "SW", 270 to "W", 315 to "NW")
                dirs.forEach { (deg, lbl) ->
                    FilterChip(
                        selected = selectedDirection == deg,
                        onClick = { selectedDirection = deg },
                        label = { Text(lbl, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { onGenerateRoundTrip(selectedDistance, selectedDirection) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                enabled = currentLocation != null
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (currentLocation != null) "Generate ($selectedDistance km)" else "Waiting for GPS...")
            }
        }
    }
}

// ─── Search Panel ───────────────────────────────────────────────────────────

@Composable
private fun BoxScope.SearchPanel() {
    var query by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 6.dp
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            placeholder = { Text("Search for places, POIs...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )
    }
}

// ─── Reusable Components ────────────────────────────────────────────────────

@Composable
private fun WaypointField(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    bgColor: androidx.compose.ui.graphics.Color,
    currentLocation: GeoPoint?,
    onRemove: (() -> Unit)? = null
) {
    var suggestions by remember { mutableStateOf<List<RouteUtils.LocationSuggestion>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var searchGen by remember { mutableIntStateOf(0) }

    LaunchedEffect(value) {
        showSuggestions = false
        if (value.length < 3) return@LaunchedEffect
        val gen = ++searchGen
        delay(1000)
        if (gen != searchGen) return@LaunchedEffect
        RouteUtils.searchLocations(value, currentLocation?.latitude, currentLocation?.longitude) { res ->
            suggestions = res
            showSuggestions = res.isNotEmpty() && value.length >= 3
        }
    }

    Column {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = bgColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { onValueChange(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(hint, fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                IconButton(onClick = {
                    currentLocation?.let {
                        onValueChange("Current Location")
                        RouteUtils.reverseGeocode(it.latitude, it.longitude) { addr ->
                            if (addr != null) onValueChange(addr)
                        }
                    }
                }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.GpsFixed, "Use GPS", modifier = Modifier.size(16.dp), tint = tint)
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(16.dp), tint = Gray600)
                    }
                }
            }
        }
        AnimatedVisibility(showSuggestions, enter = expandVertically(), exit = shrinkVertically()) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                color = bgColor,
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(start = 32.dp, bottom = 4.dp)) {
                    suggestions.take(5).forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onValueChange(s.displayName); showSuggestions = false }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationOn, null, modifier = Modifier.size(14.dp), tint = Gray600)
                            Spacer(Modifier.width(6.dp))
                            Text(s.displayName, fontSize = 12.sp, maxLines = 1, color = OnSurfaceLight)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegChip(label: String, pref: RouteType, onClick: () -> Unit) {
    val (icon, color) = when (pref) {
        RouteType.DIRECT -> Icons.Outlined.Speed to AccentOrange
        RouteType.FAST -> Icons.Outlined.Navigation to BrandBlue
        RouteType.CURVY -> Icons.Outlined.Timeline to BrandBlueLight
        RouteType.EXTRA_CURVY -> Icons.Outlined.Landscape to ErrorRed
        else -> Icons.Outlined.Timeline to BrandBlue
    }
    AssistChip(
        onClick = onClick,
        label = { Text("$label  ", fontSize = 11.sp) },
        trailingIcon = { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color) }
    )
}

@Composable
private fun PreferenceDialog(current: RouteType, onSelect: (RouteType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ride Style") },
        text = {
            Column {
                listOf(
                    RouteType.DIRECT to "Direct \u2014 Straight and efficient",
                    RouteType.FAST to "Fast \u2014 Quick with some turns",
                    RouteType.CURVY to "Curvy \u2014 Scenic winding roads",
                    RouteType.EXTRA_CURVY to "Extra Curvy \u2014 Maximum twisties"
                ).forEach { (pref, desc) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(pref) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(pref == current, { onSelect(pref) }, colors = RadioButtonDefaults.colors(selectedColor = BrandBlue))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(pref.displayName, fontWeight = if (pref == current) FontWeight.Bold else FontWeight.Normal)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariantLight)
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun AvoidanceDialog(
    selected: Set<Avoidance>,
    onUpdate: (Set<Avoidance>) -> Unit,
    onDismiss: () -> Unit
) {
    var cur by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Avoidances") },
        text = {
            Column {
                Avoidance.entries.forEach { a ->
                    Row(Modifier.fillMaxWidth().clickable {
                        cur = if (cur.contains(a)) cur - a else cur + a
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(a in cur, { cur = if (it) cur + a else cur - a })
                        Text(a.displayName)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onUpdate(cur) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onUpdate(emptySet()); onDismiss() }) { Text("Clear") } }
    )
}

// ─── Route Info Card ────────────────────────────────────────────────────────

@Composable
private fun RouteInfoCard(
    routes: List<Route>,
    selectedIndex: Int,
    onSelectRoute: (Int) -> Unit,
    onBackToPlanning: () -> Unit
) {
    val context = LocalContext.current
    val route = routes.getOrNull(selectedIndex) ?: return

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Route Ready", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onBackToPlanning) {
                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
            }
            if (routes.size > 1) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    routes.forEachIndexed { i, _ ->
                        FilterChip(i == selectedIndex, { onSelectRoute(i) },
                            label = { Text(if (i == 0) "Route 1" else "Alt ${i + 1}", fontSize = 12.sp) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem(Icons.Outlined.Navigation, "Distance", "${"%.1f".format(route.distance)} km")
                StatItem(Icons.Outlined.Schedule, "Duration", "${"%.0f".format(route.duration)} min")
                StatItem(Icons.Outlined.Straighten, "Curvature", "${"%.0f".format(route.curvatureScore)}%")
                StatItem(Icons.Outlined.Terrain, "Elevation", "${"%.0f".format(route.elevationGain)} m")
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val intent = Intent(context, NavigationService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                    else context.startService(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) {
                Icon(Icons.Outlined.Route, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start Navigation", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatItem(icon: ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = BrandBlue)
        Spacer(Modifier.height(2.dp))
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariantLight)
    }
}

// ─── Routing Functions ──────────────────────────────────────────────────────

private fun generateRoundTrip(
    context: android.content.Context, routeService: RouteService,
    center: GeoPoint, targetDistanceKm: Double, directionDeg: Int,
    preference: RouteType, avoidances: Set<Avoidance>,
    onRoutesReady: (List<Route>) -> Unit
) {
    val radiusKm = targetDistanceKm / 7.0
    val start = Waypoint("Start", center)
    val isCardinal = directionDeg % 90 == 0
    val spread = if (isCardinal) 120.0 else 90.0
    val halfSpread = spread / 2.0
    val dir = directionDeg.toDouble()

    val angles = listOf(dir - halfSpread, dir, dir + halfSpread)
    val intermediatePoints = angles.map { angle ->
        val rad = Math.toRadians(angle)
        val cosLat = Math.cos(Math.toRadians(center.latitude))
        val latOff = (radiusKm / 111.32) * Math.cos(rad)
        val lonOff = (radiusKm / (111.32 * cosLat)) * Math.sin(rad)
        Waypoint("Via", GeoPoint(center.latitude + latOff, center.longitude + lonOff))
    }

    routeService.calculateRouteAsync(start, start, intermediatePoints, preference, avoidances,
        object : RouteService.RouteCalculationCallback {
            override fun onRouteCalculated(routes: List<Route>) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onRoutesReady(routes)
                    Toast.makeText(context, "Round trip: ${"%.1f".format(routes.firstOrNull()?.distance ?: 0.0)} km", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(error: String) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onRoutesReady(emptyList())
                    Toast.makeText(context, "Failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    )
}

private fun planRoute(
    context: android.content.Context, routeService: RouteService, mapRenderer: MotorcycleMapRenderer, mapView: MapView?,
    startName: String, endName: String, intermediateNames: List<String>,
    overallPreference: RouteType, avoidances: Set<Avoidance>, currentLocation: GeoPoint?,
    onRoutesReady: (List<Route>) -> Unit
) {
    val allNames = mutableListOf<String>()
    if (startName.isNotBlank()) allNames.add(startName)
    allNames.addAll(intermediateNames.filter { it.isNotBlank() })
    if (endName.isNotBlank()) allNames.add(endName)

    if (allNames.size < 2) {
        currentLocation?.let { loc ->
            routeService.calculateRouteAsync(
                Waypoint("Start", loc), Waypoint("End", GeoPoint(loc.latitude + 0.01, loc.longitude + 0.01)),
                emptyList(), overallPreference, avoidances,
                callback(routeService, mapRenderer, mapView, onRoutesReady)
            )
        } ?: run { onRoutesReady(emptyList()) }
        return
    }

    val results = mutableListOf<GeoPoint?>()
    val pending = AtomicInteger(allNames.size)

    allNames.forEachIndexed { index, name ->
        if (name.startsWith("Current Location")) {
            addResult(results, index, currentLocation ?: GeoPoint(51.5074, -0.1278))
            if (pending.decrementAndGet() == 0) routeFromResults(context, routeService, mapRenderer, mapView, allNames, results.filterNotNull(), overallPreference, avoidances, onRoutesReady)
        } else {
            RouteUtils.geocodeLocation(name, object : RouteUtils.GeocodingCallback {
                override fun onResult(geoPoint: GeoPoint) {
                    addResult(results, index, geoPoint)
                    if (pending.decrementAndGet() == 0) routeFromResults(context, routeService, mapRenderer, mapView, allNames, results.filterNotNull(), overallPreference, avoidances, onRoutesReady)
                }
                override fun onError(error: String) {
                    val fallback = currentLocation ?: GeoPoint(51.5074, -0.1278)
                    addResult(results, index, GeoPoint(fallback.latitude + (index + 1) * 0.02, fallback.longitude + (index + 1) * 0.02))
                    if (pending.decrementAndGet() == 0) routeFromResults(context, routeService, mapRenderer, mapView, allNames, results.filterNotNull(), overallPreference, avoidances, onRoutesReady)
                }
            })
        }
    }
}

private fun addResult(results: MutableList<GeoPoint?>, index: Int, point: GeoPoint) {
    synchronized(results) { while (results.size <= index) results.add(null); results[index] = point }
}

private fun routeFromResults(
    context: android.content.Context, routeService: RouteService, mapRenderer: MotorcycleMapRenderer, mapView: MapView?,
    names: List<String>, points: List<GeoPoint>, pref: RouteType, avoid: Set<Avoidance>,
    onRoutesReady: (List<Route>) -> Unit
) {
    val start = Waypoint(names.first(), points.first())
    val end = Waypoint(names.last(), points.last())
    val im = if (points.size > 2) points.subList(1, points.lastIndex).mapIndexed { i, p -> Waypoint(names.getOrElse(i + 1) { "Via" }, p) } else emptyList()
    routeService.calculateRouteAsync(start, end, im, pref, avoid, callback(routeService, mapRenderer, mapView, onRoutesReady))
}

private fun callback(
    routeService: RouteService, mapRenderer: MotorcycleMapRenderer, mapView: MapView?,
    onRoutesReady: (List<Route>) -> Unit
): RouteService.RouteCalculationCallback = object : RouteService.RouteCalculationCallback {
    override fun onRouteCalculated(routes: List<Route>) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onRoutesReady(routes) }
    }
    override fun onError(error: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onRoutesReady(emptyList()) }
    }
}
