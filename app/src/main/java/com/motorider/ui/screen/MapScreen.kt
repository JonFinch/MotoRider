package com.motorider.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.motorider.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import com.motorider.maps.MotorcycleMapRenderer
import com.motorider.models.Avoidance
import com.motorider.models.ManeuverType
import com.motorider.models.NavigationWarning
import com.motorider.models.WarningSeverity
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.models.TurnInstruction
import com.motorider.models.Waypoint
import com.motorider.navigation.NavigationCamera
import com.motorider.navigation.NavigationState
import com.motorider.navigation.NavigationUIState
import com.motorider.services.RouteService
import com.motorider.ui.viewmodel.NavigationViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motorider.ui.component.OsmMapView
import com.motorider.ui.theme.*
import com.motorider.utils.RouteUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import java.util.concurrent.atomic.AtomicInteger

enum class Screen { Plan, QuickRide, Search, OfflineMaps, Navigation }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {}
) {
    val context = LocalContext.current

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var currentLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var currentRoutes by remember { mutableStateOf<List<Route>>(emptyList()) }
    var selectedRouteIndex by remember { mutableIntStateOf(0) }

    val mapRenderer = remember { MotorcycleMapRenderer() }
    val routeService = remember { RouteService() }
    // Owned by the ViewModelStore, not by this composition: a ViewModel built with
    // `remember` is never cleared, so its TTS engine and service binding would leak.
    val navigationViewModel: NavigationViewModel = viewModel(
        factory = NavigationViewModel.Factory(context.applicationContext)
    )

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var currentScreen by remember { mutableStateOf(Screen.Plan) }
    val scope = rememberCoroutineScope()

    var routeInfoVisible by rememberSaveable { mutableStateOf(false) }
    var quickRidePanelVisible by remember { mutableStateOf(true) }
    // Lets the rider stow the planning sheet (drag its grip down / tap the peek handle)
    // to see the full map, then bring it back. Kept across config changes.
    var planPanelVisible by rememberSaveable { mutableStateOf(true) }
    var isGeocoding by remember { mutableStateOf(false) }

    var lastStartText by rememberSaveable { mutableStateOf("") }
    var lastEndText by rememberSaveable { mutableStateOf("") }
    var lastIntermediates by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    // Held at this level (not inside PlanPanel) so the rider's curvature and
    // avoidance choices survive the panel leaving composition - switching screens,
    // or planning a route and tapping Edit to come back. Defaults to a single
    // Direct leg.
    var lastLegPrefs by remember { mutableStateOf<List<RouteType>>(listOf(RouteType.DIRECT)) }
    var lastAvoidances by remember { mutableStateOf<Set<Avoidance>>(emptySet()) }

    var distanceUnitMiles by rememberSaveable { mutableStateOf(true) }

    // Tracked so the rider knows they're seeing cached map tiles and that routing
    // calls will fail - not tied to offline routing (that's a much bigger, separate
    // undertaking), just an honest indicator of current connectivity.
    var isOffline by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        fun refreshOfflineState() {
            val caps = connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            isOffline = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        refreshOfflineState()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshOfflineState()
            override fun onLost(network: Network) = refreshOfflineState()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshOfflineState()
        }
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        onDispose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    LaunchedEffect(currentScreen) {
        quickRidePanelVisible = true
    }

    val navState by navigationViewModel.uiState.collectAsState()
    val navError by navigationViewModel.errorMessage.collectAsState()

    // Hold the display awake for the whole ride. The service's PARTIAL_WAKE_LOCK only
    // keeps the CPU running so fixes keep arriving — it does nothing for the screen,
    // and a rider glancing down at a blank phone mid-junction is the failure this
    // avoids. Gloved hands cannot reliably wake a screen either.
    //
    // Scoped to NAVIGATING alone: pausing is a deliberate "I've stopped" signal, and
    // arriving ends the need, so both should let the display sleep normally again.
    val view = LocalView.current
    DisposableEffect(navState.state) {
        view.keepScreenOn = navState.state == NavigationState.NAVIGATING
        onDispose { view.keepScreenOn = false }
    }

    val showPermissionRationale by navigationViewModel.showPermissionRationale.collectAsState()

    LaunchedEffect(navError) {
        navError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            navigationViewModel.dismissError()
        }
    }

    // Both permissions go in one request: from Android 12 the system ignores a
    // request for ACCESS_FINE_LOCATION that does not also ask for COARSE, so a
    // fine-only launcher never shows the rider a dialog at all.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        navigationViewModel.dismissPermissionRationale()
        // Approximate location is not good enough to follow a road, so only a
        // precise grant counts as success.
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
            Toast.makeText(context, context.getString(R.string.nav_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    // Unlike location, notifications are a nicety - the lock-screen turn prompt and
    // Pause/End actions - so a denial is silently accepted rather than gating the
    // ride or showing a rationale dialog.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: navigation proceeds the same whether this is granted or denied */ }

    // POST_NOTIFICATIONS is only a runtime permission from Android 13 (TIRAMISU) on;
    // on older versions it's granted at install time and requesting it is a no-op
    // that risks confusing the rider with a needless system dialog.
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Asking only when the rider actually taps "Start navigation" keeps the request
    // attached to a reason they can see.
    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { navigationViewModel.dismissPermissionRationale() },
            title = { Text(stringResource(R.string.screen_navigation)) },
            text = { Text(stringResource(R.string.nav_navigation_permission_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }) { Text(stringResource(R.string.nav_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { navigationViewModel.dismissPermissionRationale() }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // One definition of "navigating" for the whole screen: anything that floats
    // over the map has to defer to the navigation overlay, and reading the same
    // flag keeps them from drifting apart.
    val navigating = currentScreen == Screen.Navigation

    val navigationCamera = remember { NavigationCamera() }
    NavigationMapCamera(
        mapView = mapView,
        state = navState,
        active = navigating,
        camera = navigationCamera
    )

    // Leaving the navigation screen by any route must also stop the GPS service.
    fun stopNavigating() {
        navigationViewModel.endNavigation()
        currentScreen = Screen.Plan
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

    // OSM's default tiles are bright white - a real glare hazard on a handlebar-mounted
    // phone at night. INVERT_COLORS is osmdroid's own built-in filter, documented in
    // its source as existing specifically to support night mode.
    val isDarkTheme = LocalIsDarkTheme.current
    LaunchedEffect(isDarkTheme, mapView) {
        mapView?.mapOverlay?.setColorFilter(if (isDarkTheme) TilesOverlay.INVERT_COLORS else null)
        mapView?.invalidate()
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
                DrawerItem(Screen.OfflineMaps, stringResource(R.string.offline_maps), Icons.Outlined.Download, currentScreen) {
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
                        Icon(Icons.Outlined.Straighten, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.settings_distance_units), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                if (distanceUnitMiles) stringResource(R.string.settings_miles) else stringResource(R.string.settings_kilometres),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = distanceUnitMiles,
                        onCheckedChange = { distanceUnitMiles = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = BrandBlue)
                    )
                }

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.DarkMode, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.settings_theme),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ThemeModeSelector(themeMode, onThemeModeChange)
                }

                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.drawer_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                Screen.OfflineMaps -> stringResource(R.string.offline_maps)
                                Screen.Navigation -> stringResource(R.string.screen_navigation)
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
                    },
                    onMapPinched = {
                        navigationCamera.onUserZoomGesture(android.os.SystemClock.elapsedRealtime())
                    }
                )

                // These two sit outside the screen `when` so they can float over the
                // map, which means nothing hides them on their own. The navigation
                // overlay is transparent by design, so each has to opt out of it:
                // the offline notice would land on top of the turn banner, and the
                // route card would show through beneath the navigation controls.
                // Connectivity still reaches the rider mid-ride - NavigationScreen
                // shows it alongside the other navigation warnings.
                AnimatedVisibility(
                    visible = isOffline && !navigating,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    RouteWarningBanner(
                        stringResource(R.string.offline_indicator),
                        AccentOrange,
                        modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp)
                    )
                }

                AnimatedVisibility(
                    visible = routeInfoVisible && currentRoutes.isNotEmpty() && !navigating,
                    enter = slideInVertically(tween(300)) { it },
                    exit = slideOutVertically(tween(300)) { it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    RouteInfoCard(
                        routes = currentRoutes,
                        selectedIndex = selectedRouteIndex,
                        onSelectRoute = { selectedRouteIndex = it },
                        onBackToPlanning = { routeInfoVisible = false },
                        formatDistance = ::formatDistance,
                        onNavigate = {
                            val selectedRoute = currentRoutes.getOrNull(selectedRouteIndex)
                            if (selectedRoute != null) {
                                requestNotificationPermissionIfNeeded()
                                navigationViewModel.startNavigation(selectedRoute)
                                currentScreen = Screen.Navigation
                            }
                        }
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
                    Screen.Plan -> {
                    val planSlotActive = !routeInfoVisible || currentRoutes.isEmpty()
                    AnimatedVisibility(
                        visible = planSlotActive && planPanelVisible,
                        enter = fadeIn() + slideInVertically(tween(300)) { it },
                        exit = fadeOut() + slideOutVertically(tween(300)) { it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        PlanPanel(
                        onDismiss = { planPanelVisible = false },
                        currentLocation = currentLocation,
                        initialStart = lastStartText,
                        initialEnd = lastEndText,
                        initialIntermediates = lastIntermediates,
                        initialLegPrefs = lastLegPrefs,
                        initialAvoidances = lastAvoidances,
                        isBusy = isGeocoding,
                        onLegPrefsChange = { lastLegPrefs = it },
                        onAvoidancesChange = { lastAvoidances = it },
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
                                 legPrefs.firstOrNull() ?: RouteType.DIRECT,
                                 avoidances, currentLocation,
                                 onRoutesReady = { routes ->
                                     currentRoutes = routes
                                     selectedRouteIndex = 0
                                     routeInfoVisible = true
                                     isGeocoding = false
                                 },
                                 onNavigate = { route ->
                                     requestNotificationPermissionIfNeeded()
                                     navigationViewModel.startNavigation(route)
                                     currentScreen = Screen.Navigation
                                 },
                                 currentRoutes = currentRoutes,
                                 selectedRouteIndex = selectedRouteIndex
                             )
                        }
                    )
                    }

                    // Peek handle: appears in the same bottom slot once the sheet is
                    // stowed, so the rider always has an obvious way to bring it back.
                    AnimatedVisibility(
                        visible = planSlotActive && !planPanelVisible,
                        enter = fadeIn() + slideInVertically(tween(300)) { it },
                        exit = fadeOut() + slideOutVertically(tween(300)) { it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        PlanPeekHandle(onReveal = { planPanelVisible = true })
                    }
                    }
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
                                 val selectedRoute = currentRoutes.getOrNull(selectedRouteIndex)
                                 if (selectedRoute != null) {
                                    requestNotificationPermissionIfNeeded()
                                    navigationViewModel.startNavigation(selectedRoute)
                                    currentScreen = Screen.Navigation
                                } else {
                                    Toast.makeText(context, context.getString(R.string.waiting_for_gps), Toast.LENGTH_SHORT).show()
                                }
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
                    Screen.OfflineMaps -> OfflineMapManagerScreen(
                        onNavigateBack = { currentScreen = Screen.Plan }
                    )
                    Screen.Navigation -> NavigationScreen(
                        navigationState = navState.state,
                        distanceRemaining = navState.distanceRemaining,
                        timeRemaining = navState.timeRemaining,
                        eta = navState.eta,
                        currentSpeed = navState.currentSpeed,
                        currentInstruction = navState.currentInstruction,
                        navigationWarnings = navState.warnings,
                        isOffRoute = navState.isOffRoute,
                        isGpsLost = navState.isGpsLost,
                        isRecalculating = navState.isRecalculating,
                        isOffline = isOffline,
                        isTtsEnabled = navState.isTtsEnabled,
                        skipAvailableWaypointName = navState.skipAvailableWaypointName,
                        onToggleTts = { navigationViewModel.setTTSEnabled(!navState.isTtsEnabled) },
                        onBackToPlanning = { stopNavigating() },
                        onPauseNavigation = {
                            when (navState.state) {
                                NavigationState.NAVIGATING -> navigationViewModel.pauseNavigation()
                                NavigationState.PAUSED -> navigationViewModel.resumeNavigation()
                                else -> {}
                            }
                        },
                        onEndNavigation = { stopNavigating() },
                        onSkipWaypoint = { navigationViewModel.skipWaypoint() },
                        distanceUnitMiles = distanceUnitMiles
                    )
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
    onDismiss: () -> Unit,
    currentLocation: GeoPoint?,
    initialStart: String,
    initialEnd: String,
    initialIntermediates: List<String>,
    initialLegPrefs: List<RouteType>,
    initialAvoidances: Set<Avoidance>,
    isBusy: Boolean,
    onLegPrefsChange: (List<RouteType>) -> Unit,
    onAvoidancesChange: (Set<Avoidance>) -> Unit,
    onPlanRoute: (String, String, List<String>, List<RouteType>, Set<Avoidance>) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var startText by remember { mutableStateOf(initialStart) }
    var endText by remember { mutableStateOf(initialEnd) }
    var intermediates by remember { mutableStateOf(initialIntermediates) }
    // Seeded from the hoisted session state so previously-chosen avoidances and
    // per-leg curvature reappear when the panel is reopened. Changes are pushed
    // back up immediately (not just on Go) via the on*Change callbacks.
    var selectedAvoidances by remember { mutableStateOf(initialAvoidances) }
    var showPreferenceDialog by remember { mutableStateOf(false) }
    var showAvoidanceDialog by remember { mutableStateOf(false) }
    var editingLegIndex by remember { mutableStateOf(0) }

    val legPrefs = remember {
        mutableStateListOf<RouteType>().apply {
            addAll(initialLegPrefs.ifEmpty { listOf(RouteType.DIRECT) })
        }
    }
    LaunchedEffect(intermediates.size) {
        val needed = 1 + intermediates.size
        var changed = false
        while (legPrefs.size < needed) { legPrefs.add(RouteType.DIRECT); changed = true }
        while (legPrefs.size > needed) { legPrefs.removeAt(legPrefs.size - 1); changed = true }
        if (changed) onLegPrefsChange(legPrefs.toList())
    }

    val canPlan = startText.isNotBlank() && endText.isNotBlank() && !isBusy

    Surface(
        modifier = Modifier.align(Alignment.BottomCenter),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 12.dp,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            // Drag-handle affordance, the standard bottom-sheet grip. Dragging it down
            // (past a small threshold) or tapping it stows the sheet; a peek handle then
            // takes its place so it can be brought back.
            Box(
                Modifier
                    .fillMaxWidth()
                    // Wider vertical padding gives the grip a comfortable touch target.
                    .padding(vertical = 6.dp)
                    .pointerInput(Unit) {
                        var dragged = 0f
                        detectVerticalDragGestures(
                            onDragEnd = { dragged = 0f },
                            onDragCancel = { dragged = 0f }
                        ) { change, dragAmount ->
                            change.consume()
                            dragged += dragAmount
                            if (dragged > 48f) {
                                onDismiss()
                                dragged = 0f
                            }
                        }
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }

            // ── Waypoints ──
            WaypointField(
                icon = Icons.Default.Home, tint = BrandBlue,
                hint = stringResource(R.string.start_location_hint), value = startText,
                onValueChange = { startText = it },
                bgColor = if (LocalIsDarkTheme.current) StartLocationBgDark else StartLocationBgLight,
                currentLocation = currentLocation
            )

            intermediates.forEachIndexed { i, wp ->
                Spacer(Modifier.height(6.dp))
                WaypointField(
                    icon = Icons.Default.MyLocation, tint = AccentOrange,
                    hint = stringResource(R.string.via_point_hint, i + 1), value = wp,
                    onValueChange = { value -> intermediates = intermediates.toMutableList().also { it[i] = value } },
                    bgColor = if (LocalIsDarkTheme.current) ViaLocationBgDark else ViaLocationBgLight,
                    currentLocation = currentLocation,
                    onRemove = { intermediates = intermediates.toMutableList().also { it.removeAt(i) } }
                )
            }

            Spacer(Modifier.height(6.dp))
            WaypointField(
                icon = Icons.Default.Flag, tint = ErrorRed,
                hint = stringResource(R.string.end_location_hint), value = endText,
                onValueChange = { endText = it },
                bgColor = if (LocalIsDarkTheme.current) EndLocationBgDark else EndLocationBgLight,
                currentLocation = currentLocation
            )

            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { intermediates = intermediates + "" },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = BrandBlue)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.add_stop), color = BrandBlue)
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(14.dp))

            // ── Ride style: one-tap inline selection, no dialog ──
            Text(
                stringResource(R.string.ride_style),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            val allLegsSame = legPrefs.distinct().size <= 1
            val shownStyle = if (allLegsSame) legPrefs.firstOrNull() else null
            RideStyleSelector(shownStyle) { style ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                for (i in legPrefs.indices) legPrefs[i] = style
                onLegPrefsChange(legPrefs.toList())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                shownStyle?.let { stringResource(rideStyleDescription(it)) }
                    ?: stringResource(R.string.mixed_leg_styles),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Per-leg overrides only appear when the trip actually has multiple legs.
            if (legPrefs.size > 1) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    legPrefs.forEachIndexed { i, pref ->
                        LegChip(stringResource(R.string.leg_fmt, i + 1), pref) {
                            editingLegIndex = i; showPreferenceDialog = true
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            AvoidanceSummaryRow(selectedAvoidances) { showAvoidanceDialog = true }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val im = intermediates.filter { it.isNotBlank() }
                    onPlanRoute(startText, endText, im, legPrefs.toList(), selectedAvoidances)
                },
                enabled = canPlan,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.finding_route), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                } else {
                    Icon(Icons.Outlined.Route, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.find_route), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }

    if (showPreferenceDialog) {
        PreferenceDialog(
            legPrefs.getOrElse(editingLegIndex) { RouteType.DIRECT },
            onSelect = {
                if (editingLegIndex in legPrefs.indices) legPrefs[editingLegIndex] = it
                onLegPrefsChange(legPrefs.toList())
                showPreferenceDialog = false
            },
            onDismiss = { showPreferenceDialog = false }
        )
    }
    if (showAvoidanceDialog) {
        AvoidanceDialog(
            selectedAvoidances,
            onUpdate = {
                selectedAvoidances = it
                onAvoidancesChange(it)
                showAvoidanceDialog = false
            },
            onDismiss = { showAvoidanceDialog = false }
        )
    }
}

/**
 * Compact handle shown in the bottom slot when the planning sheet is stowed. Tapping it
 * (or dragging up) restores the full [PlanPanel]. Deliberately small so the map stays
 * almost fully visible while it's up.
 */
@Composable
private fun BoxScope.PlanPeekHandle(onReveal: () -> Unit) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragEnd = { dragged = 0f },
                    onDragCancel = { dragged = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    dragged += dragAmount
                    if (dragged < -32f) {
                        onReveal()
                        dragged = 0f
                    }
                }
            }
            .clickable(onClick = onReveal),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 8.dp,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.show_planner),
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
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
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 12.dp,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            Text(stringResource(R.string.round_trip), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.distance), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("10", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${"%.0f".format(displayMax)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.direction), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var isSearching by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var selectedValue by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(value) {
        if (value == selectedValue || value.isEmpty()) {
            isSearching = false
            return@LaunchedEffect
        }
        showSuggestions = false
        searchJob?.cancel()
        if (value.length < 3) {
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        searchJob = scope.launch {
            delay(300)
            RouteUtils.searchLocations(value, currentLocation?.latitude, currentLocation?.longitude) { res ->
                suggestions = res
                isSearching = false
                hasError = res.isEmpty()
                if (isFocused && value.length >= 3) {
                    showSuggestions = res.isNotEmpty() || hasError
                }
            }
        }
    }

    LaunchedEffect(isFocused) {
        if (!isFocused) {
            showSuggestions = false
        } else if (value.length >= 3 && value != selectedValue && (suggestions.isNotEmpty() || hasError)) {
            showSuggestions = true
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
                    onValueChange = { newVal ->
                        selectedValue = ""
                        onValueChange(newVal)
                    },
                    modifier = Modifier.weight(1f).onFocusChanged { isFocused = it.isFocused },
                    placeholder = { Text(hint, fontSize = 15.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                if (value.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            selectedValue = ""
                            onValueChange("")
                            suggestions = emptyList()
                            showSuggestions = false
                            hasError = false
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Clear, stringResource(R.string.clear), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = {
                    currentLocation?.let {
                        selectedValue = currentLocationLabel
                        onValueChange(currentLocationLabel)
                        RouteUtils.reverseGeocode(it.latitude, it.longitude) { addr ->
                            if (addr != null) {
                                selectedValue = addr
                                onValueChange(addr)
                            }
                        }
                    }
                }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.GpsFixed, stringResource(R.string.use_gps), modifier = Modifier.size(24.dp), tint = tint)
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.remove), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        AnimatedVisibility(showSuggestions, enter = expandVertically(), exit = shrinkVertically()) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                tonalElevation = 2.dp
            ) {
                Column {
                    if (isSearching) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = BrandBlue,
                            trackColor = BrandBlue.copy(alpha = 0.1f)
                        )
                    }
                    if (hasError && suggestions.isEmpty() && !isSearching) {
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(16.dp), tint = ErrorRed)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.no_results_found), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    suggestions.take(5).forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable {
                                selectedValue = s.displayName
                                onValueChange(s.displayName)
                                showSuggestions = false
                                isSearching = false
                                hasError = false
                                searchJob?.cancel()
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationOn, null, modifier = Modifier.size(20.dp), tint = BrandBlue)
                            Spacer(Modifier.width(10.dp))
                            Text(s.displayName, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

private fun rideStyleIcon(pref: RouteType): Pair<ImageVector, Color> = when (pref) {
    RouteType.DIRECT -> Icons.Outlined.Speed to AccentOrange
    RouteType.FAST -> Icons.Outlined.Navigation to BrandBlue
    RouteType.CURVY -> Icons.Outlined.Timeline to BrandBlueLight
    RouteType.EXTRA_CURVY -> Icons.Outlined.Landscape to ErrorRed
}

private fun rideStyleDescription(pref: RouteType): Int = when (pref) {
    RouteType.DIRECT -> R.string.direct_desc
    RouteType.FAST -> R.string.fast_desc
    RouteType.CURVY -> R.string.curvy_desc
    RouteType.EXTRA_CURVY -> R.string.extra_curvy_desc
}

@Composable
private fun LegChip(label: String, pref: RouteType, onClick: () -> Unit) {
    val (icon, color) = rideStyleIcon(pref)
    AssistChip(
        onClick = onClick,
        label = { Text("$label · ${pref.shortLabel}", fontSize = 13.sp) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color) }
    )
}

/** Inline single-choice ride-style selector. Replaces the tap-open-dialog-tap-close flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RideStyleSelector(selected: RouteType?, onSelect: (RouteType) -> Unit) {
    val styles = RouteType.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        styles.forEachIndexed { index, style ->
            SegmentedButton(
                selected = style == selected,
                onClick = { onSelect(style) },
                shape = SegmentedButtonDefaults.itemShape(index, styles.size),
                // Suppress the default check icon so four segments fit on narrow phones.
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = BrandBlue.copy(alpha = 0.16f),
                    activeContentColor = BrandBlue,
                    activeBorderColor = BrandBlue
                )
            ) {
                Text(style.shortLabel, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/** Inline Light/System/Dark selector in the drawer, same control as [RideStyleSelector]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val modes = listOf(
        ThemeMode.LIGHT to R.string.theme_light,
        ThemeMode.SYSTEM to R.string.theme_system,
        ThemeMode.DARK to R.string.theme_dark
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (mode, labelRes) ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = BrandBlue.copy(alpha = 0.16f),
                    activeContentColor = BrandBlue,
                    activeBorderColor = BrandBlue
                )
            ) {
                Text(stringResource(labelRes), fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/** Full-width row summarising selected avoidances and opening the multi-select dialog. */
@Composable
private fun AvoidanceSummaryRow(selected: Set<Avoidance>, onClick: () -> Unit) {
    val summary = if (selected.isEmpty()) stringResource(R.string.avoid_none)
                  else selected.joinToString(", ") { it.displayName }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.avoid_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
                            Text(stringResource(descRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun RouteWarningBanner(
    message: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.12f)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Warning, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = tint)
        }
    }
}

@Composable
private fun RouteInfoCard(
    routes: List<Route>,
    selectedIndex: Int,
    onSelectRoute: (Int) -> Unit,
    onBackToPlanning: () -> Unit,
    formatDistance: (Double) -> String,
    onNavigate: () -> Unit
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
                Text(
                    stringResource(if (route.isEstimate) R.string.route_estimate_title else R.string.route_ready),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (route.isEstimate) ErrorRed else MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onBackToPlanning) {
                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.edit))
                }
            }
            if (route.isEstimate) {
                // Not a real route: straight lines between waypoints. Say so loudly.
                RouteWarningBanner(stringResource(R.string.route_estimate_warning), ErrorRed)
            }
            // A real route, but with a caveat the rider needs before setting off.
            if (!route.isEstimate && !route.avoidancesHonoured) {
                RouteWarningBanner(stringResource(R.string.avoidances_not_honoured_warning), AccentOrange)
            }
            if (!route.isEstimate && !route.curvatureAvailable) {
                RouteWarningBanner(stringResource(R.string.curvature_unavailable_warning), AccentOrange)
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
                StatItem(Icons.Outlined.Straighten, stringResource(R.string.curvature_label), "${"%.1f".format(route.curvatureScore)}")
                StatItem(Icons.Outlined.Star, stringResource(R.string.score_label), "${"%.2f".format(route.routeScore)}")
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onNavigate()
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
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    // Canvas's draw lambda runs outside composition, so MaterialTheme.colorScheme
    // cannot be read there directly - resolve every color here, in composable scope.
    val outerRingColor = MaterialTheme.colorScheme.outlineVariant
    val innerRingColor = MaterialTheme.colorScheme.surfaceVariant
    val cardinalTickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val minorTickColor = MaterialTheme.colorScheme.outlineVariant
    val cardinalLabelColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val minorLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

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
                color = outerRingColor,
                radius = radius,
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )

            // Inner ring (subtle)
            drawCircle(
                color = innerRingColor,
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
                    color = if (isCardinal) cardinalTickColor else minorTickColor,
                    start = Offset(center.x + innerR * sr, center.y - innerR * cr),
                    end = Offset(center.x + outerR * sr, center.y - outerR * cr),
                    strokeWidth = if (isCardinal) 2.5.dp.toPx() else 1.5.dp.toPx()
                )

                // Draw label text on canvas
                val labelR = radius - tickLen - 16.dp.toPx()
                val lx = center.x + labelR * sr
                val ly = center.y - labelR * cr
                labelPaint.textSize = if (isCardinal) 12.dp.toPx() else 9.dp.toPx()
                labelPaint.color = if (isCardinal) cardinalLabelColor else minorLabelColor
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
    onRoutesReady: (List<Route>) -> Unit,
    onNavigate: (Route) -> Unit,
    currentRoutes: List<Route>,
    selectedRouteIndex: Int
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

// ─── Navigation map camera ──────────────────────────────────────────────────

/**
 * Drives the shared map while navigating: follows the rider, turns the map so the
 * way ahead is always up, and picks the zoom from how fast they are going.
 *
 * It steers the *existing* MapView rather than creating a second one, so the route
 * polyline, tile cache and offline regions already on it carry straight into
 * navigation.
 */
@Composable
private fun NavigationMapCamera(
    mapView: MapView?,
    state: NavigationUIState,
    active: Boolean,
    camera: NavigationCamera
) {
    val navigating = active && state.state == NavigationState.NAVIGATING

    DisposableEffect(navigating) {
        if (navigating) camera.reset()
        onDispose {
            // Leave a plain north-up map behind, or the planning screen inherits
            // whatever heading the ride happened to finish on.
            if (navigating) {
                mapView?.mapOrientation = 0f
                mapView?.invalidate()
            }
        }
    }

    LaunchedEffect(mapView, navigating, state.position, state.currentSpeed, state.bearing) {
        val view = mapView ?: return@LaunchedEffect
        if (!navigating) return@LaunchedEffect
        val position = state.position ?: return@LaunchedEffect

        val target = camera.update(
            speedMps = state.currentSpeed,
            rawBearing = state.bearing,
            nowMs = android.os.SystemClock.elapsedRealtime()
        )

        // osmdroid measures orientation anticlockwise, so the heading is negated to
        // bring the direction of travel to the top of the screen.
        view.mapOrientation = -target.bearing

        // A null zoom means the rider's pinch still owns it.
        target.zoom?.let { view.controller.setZoom(it) }
        view.controller.animateTo(position)
    }
}
