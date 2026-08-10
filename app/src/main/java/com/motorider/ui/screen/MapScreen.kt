package com.motorider.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextOverflow
import com.motorider.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import com.motorider.maps.MotorcycleMapRenderer
import com.motorider.models.Avoidance
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.navigation.NavigationCamera
import com.motorider.navigation.NavigationState
import com.motorider.navigation.NavigationUIState
import com.motorider.services.RouteService
import com.motorider.ui.component.LocationPickerDialog
import com.motorider.ui.component.RouteStop
import com.motorider.ui.component.StopKind
import com.motorider.ui.viewmodel.NavigationViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motorider.ui.component.OsmMapView
import com.motorider.ui.theme.*
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay

enum class Screen { Plan, QuickRide, Search, OfflineMaps, Navigation }

/**
 * Keeps a half-built plan across rotation and process death, coordinates included.
 *
 * A hand-rolled [listSaver] rather than `@Parcelize` because the project carries no
 * kotlin-parcelize plugin, and one saver is a smaller thing to own than a plugin.
 */
private val PlanDraftSaver = listSaver<PlanDraft, Any>(
    save = { draft ->
        buildList {
            add(draft.via.size)
            addAll(draft.start.toSavedFields())
            addAll(draft.destination.toSavedFields())
            draft.via.forEach { addAll(it.toSavedFields()) }
        }
    },
    restore = { values ->
        val viaCount = values[0] as Int
        fun stopAt(slot: Int) = stopFromSavedFields(values.subList(1 + slot * 4, 5 + slot * 4))
        PlanDraft(
            start = stopAt(0),
            destination = stopAt(1),
            via = (0 until viaCount).map { stopAt(2 + it) }
        )
    }
)

private fun RouteStop.toSavedFields(): List<Any> = listOf(
    label,
    point?.latitude ?: Double.NaN,
    point?.longitude ?: Double.NaN,
    isCurrentLocation
)

