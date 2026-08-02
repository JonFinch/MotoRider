package com.motorider.ui.screen

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorider.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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

    var routeInfoVisible by rememberSaveable { mutableStateOf(false) }
    var quickRidePanelVisible by remember { mutableStateOf(true) }
    var isGeocoding by remember { mutableStateOf(false) }

    var lastStartText by rememberSaveable { mutableStateOf("") }
    var lastEndText by rememberSaveable { mutableStateOf("") }
    var lastIntermediates by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var lastLegPrefs by rememberSaveable { mutableStateOf<List<RouteType>>(emptyList()) }
    var lastAvoidances by rememberSaveable { mutableStateOf<Set<Avoidance>>(emptySet()) }

    var distanceUnitMiles by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(currentScreen) {
        quickRidePanelVisible = true
    }

    fun formatDistance(km: Double): String {
        return if (distanceUnitMiles) "%.1f".format(km * 0.621371) + " mi"
        else "%.1f".format(km) + " km"
    }

    LaunchedEffect(selectedRouteIndex, currentRoutes) {
        val route = currentRoutes.getOrNull(selectedRouteIndex) ?: return@LaunchedEffect
        route.routeGeometry?.let { geometry ->
            mapRenderer.renderMotorcycleRoute(mapView, geometry)
            mapView?.invalidate()
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = BrandBlue,
                    modifier = Modifier
                        .padding(horizontal = 28.dp, vertical = 8.dp)
                        .clickable { scope.launch { drawerState.close() } }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DrawerItem(Screen.Plan, stringResource(R.string.drawer_plan), Icons.Outlined.EditLocation, currentScreen) {
                    currentScreen = it
                    scope.launch { drawerState.close() }
                }
                DrawerItem(Screen.QuickRide, stringResource(R.string.drawer_quick_ride), Icons.Outlined.Refresh, currentScreen) {
                    currentScreen = it
                    scope.launch { drawerState.close() }
                }
                DrawerItem(Screen.Search, stringResource(R.string.drawer_search), Icons.Outlined.Search, currentScreen) {
                    currentScreen = it
                    scope.launch { drawerState.close() }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { distanceUnitMiles = !distanceUnitMiles }
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Straighten, null, tint = OnSurfaceVariantLight, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.settings_distance_units), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceLight)
                            Text(
                                if (distanceUnitMiles) stringResource(R.string.settings_miles) else stringResource(R.string.settings_kilometres),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceVariantLight
                            )
                        }
                    }
                    Switch(
                        checked = distanceUnitMiles,
                        onCheckedChange = { distanceUnitMiles = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = BrandBlue)
                    )
                }

                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.drawer_tagline),
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
                                Screen.Plan -> stringResource(R.string.screen_plan)
                                Screen.QuickRide -> stringResource(R.string.screen_quick_ride)
                                Screen.Search -> stringResource(R.string.screen_search)
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, stringResource(R.string.menu))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                    )
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding).navigationBarsPadding()) {
                OsmMapView(
                    modifier = Modifier.fillMaxSize(),
                    onLocationReceived = { location -> currentLocation = location },
                    onMapViewReady = { mv -> mapView = mv },
                    onMapTapped = {
                        if (drawerState.isOpen) scope.launch { drawerState.close() }
                        else quickRidePanelVisible = !quickRidePanelVisible
                    }
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
                        onBackToPlanning = { routeInfoVisible = false },
                        formatDistance = ::formatDistance
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
                                Text(stringResource(R.string.finding_route), fontSize = 15.sp)
                            }
                        }
                    }
                }

                when (currentScreen) {
                    Screen.Plan -> PlanPanel(
                        currentLocation = currentLocation,
                        hasRoute = currentRoutes.isNotEmpty(),
                        initialStart = lastStartText,
                        initialEnd = lastEndText,
                        initialIntermediates = lastIntermediates,
                        onNavigate = {
                            val intent = Intent(context, NavigationService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                            else context.startService(intent)
                            Toast.makeText(context, context.getString(R.string.navigation_started), Toast.LENGTH_SHORT).show()
                        },
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
                    Screen.QuickRide -> AnimatedVisibility(
                        visible = quickRidePanelVisible,
                        enter = fadeIn() + slideInVertically(tween(300)) { it },
                        exit = fadeOut() + slideOutVertically(tween(300)) { it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        QuickRidePanel(
                            currentLocation = currentLocation,
                            hasRoute = currentRoutes.isNotEmpty(),
                            distanceUnitMiles = distanceUnitMiles,
                            onNavigate = {
                                val intent = Intent(context, NavigationService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                                else context.startService(intent)
                                Toast.makeText(context, context.getString(R.string.navigation_started), Toast.LENGTH_SHORT).show()
                            },
                            onGenerateRoundTrip = { dist, dir ->
                            currentLocation?.let { loc ->
                                isGeocoding = true
                                generateRoundTrip(
                                    context, routeService, loc, dist, dir,
                                    RouteType.CURVY, emptySet(),
                                    { routes ->
                                        currentRoutes = routes
                                        selectedRouteIndex = 0
                                        routeInfoVisible = true
                                        isGeocoding = false
                                    },
                                    ::formatDistance
                                )
                            } ?: Toast.makeText(context, context.getString(R.string.waiting_for_gps), Toast.LENGTH_SHORT).show()
                        }
                    )
                    }
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
    hasRoute: Boolean,
    initialStart: String,
    initialEnd: String,
    initialIntermediates: List<String>,
    onNavigate: () -> Unit,
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
                LegChip(stringResource(R.string.leg_fmt, 1), legPrefs.getOrElse(0) { RouteType.CURVY }) {
                    editingLegIndex = 0; showPreferenceDialog = true
                }
                intermediates.forEachIndexed { i, _ ->
                    LegChip(stringResource(R.string.leg_fmt, i + 2), legPrefs.getOrElse(i + 1) { RouteType.CURVY }) {
                        editingLegIndex = i + 1; showPreferenceDialog = true
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            WaypointField(
                icon = Icons.Default.Home, tint = BrandBlue,
                hint = stringResource(R.string.start_location_hint), value = startText,
                onValueChange = { startText = it },
                bgColor = StartLocationBgLight,
                currentLocation = currentLocation
            )

            intermediates.forEachIndexed { i, wp ->
                Spacer(Modifier.height(2.dp))
                WaypointField(
                    icon = Icons.Default.MyLocation, tint = AccentOrange,
                    hint = stringResource(R.string.via_point_hint, i + 1), value = wp,
                    onValueChange = { value -> intermediates = intermediates.toMutableList().also { it[i] = value } },
                    bgColor = ViaLocationBgLight,
                    currentLocation = currentLocation,
                    onRemove = { intermediates = intermediates.toMutableList().also { it.removeAt(i) } }
                )
            }

            Spacer(Modifier.height(2.dp))
            WaypointField(
                icon = Icons.Default.Flag, tint = ErrorRed,
                hint = stringResource(R.string.end_location_hint), value = endText,
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
                    label = { Text(stringResource(R.string.curvature)) },
                    leadingIcon = { Icon(imageVector = Icons.Outlined.Timeline, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = { showAvoidanceDialog = true },
                    label = { Text(if (selectedAvoidances.isEmpty()) stringResource(R.string.avoidances) else stringResource(R.string.avoidances_fmt, selectedAvoidances.size)) },
                    leadingIcon = { Icon(imageVector = Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                IconButton(onClick = { intermediates = intermediates + "" }) {
                    Icon(Icons.Default.Add, null, tint = BrandBlue)
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
                    Text(stringResource(R.string.go), fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onNavigate,
                enabled = hasRoute,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.Route, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_ride), fontWeight = FontWeight.SemiBold)
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
    hasRoute: Boolean,
    distanceUnitMiles: Boolean,
    onNavigate: () -> Unit,
    onGenerateRoundTrip: (Double, Int) -> Unit
) {
    val maxKm = 500.0
    var selectedDistanceKm by rememberSaveable { mutableDoubleStateOf(30.0) }
    var selectedDirection by rememberSaveable { mutableIntStateOf(0) }

    val displayValue: Double
    val displayMax: Double
    val unitLabel: String
    if (distanceUnitMiles) {
        displayValue = selectedDistanceKm * 0.621371
        displayMax = maxKm * 0.621371
        unitLabel = "mi"
    } else {
        displayValue = selectedDistanceKm
        displayMax = maxKm
        unitLabel = "km"
    }

    Surface(
        modifier = Modifier.align(Alignment.BottomCenter),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(stringResource(R.string.round_trip), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.distance), style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariantLight)
                Text(
                    "${"%.0f".format(displayValue)} $unitLabel",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = AccentOrange
                )
            }
            Spacer(Modifier.height(4.dp))
            Slider(
                value = selectedDistanceKm.toFloat(),
                onValueChange = { selectedDistanceKm = it.toDouble() },
                valueRange = 10f..maxKm.toFloat(),
                onValueChangeFinished = {},
                colors = SliderDefaults.colors(
                    thumbColor = AccentOrange,
                    activeTrackColor = AccentOrange,
                    inactiveTrackColor = AccentOrange.copy(alpha = 0.2f)
                ),
                steps = 0
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("10", fontSize = 11.sp, color = Gray400)
                Text("${"%.0f".format(displayMax)}", fontSize = 11.sp, color = Gray400)
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.direction), style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariantLight)
            Spacer(Modifier.height(8.dp))
            CompassSelector(
                directionDegrees = selectedDirection,
                onDirectionChanged = { selectedDirection = it },
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onNavigate,
                enabled = hasRoute,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.Route, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_ride), fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onGenerateRoundTrip(selectedDistanceKm, selectedDirection) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                enabled = currentLocation != null
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (currentLocation != null) stringResource(R.string.generate_fmt, "${"%.0f".format(displayValue)} $unitLabel") else stringResource(R.string.waiting_for_gps))
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
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
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
    val currentLocationLabel = stringResource(R.string.current_location)
    var suggestions by remember { mutableStateOf<List<RouteUtils.LocationSuggestion>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var searchGen by remember { mutableIntStateOf(0) }
    var isFocused by remember { mutableStateOf(false) }
    var dismissJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(value) {
        showSuggestions = false
        dismissJob?.cancel()
        if (value.length < 3) return@LaunchedEffect
        val gen = ++searchGen
        delay(1000)
        if (gen != searchGen) return@LaunchedEffect
        RouteUtils.searchLocations(value, currentLocation?.latitude, currentLocation?.longitude) { res ->
            suggestions = res
            showSuggestions = res.isNotEmpty() && value.length >= 3
            if (showSuggestions) {
                dismissJob = scope.launch {
                    delay(4000)
                    showSuggestions = false
                }
            }
        }
    }

    LaunchedEffect(isFocused) {
        if (!isFocused) {
            delay(200)
            showSuggestions = false
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
                    modifier = Modifier.weight(1f).onFocusChanged { isFocused = it.isFocused },
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
                        onValueChange(currentLocationLabel)
                        RouteUtils.reverseGeocode(it.latitude, it.longitude) { addr ->
                            if (addr != null) onValueChange(addr)
                        }
                    }
                }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.GpsFixed, stringResource(R.string.use_gps), modifier = Modifier.size(16.dp), tint = tint)
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.remove), modifier = Modifier.size(16.dp), tint = Gray600)
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
        label = { Text(label, fontSize = 11.sp) },
        trailingIcon = { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color) }
    )
}

@Composable
private fun PreferenceDialog(current: RouteType, onSelect: (RouteType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ride_style)) },
        text = {
            Column {
                val prefs = listOf(
                    RouteType.DIRECT to R.string.direct_desc,
                    RouteType.FAST to R.string.fast_desc,
                    RouteType.CURVY to R.string.curvy_desc,
                    RouteType.EXTRA_CURVY to R.string.extra_curvy_desc
                )
                prefs.forEach { (pref, descRes) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(pref) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(pref == current, { onSelect(pref) }, colors = RadioButtonDefaults.colors(selectedColor = BrandBlue))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(pref.displayName, fontWeight = if (pref == current) FontWeight.Bold else FontWeight.Normal)
                            Text(stringResource(descRes), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariantLight)
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
        title = { Text(stringResource(R.string.avoidances_title)) },
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
        confirmButton = { TextButton(onClick = { onUpdate(cur) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = { onUpdate(emptySet()); onDismiss() }) { Text(stringResource(R.string.clear)) } }
    )
}

// ─── Route Info Card ────────────────────────────────────────────────────────

@Composable
private fun RouteInfoCard(
    routes: List<Route>,
    selectedIndex: Int,
    onSelectRoute: (Int) -> Unit,
    onBackToPlanning: () -> Unit,
    formatDistance: (Double) -> String
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
                Text(stringResource(R.string.route_ready), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onBackToPlanning) {
                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.edit))
                }
            }
            if (routes.size > 1) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    routes.forEachIndexed { i, _ ->
                    FilterChip(
                        selected = i == selectedIndex,
                        onClick = { onSelectRoute(i) },
                        label = { Text(if (i == 0) stringResource(R.string.route_1) else stringResource(R.string.alt_fmt, i + 1), fontSize = 12.sp) }
                    )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem(Icons.Outlined.Navigation, stringResource(R.string.distance_label), formatDistance(route.distance))
                StatItem(Icons.Outlined.Schedule, stringResource(R.string.duration_label), "${"%.0f".format(route.duration)} min")
                StatItem(Icons.Outlined.Straighten, stringResource(R.string.curvature_label), "${"%.0f".format(route.curvatureScore)}%")
                StatItem(Icons.Outlined.Terrain, stringResource(R.string.elevation_label), stringResource(R.string.elevation_fmt, "${"%.0f".format(route.elevationGain)}"))
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
                Text(stringResource(R.string.start_navigation_label), fontWeight = FontWeight.SemiBold)
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

@Composable
private fun CompassSelector(
    directionDegrees: Int,
    onDirectionChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedAngle by animateFloatAsState(
        targetValue = directionDegrees.toFloat(),
        animationSpec = tween(150)
    )

    val compassLabels = listOf(
        "N" to 0f, "NE" to 45f, "E" to 90f, "SE" to 135f,
        "S" to 180f, "SW" to 225f, "W" to 270f, "NW" to 315f
    )

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, _ ->
                val cx = size.width / 2f
                val cy = size.height / 2f
                val dx = change.position.x - cx
                val dy = change.position.y - cy
                val angleRad = atan2(dx.toDouble(), -dy.toDouble())
                var deg = Math.toDegrees(angleRad)
                if (deg < 0) deg += 360.0
                onDirectionChanged(deg.toInt())
            }
        }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = (size.minDimension / 2f) * 0.82f
            val center = Offset(size.width / 2f, size.height / 2f)
            val labelPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }

            // Outer ring
            drawCircle(
                color = Gray300,
                radius = radius,
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )

            // Inner ring (subtle)
            drawCircle(
                color = Gray200,
                radius = radius * 0.55f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // Tick marks and labels
            compassLabels.forEach { (label, deg) ->
                val rad = Math.toRadians(deg.toDouble())
                val tickLen = if (deg % 90f == 0f) 14.dp.toPx() else 8.dp.toPx()
                val innerR = radius - tickLen - 1.dp.toPx()
                val outerR = radius - 1.dp.toPx()
                val sr = sin(rad).toFloat()
                val cr = cos(rad).toFloat()
                val isCardinal = deg % 90f == 0f

                drawLine(
                    color = if (isCardinal) Gray600 else Gray400,
                    start = Offset(center.x + innerR * sr, center.y - innerR * cr),
                    end = Offset(center.x + outerR * sr, center.y - outerR * cr),
                    strokeWidth = if (isCardinal) 2.5.dp.toPx() else 1.5.dp.toPx()
                )

                // Draw label text on canvas
                val labelR = radius - tickLen - 16.dp.toPx()
                val lx = center.x + labelR * sr
                val ly = center.y - labelR * cr
                labelPaint.textSize = if (isCardinal) 12.dp.toPx() else 9.dp.toPx()
                labelPaint.color = if (isCardinal) 0xFF424242.toInt() else 0xFF868E96.toInt()
                labelPaint.isFakeBoldText = isCardinal
                drawContext.canvas.nativeCanvas.drawText(label, lx, ly + labelPaint.textSize / 3f, labelPaint)
            }

            // V-shaped direction indicator
            rotate(animatedAngle, center) {
                val arrowH = radius * 0.38f
                val arrowBaseY = center.y - radius * 0.44f
                val halfW = arrowH * 0.45f
                val arrowPath = Path().apply {
                    moveTo(center.x, arrowBaseY - arrowH)
                    lineTo(center.x - halfW, arrowBaseY)
                    lineTo(center.x + halfW, arrowBaseY)
                    close()
                }
                drawPath(path = arrowPath, color = AccentOrange.copy(alpha = 0.2f))
                drawPath(path = arrowPath, color = AccentOrange, style = Stroke(width = 2.dp.toPx()))
            }

            // Center dot
            drawCircle(color = AccentOrange, radius = 5.dp.toPx(), center = center)
        }
    }
}