private fun stopFromSavedFields(fields: List<Any>): RouteStop {
    val lat = fields[1] as Double
    val lon = fields[2] as Double
    return RouteStop(
        label = fields[0] as String,
        point = if (lat.isNaN() || lon.isNaN()) null else GeoPoint(lat, lon),
        isCurrentLocation = fields[3] as Boolean
    )
}

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
    val snackbarHostState = remember { SnackbarHostState() }

    var routeInfoVisible by rememberSaveable { mutableStateOf(false) }
    var quickRidePanelVisible by remember { mutableStateOf(true) }
    // Lets the rider stow the planning sheet (drag its grip down / tap the peek handle)
    // to see the full map, then bring it back. Kept across config changes.
    var planPanelVisible by rememberSaveable { mutableStateOf(true) }
    var isPlanning by remember { mutableStateOf(false) }

    var planDraft by rememberSaveable(stateSaver = PlanDraftSaver) { mutableStateOf(PlanDraft()) }
    // Held at this level (not inside PlanPanel) so the rider's curvature and
    // avoidance choices survive the panel leaving composition - switching screens,
    // or planning a route and tapping Edit to come back.
    var legPrefs by remember { mutableStateOf<List<RouteType>>(listOf(RouteType.DIRECT)) }
    var avoidances by remember { mutableStateOf<Set<Avoidance>>(emptySet()) }

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
    val navLocation by navigationViewModel.locationFlow.collectAsState()

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
            snackbarHostState.showSnackbar(it)
            navigationViewModel.dismissError()
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
    //
    // Only ever asked once the ride is actually going ahead. Location is requested
    // first and can refuse the ride outright, so asking in parallel would stack a
    // second system dialog on top of the location rationale and ask about
    // notifications for a ride that is not about to start.
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!navigationViewModel.hasLocationPermission()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Both permissions go in one request: from Android 12 the system ignores a
    // request for ACCESS_FINE_LOCATION that does not also ask for COARSE, so a
    // fine-only launcher never shows the rider a dialog at all.
    val permissionDeniedMessage = stringResource(R.string.nav_permission_denied)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        navigationViewModel.dismissPermissionRationale()
        // Approximate location is not good enough to follow a road, so only a
        // precise grant counts as success.
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
            scope.launch { snackbarHostState.showSnackbar(permissionDeniedMessage) }
        } else {
            // Location just went through, so this is the first moment the
            // notification prompt is not competing with it.
            requestNotificationPermissionIfNeeded()
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

    fun startNavigation(route: Route) {
        requestNotificationPermissionIfNeeded()
        navigationViewModel.startNavigation(route)
        currentScreen = Screen.Navigation
    }

    // Every planning failure the rider can act on ends up here, so none of them can
    // be the silent no-op the old code produced when routing came back empty.
    val geocodeFailedFmt = stringResource(R.string.geocode_failed_fmt)
    val routeFailedFmt = stringResource(R.string.route_failed_fmt)
    val noFixMessage = stringResource(R.string.waiting_for_gps)
    fun handlePlanOutcome(outcome: PlanOutcome) {
        isPlanning = false

        // Planning errors explain what to do about them, so they run to several
        // lines — "no road a motorcycle can use near the destination, pick a nearby
        // road or town". The 4 seconds of SnackbarDuration.Short is not enough to
        // read that, and the rider has just been left with nothing on the map.
        fun report(message: String) {
            scope.launch {
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            }
        }

        when (outcome) {
            is PlanOutcome.Success -> {
                currentRoutes = outcome.routes
                selectedRouteIndex = 0
                routeInfoVisible = true
            }
            is PlanOutcome.GeocodeFailed -> report(geocodeFailedFmt.format(outcome.label))
            is PlanOutcome.RoutingFailed -> report(routeFailedFmt.format(outcome.message))
            PlanOutcome.NoFix -> report(noFixMessage)
        }
    }

    // While riding, draw the route navigation is actually following. After an
    // off-route detour that is no longer the route the planning screen holds, and
    // showing the old line would point the rider at a road they are not being sent
    // down.
    val startColor = MarkerStart.toArgb()
    val viaColor = MarkerVia.toArgb()
    val endColor = MarkerEnd.toArgb()
    LaunchedEffect(selectedRouteIndex, currentRoutes, navigating, navState.routeGeometry) {
        val route = currentRoutes.getOrNull(selectedRouteIndex)
        val geometry = if (navigating) navState.routeGeometry else route?.routeGeometry
        if (geometry == null) return@LaunchedEffect

        mapRenderer.renderMotorcycleRoute(mapView, geometry)

        // Stop markers belong to the plan, not the ride: while navigating the
        // heading-up camera and turn banner say where to go, and dots on the line
        // would only clutter it.
        if (!navigating && route != null) {
            val stops = route.waypoints
            mapRenderer.renderWaypointMarkers(
                mapView,
                stops.map { it.location },
                stops.map { it.name },
                stops.indices.map { i ->
                    when (i) {
                        0 -> startColor
                        stops.lastIndex -> endColor
                        else -> viaColor
                    }
                }
            )
        } else {
            mapRenderer.clearWaypointMarkers(mapView)
        }

        mapView?.invalidate()
        // NavigationMapCamera owns the map once riding; framing here would fight it.
        if (!navigating) mapRenderer.frameRoute(mapView, geometry)
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
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
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
                            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
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
        }
    ) {
        Scaffold(
            topBar = {
                // No app bar while riding. The overlay's own turn banner needs that
                // strip of screen far more than a title and a hamburger do, and
                // neither is something a rider taps at speed.
                if (!navigating) {
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
            },
            snackbarHost = {
                SnackbarHost(
                    snackbarHostState,
                    // Above the planning sheet, which owns the bottom of the screen.
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        ) { innerPadding ->
            // Scaffold's innerPadding already carries the navigation-bar inset. The
            // navigationBarsPadding() that used to sit here applied it a second
            // time, leaving a strip of bare map below every bottom sheet.
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                OsmMapView(
                    modifier = Modifier.fillMaxSize(),
                    onLocationReceived = { location -> currentLocation = location },
                    onMapViewReady = { mv -> mapView = mv },
                    onMapTapped = {
                        // Tapping the map used to toggle a flag the current screen
                        // did not read, so on the planning screen it did nothing at
                        // all. Each screen now stows its own sheet to reveal the map.
                        if (drawerState.isOpen) {
                            scope.launch { drawerState.close() }
                        } else when (currentScreen) {
                            Screen.Plan -> planPanelVisible = !planPanelVisible
                            Screen.QuickRide -> quickRidePanelVisible = !quickRidePanelVisible
                            else -> {}
                        }
                    },
                    onMapPinched = {
                        navigationCamera.onUserZoomGesture(android.os.SystemClock.elapsedRealtime())
                    },
                    // NavigationService already holds the one GPS subscription driving
                    // guidance; relaying its fixes here keeps the blue dot moving without
                    // the overlay opening a second subscription of its own.
                    isNavigating = navigating,
                    navigationFix = navLocation?.location
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
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
                        distanceUnitMiles = distanceUnitMiles,
                        onNavigate = {
                            currentRoutes.getOrNull(selectedRouteIndex)?.let(::startNavigation)
                        }
                    )
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
                                draft = planDraft,
                                onDraftChange = { planDraft = it },
                                onDismiss = { planPanelVisible = false },
                                currentLocation = currentLocation,
                                isOffline = isOffline,
                                legPrefs = legPrefs,
                                avoidances = avoidances,
                                isBusy = isPlanning,
                                onLegPrefsChange = { legPrefs = it },
                                onAvoidancesChange = { avoidances = it },
                                onPlanRoute = {
                                    isPlanning = true
                                    planRoute(
                                        context, routeService, planDraft, legPrefs,
                                        avoidances, currentLocation, ::handlePlanOutcome
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
                        visible = quickRidePanelVisible && (!routeInfoVisible || currentRoutes.isEmpty()),
                        enter = fadeIn() + slideInVertically(tween(300)) { it },
                        exit = fadeOut() + slideOutVertically(tween(300)) { it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        QuickRidePanel(
                            currentLocation = currentLocation,
                            distanceUnitMiles = distanceUnitMiles,
                            isBusy = isPlanning,
                            // Avoidances are shared with the planning sheet because
                            // they are a constraint on what the rider can ride at
                            // all, not a mood: a ticked "avoid ferries" means the
                            // same thing whichever screen produced the route. Ride
                            // style is a mood, so Quick Ride keeps its own.
                            avoidances = avoidances,
                            onAvoidancesChange = { avoidances = it },
                            onGenerateRoundTrip = { dist, dir, style ->
                                val loc = currentLocation
                                if (loc == null) {
                                    scope.launch { snackbarHostState.showSnackbar(noFixMessage) }
                                } else {
                                    isPlanning = true
                                    generateRoundTrip(
                                        context, routeService, loc, dist, dir,
                                        style, avoidances, ::handlePlanOutcome
                                    )
                                }
                            }
                        )
                    }

                    Screen.Search -> SearchPanel(
                        currentLocation = currentLocation,
                        isOffline = isOffline,
                        onFocusPlace = { label, point ->
                            // Centring alone leaves the rider guessing which of the
                            // dozen things near the middle of the screen was found.
                            mapRenderer.renderWaypointMarkers(
                                mapView, listOf(point), listOf(label), listOf(endColor)
                            )
                            mapView?.controller?.animateTo(point)
                            mapView?.controller?.setZoom(14.0)
                        },
                        onUseAsStop = { stop, asDestination ->
                            planDraft = if (asDestination) {
                                planDraft.copy(destination = stop)
                            } else {
                                planDraft.copy(via = planDraft.via + stop)
                            }
                            planPanelVisible = true
                            routeInfoVisible = false
                            currentScreen = Screen.Plan
                        }
                    )

                    Screen.OfflineMaps -> OfflineMapManagerScreen(
                        onNavigateBack = { currentScreen = Screen.Plan }
                    )

                    Screen.Navigation -> NavigationScreen(
                        navigationState = navState.state,
                        distanceRemaining = navState.distanceRemaining,
                        timeRemaining = navState.timeRemaining,
                        progress = navState.progress,
                        eta = navState.eta,
                        currentSpeed = navState.currentSpeed,
                        currentInstruction = navState.currentInstruction,
                        followOnInstruction = navState.followOnInstruction,
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
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary
        )
    )
}

// ─── Units ──────────────────────────────────────────────────────────────────

fun formatDistanceKm(km: Double, useMiles: Boolean): String =
    if (useMiles) "%.1f mi".format(km * 0.621371) else "%.1f km".format(km)

@Composable
fun formatDurationMinutes(minutes: Double): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    return if (total >= 60) {
        stringResource(R.string.duration_fmt_hour_min, total / 60, total % 60)
    } else {
        stringResource(R.string.duration_fmt_min, total)
    }
}

// ─── Quick Ride Panel ───────────────────────────────────────────────────────

@Composable
private fun BoxScope.QuickRidePanel(
    currentLocation: GeoPoint?,
    distanceUnitMiles: Boolean,
    isBusy: Boolean,
    avoidances: Set<Avoidance>,
    onAvoidancesChange: (Set<Avoidance>) -> Unit,
    onGenerateRoundTrip: (Double, Int, RouteType) -> Unit
) {
    val minKm = 10.0
    val maxKm = 500.0
    var selectedDistanceKm by rememberSaveable { mutableDoubleStateOf(30.0) }
    var selectedDirection by rememberSaveable { mutableIntStateOf(0) }
    // Curvy by default: nobody opens "generate me a loop for the afternoon" wanting
    // the most efficient way back to where they already are.
    var rideStyle by rememberSaveable { mutableStateOf(RouteType.CURVY) }
    var showAvoidanceDialog by remember { mutableStateOf(false) }

    val unitFactor = if (distanceUnitMiles) 0.621371 else 1.0
    val unitLabel = if (distanceUnitMiles) "mi" else "km"
    val displayValue = selectedDistanceKm * unitFactor
    // Both ends of the scale get converted, not just the value. The old panel
    // converted the readout but printed the raw kilometre bounds beside it, so in
    // miles the slider claimed a minimum of 10 while sitting at 6.
    val displayMin = minKm * unitFactor
    val displayMax = maxKm * unitFactor

    Surface(
        modifier = Modifier.align(Alignment.BottomCenter),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 12.dp,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                // Tall enough that the whole panel fits without scrolling on a
                // normal phone: a scroll boundary landing mid-row reads as broken,
                // and Quick Ride is a one-screen decision.
                .heightIn(max = 680.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }

            // Generate stays pinned below this scroll area, so the compass never
            // pushes the only actionable control off a short screen.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
            Text(
                stringResource(R.string.round_trip),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.round_trip_from_here),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.distance), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // A filled pill rather than orange text: the brand orange is a fill
                // colour, and the container tone carries the same accent while the
                // number itself stays fully legible.
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        "${"%.0f".format(displayValue)} $unitLabel",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Slider(
                value = selectedDistanceKm.toFloat(),
                onValueChange = { selectedDistanceKm = it.toDouble() },
                valueRange = minKm.toFloat()..maxKm.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.secondary,
                    activeTrackColor = MaterialTheme.colorScheme.secondary,
                    inactiveTrackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${"%.0f".format(displayMin)} $unitLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${"%.0f".format(displayMax)} $unitLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.direction), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.direction_fmt, stringResource(compassLabel(selectedDirection))),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(8.dp))
            CompassSelector(
                directionDegrees = selectedDirection,
                onDirectionChanged = { selectedDirection = it },
                modifier = Modifier.fillMaxWidth().height(170.dp)
            )

            // Quick Ride used to hardcode Curvy and no avoidances, so a rider who
            // had said "avoid ferries" on the planning screen got a loop that could
            // route them onto one. Same controls, same state, same promise.
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.ride_style),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            RideStyleSelector(rideStyle) { rideStyle = it }

            Spacer(Modifier.height(12.dp))
            AvoidanceSummaryRow(avoidances) { showAvoidanceDialog = true }

            Spacer(Modifier.height(16.dp))
            }

            Button(
                onClick = { onGenerateRoundTrip(selectedDistanceKm, selectedDirection, rideStyle) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                ),
                enabled = currentLocation != null && !isBusy
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.finding_route), fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (currentLocation != null) {
                            stringResource(R.string.generate_fmt, "${"%.0f".format(displayValue)} $unitLabel")
                        } else {
                            stringResource(R.string.waiting_for_gps)
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showAvoidanceDialog) {
        QuickRideAvoidanceDialog(
            avoidances,
            onUpdate = { onAvoidancesChange(it); showAvoidanceDialog = false },
            onDismiss = { showAvoidanceDialog = false }
        )
    }
}

@Composable
private fun QuickRideAvoidanceDialog(
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { cur = if (cur.contains(a)) cur - a else cur + a }
                            .defaultMinSize(minHeight = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(a in cur, { cur = if (it) cur + a else cur - a })
                        Text(a.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onUpdate(cur) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = { cur = emptySet() }) { Text(stringResource(R.string.clear)) } }
    )
}

private fun compassLabel(degrees: Int): Int {
    // Nearest of the eight points the compass dial is actually labelled with.
    val index = (((degrees % 360) + 360) % 360 + 22) / 45 % 8
    return listOf(
        R.string.compass_n, R.string.compass_ne, R.string.compass_e, R.string.compass_se,
        R.string.compass_s, R.string.compass_sw, R.string.compass_w, R.string.compass_nw
    )[index]
}

// ─── Search Panel ───────────────────────────────────────────────────────────

/**
 * Look a place up and send it straight into the plan.
 *
 * This screen was previously a text field wired to nothing: typing in it searched
 * nothing and produced nothing, while the drawer advertised it as a destination.
 * It now shares the planning sheet's picker, moves the map to whatever is found,
 * and offers the two things a rider would want next.
 */
@Composable
private fun BoxScope.SearchPanel(
    currentLocation: GeoPoint?,
    isOffline: Boolean,
    onFocusPlace: (label: String, point: GeoPoint) -> Unit,
    onUseAsStop: (RouteStop, asDestination: Boolean) -> Unit
) {
    var found by remember { mutableStateOf<RouteStop?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(12.dp)
            .fillMaxWidth(),
        onClick = { pickerOpen = true },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                found?.label ?: stringResource(R.string.search_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = if (found != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    found?.let { place ->
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            shadowElevation = 8.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    place.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onUseAsStop(place, true) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Flag, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search_set_as_destination), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onUseAsStop(place, false) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.search_add_as_stop), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { pickerOpen = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.search_change_place), maxLines = 1)
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        LocationPickerDialog(
            kind = StopKind.DESTINATION,
            index = 0,
            initialQuery = found?.label.orEmpty(),
            currentLocation = currentLocation,
            isOffline = isOffline,
            onPick = { label, point, isCurrent ->
                val stop = RouteStop(label = label, point = point, isCurrentLocation = isCurrent)
                found = stop
                point?.let { onFocusPlace(stop.label, it) }
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false }
        )
    }
}