// ─── Routing Functions ──────────────────────────────────────────────────────

private fun generateRoundTrip(
    context: android.content.Context, routeService: RouteService,
    center: GeoPoint, targetDistanceKm: Double, directionDeg: Int,
    preference: RouteType, avoidances: Set<Avoidance>,
    onRoutesReady: (List<Route>) -> Unit, formatDistance: (Double) -> String
) {
    val radiusKm = targetDistanceKm / 7.0
    val start = Waypoint(context.getString(R.string.waypoint_start), center)
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
        Waypoint(context.getString(R.string.waypoint_via), GeoPoint(center.latitude + latOff, center.longitude + lonOff))
    }

    routeService.calculateRouteAsync(start, start, intermediatePoints, preference, avoidances,
        object : RouteService.RouteCalculationCallback {
            override fun onRouteCalculated(routes: List<Route>) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onRoutesReady(routes)
                    Toast.makeText(context, context.getString(R.string.round_trip_fmt, formatDistance(routes.firstOrNull()?.distance ?: 0.0)), Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(error: String) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onRoutesReady(emptyList())
                    Toast.makeText(context, context.getString(R.string.round_trip_failed, error), Toast.LENGTH_LONG).show()
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
                Waypoint(context.getString(R.string.waypoint_start), loc), Waypoint(context.getString(R.string.waypoint_end), GeoPoint(loc.latitude + 0.01, loc.longitude + 0.01)),
                emptyList(), overallPreference, avoidances,
                callback(routeService, mapRenderer, mapView, onRoutesReady)
            )
        } ?: run { onRoutesReady(emptyList()) }
        return
    }

    val results = mutableListOf<GeoPoint?>()
    val pending = AtomicInteger(allNames.size)

    allNames.forEachIndexed { index, name ->
        if (name.startsWith(context.getString(R.string.current_location))) {
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
    val im = if (points.size > 2) points.subList(1, points.lastIndex).mapIndexed { i, p -> Waypoint(names.getOrElse(i + 1) { context.getString(R.string.waypoint_via) }, p) } else emptyList()
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