// ─── Theme selector ─────────────────────────────────────────────────────────

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
                    activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    activeContentColor = MaterialTheme.colorScheme.primary,
                    activeBorderColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

// ─── Route Info Card ────────────────────────────────────────────────────────

/**
 * Filled with the container tone and written on in the matching `on` tone, rather
 * than drawing the message in a tint over a washed-out version of itself. The
 * warning tint is the vivid brand orange, which is a fill colour — as text on a
 * light surface it was 3.79:1.
 */
@Composable
private fun RouteWarningBanner(
    message: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        color = containerColor
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Warning, null, Modifier.size(18.dp), tint = contentColor)
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = contentColor)
        }
    }
}

@Composable
private fun RouteInfoCard(
    routes: List<Route>,
    selectedIndex: Int,
    onSelectRoute: (Int) -> Unit,
    onBackToPlanning: () -> Unit,
    distanceUnitMiles: Boolean,
    onNavigate: () -> Unit
) {
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
                    color = if (route.isEstimate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onBackToPlanning) {
                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.edit))
                }
            }
            if (route.isEstimate) {
                // Not a real route: straight lines between waypoints. Say so loudly.
                RouteWarningBanner(
                    stringResource(R.string.route_estimate_warning),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            // A real route, but with a caveat the rider needs before setting off.
            if (!route.isEstimate && !route.avoidancesHonoured) {
                RouteWarningBanner(
                    stringResource(R.string.avoidances_not_honoured_warning),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            if (!route.isEstimate && !route.curvatureAvailable) {
                RouteWarningBanner(
                    stringResource(R.string.curvature_unavailable_warning),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            // Alternatives were labelled "Route 1", "Alt 2" — nothing to choose
            // between. Each chip now states what makes that option different.
            if (routes.size > 1) {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    routes.forEachIndexed { i, alternative ->
                        val duration = formatDurationMinutes(alternative.duration)
                        FilterChip(
                            selected = i == selectedIndex,
                            onClick = { onSelectRoute(i) },
                            label = {
                                Text(
                                    "${formatDistanceKm(alternative.distance, distanceUnitMiles)} · $duration",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem(
                    Icons.Outlined.Navigation,
                    stringResource(R.string.distance_label),
                    formatDistanceKm(route.distance, distanceUnitMiles)
                )
                StatItem(
                    Icons.Outlined.Schedule,
                    stringResource(R.string.duration_label),
                    formatDurationMinutes(route.duration)
                )
                StatItem(
                    Icons.Outlined.Timeline,
                    stringResource(R.string.curvature_label),
                    // No curvature data — or no real route at all — means no curves
                    // figure. Printing "0.0" right beside a banner saying this is a
                    // straight-line estimate invites reading it as "flat road".
                    if (route.curvatureAvailable && !route.isEstimate) {
                        "%.1f".format(route.curvatureScore)
                    } else {
                        stringResource(R.string.none_value)
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onNavigate,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
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
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
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
        animationSpec = tween(150),
        label = "compass"
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
    val needleColor = MaterialTheme.colorScheme.secondary

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

            drawCircle(
                color = outerRingColor,
                radius = radius,
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )

            drawCircle(
                color = innerRingColor,
                radius = radius * 0.55f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

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

                val labelR = radius - tickLen - 16.dp.toPx()
                val lx = center.x + labelR * sr
                val ly = center.y - labelR * cr
                labelPaint.textSize = if (isCardinal) 12.dp.toPx() else 9.dp.toPx()
                labelPaint.color = if (isCardinal) cardinalLabelColor else minorLabelColor
                labelPaint.isFakeBoldText = isCardinal
                drawContext.canvas.nativeCanvas.drawText(label, lx, ly + labelPaint.textSize / 3f, labelPaint)
            }

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
                drawPath(path = arrowPath, color = needleColor.copy(alpha = 0.25f))
                drawPath(path = arrowPath, color = needleColor, style = Stroke(width = 2.5.dp.toPx()))
            }

            drawCircle(color = needleColor, radius = 5.dp.toPx(), center = center)
        }
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
