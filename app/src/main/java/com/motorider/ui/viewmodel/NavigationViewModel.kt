package com.motorider.ui.viewmodel

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.motorider.models.PoiCategory
import com.motorider.models.PoiResult
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import com.motorider.navigation.NavigationManager
import com.motorider.navigation.NavigationState
import com.motorider.navigation.NavigationUIState
import com.motorider.navigation.TTSManager
import com.motorider.services.LocationResult
import com.motorider.services.NavigationService
import com.motorider.services.PoiOutcome
import com.motorider.services.PoiService
import com.motorider.services.RouteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.util.Calendar

/**
 * What the fuel/food picker is doing, if anything.
 *
 * [Empty] and [Failed] are separate states rather than an empty [Results], because
 * a rider low on fuel needs to know which of "there is nothing out here" and "the
 * search did not work" they are looking at — the first means press on, the second
 * means try again.
 */
sealed interface PoiSearchState {
    data object Closed : PoiSearchState

    data class Searching(val category: PoiCategory) : PoiSearchState

    /** [onRoute] false means nothing was found on the road ahead and these are detours. */
    data class Results(
        val category: PoiCategory,
        val results: List<PoiResult>,
        val onRoute: Boolean
    ) : PoiSearchState

    data class Empty(val category: PoiCategory) : PoiSearchState

    data class Failed(val category: PoiCategory, val message: String) : PoiSearchState
}

/**
 * Bridges the navigation service (GPS) and the navigation manager (logic) to the UI.
 *
 * Holds only the application context — a ViewModel outlives the Activity that
 * created it, so keeping an Activity reference here would leak it across rotation.
 */
class NavigationViewModel(
    context: Context,
    private val routeService: RouteService = RouteService()
) : ViewModel() {

    companion object {
        private const val TAG = "NavigationViewModel"

        /** How often to re-check time-based state such as GPS loss. */
        private const val TICK_INTERVAL_MS = 5_000L

        /** Minimum gap between automatic off-route recalculations. */
        private const val RECALCULATION_COOLDOWN_MS = 30_000L

        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NavigationViewModel(context) as T
            }
        }
    }

    private val appContext = context.applicationContext

    private val navigationManager = NavigationManager()
    private val ttsManager = TTSManager(appContext)
    private val poiService = PoiService()

    val uiState: StateFlow<NavigationUIState> = navigationManager.uiState
    val locationFlow: StateFlow<LocationResult?> = navigationManager.locationFlow

    private val _showTtsUnavailable = MutableStateFlow(false)
    val showTtsUnavailable: StateFlow<Boolean> = _showTtsUnavailable.asStateFlow()

    private val _showPermissionRationale = MutableStateFlow(false)
    val showPermissionRationale: StateFlow<Boolean> = _showPermissionRationale.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _poiSearch = MutableStateFlow<PoiSearchState>(PoiSearchState.Closed)
    val poiSearch: StateFlow<PoiSearchState> = _poiSearch.asStateFlow()

    private var navigationService: NavigationService? = null
    private var serviceBound = false
    /** Route to start as soon as the service connects, if navigation was requested first. */
    private var pendingRoute: Route? = null
    private var recalculating = false
    private var lastRecalculationAtMs = 0L

    /** Bumped per POI search so a superseded one cannot publish its answer. */
    private var poiSearchToken = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NavigationService.NavigationBinder ?: return
            navigationService = binder.getService()
            serviceBound = true
            Log.d(TAG, "NavigationService connected")

            viewModelScope.launch {
                binder.getLocationFlow().collect { fix ->
                    if (fix != null) navigationManager.setLocation(fix)
                }
            }

            viewModelScope.launch {
                binder.getNotificationActions().collect { action ->
                    when (action) {
                        NavigationService.ACTION_PAUSE -> navigationManager.pauseNavigation()
                        NavigationService.ACTION_END -> endNavigation()
                    }
                }
            }

            pendingRoute?.let { route ->
                pendingRoute = null
                beginNavigation(route)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            navigationService = null
            Log.d(TAG, "NavigationService disconnected")
        }
    }

    init {
        navigationManager.setTTSManager(ttsManager)
        ttsManager.initialize { success ->
            if (!success) {
                _showTtsUnavailable.value = true
                Log.w(TAG, "TTS unavailable — audio guidance disabled")
            }
        }

        // Keep the ongoing notification in step with the on-screen guidance, and
        // recalculate when the rider strays off route.
        viewModelScope.launch {
            uiState.collect { state -> onStateChanged(state) }
        }

        // GPS loss is the absence of fixes, so only an independent tick can spot it.
        viewModelScope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                navigationManager.tick()
            }
        }
    }

    // ─── Navigation actions ──────────────────────────────────────────────────

    fun startNavigation(route: Route) {
        if (!hasLocationPermission()) {
            _showPermissionRationale.value = true
            return
        }
        if (route.routeGeometry.let { it == null || it.size < 2 }) {
            _errorMessage.value = "This route has no usable geometry to navigate."
            return
        }

        // Start it as a foreground service as well as binding: a bound-only service
        // dies with the last binding, which would end the ride the moment the app
        // is backgrounded.
        val intent = Intent(appContext, NavigationService::class.java)
        ContextCompat.startForegroundService(appContext, intent)

        val service = navigationService
        if (service == null) {
            pendingRoute = route
            bindService(intent)
        } else {
            beginNavigation(route)
        }
    }

    private fun beginNavigation(route: Route) {
        val service = navigationService
        if (service == null) {
            _errorMessage.value = "Could not start location tracking."
            return
        }
        if (!service.startTracking()) {
            _errorMessage.value = "Could not start GPS. Check that location is enabled."
            return
        }
        if (!navigationManager.startNavigation(route)) {
            _errorMessage.value = "This route has no usable geometry to navigate."
            service.stopTracking()
        }
    }

    private fun bindService(intent: Intent) {
        if (serviceBound) return
        try {
            appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind to NavigationService", e)
            _errorMessage.value = "Could not start location tracking."
        }
    }

    fun pauseNavigation() {
        navigationManager.pauseNavigation()
        navigationService?.stopTracking()
    }

    fun resumeNavigation() {
        if (navigationService?.startTracking() == false) {
            _errorMessage.value = "Could not restart GPS. Check that location is enabled."
            return
        }
        navigationManager.resumeNavigation()
    }

    fun endNavigation() {
        navigationManager.endNavigation()
        pendingRoute = null
        // The picker belongs to the ride that just ended. Left set, a result still
        // in flight would land after the rider got home and reappear on their next
        // ride days later, headed "Petrol ahead" and listing stations near where
        // the last ride finished.
        dismissPoiSearch()
        navigationService?.stopTracking()
        appContext.stopService(Intent(appContext, NavigationService::class.java))
        unbindService()
    }

    /**
     * Skip the intermediate waypoint the rider is currently heading for and route
     * straight to the one after it from wherever they are now.
     */
    fun skipWaypoint() {
        val remaining = navigationManager.waypointsAfterSkip()
        if (remaining.isNullOrEmpty()) {
            _errorMessage.value = "There is no waypoint to skip."
            return
        }
        recalculateTo(remaining, "Could not route past the skipped waypoint.")
    }

    // ─── Fuel and food ───────────────────────────────────────────────────────

    /**
     * Look for the nearest [category] on the road ahead.
     *
     * Position is the latest raw fix. The corridor is measured along the route from
     * wherever the rider actually is, which is the honest starting point even when
     * that is a few metres off the line.
     */
    fun searchPoi(category: PoiCategory) {
        val state = uiState.value
        val here = navigationManager.currentPosition ?: state.rawPosition
        if (here == null) {
            _poiSearch.value = PoiSearchState.Failed(category, "No GPS fix yet.")
            return
        }

        // Every search gets a token. Without one, two searches in flight are told
        // apart only by the *type* of the current state, so a rider who taps Fuel
        // and then Food is shown whichever answer lands first under whichever
        // heading is current — in practice, petrol stations labelled "Food ahead",
        // with the food search silently discarded.
        val token = ++poiSearchToken
        _poiSearch.value = PoiSearchState.Searching(category)

        poiService.findNearest(
            category = category,
            riderPosition = here,
            routeGeometry = state.routeGeometry.orEmpty(),
            segmentIndex = state.routeSegmentIndex
        ) { outcome ->
            // Superseded by a later search, or the rider closed the card, or the
            // ride ended while this was in flight. Dropping a result panel back
            // over the map in any of those cases would be worse than losing it.
            if (token != poiSearchToken) return@findNearest
            if (_poiSearch.value !is PoiSearchState.Searching) return@findNearest

            _poiSearch.value = when (outcome) {
                is PoiOutcome.OnRoute -> PoiSearchState.Results(category, outcome.results, onRoute = true)
                is PoiOutcome.OffRoute -> PoiSearchState.Results(category, outcome.results, onRoute = false)
                PoiOutcome.None -> PoiSearchState.Empty(category)
                is PoiOutcome.Failed -> PoiSearchState.Failed(category, outcome.message)
            }
        }
    }

    fun dismissPoiSearch() {
        // Bumping the token as well as closing the card: a result still in flight
        // must not reopen it seconds after the rider dismissed it.
        poiSearchToken++
        _poiSearch.value = PoiSearchState.Closed
    }

    /**
     * Divert through [poi], then carry on to everywhere the rider was already going.
     *
     * The stop is inserted ahead of the remaining waypoints rather than replacing
     * the route, so filling up does not quietly cancel the rest of the ride —
     * which is what routing straight to the petrol station would do.
     */
    fun rerouteVia(poi: PoiResult) {
        // A recalculation already running — an off-route rejoin, most often —
        // makes recalculateTo a no-op. That must not happen silently here: the
        // "Recalculating…" banner is already on screen for the other request, so
        // every visible signal would tell the rider their diversion is being
        // worked out while nothing had been asked for, and they would ride past
        // the petrol station believing it was coming.
        if (recalculating) {
            _errorMessage.value =
                "Still working out the last route change — try ${poi.name} again in a moment."
            return
        }

        dismissPoiSearch()
        val destinations = listOf(Waypoint(poi.name, poi.location)) + navigationManager.remainingWaypoints()
        if (destinations.size < 2 && navigationManager.route == null) {
            _errorMessage.value = "No active route to divert."
            return
        }

        // Paused, the GPS subscription is stopped, but replaceRoute puts the state
        // back to NAVIGATING — leaving a live-looking screen with a frozen position.
        // A rider picking a fuel stop plainly means to get going again, so resume
        // properly rather than half-way.
        if (uiState.value.state == NavigationState.PAUSED) {
            resumeNavigation()
        }

        recalculateTo(destinations, "Could not find a route via ${poi.name}.")
    }

    /**
     * Rebuild the route from the rider's current position to the waypoints still
     * ahead of them. Used for an explicit skip, a diversion to fuel or food, and
     * automatic off-route recovery.
     */
    private fun recalculateTo(destinations: List<Waypoint>, failureMessage: String) {
        val here = navigationManager.locationFlow.value ?: run {
            _errorMessage.value = "No GPS fix — cannot recalculate."
            return
        }
        val previous = navigationManager.route ?: return
        val end = destinations.lastOrNull() ?: return
        val intermediates = destinations.dropLast(1)

        if (recalculating) return
        recalculating = true
        lastRecalculationAtMs = System.currentTimeMillis()
        navigationManager.setRecalculating(true)

        val start = Waypoint(
            "Current position",
            GeoPoint(here.location.latitude, here.location.longitude)
        )

        viewModelScope.launch {
            val routes = withContext(Dispatchers.IO) {
                runCatching {
                    routeService.calculateRoute(
                        start, end, intermediates,
                        previous.routeType ?: RouteType.DIRECT,
                        previous.avoidances
                    )
                }.getOrElse { emptyList() }
            }

            recalculating = false
            val replacement = routes.firstOrNull { !it.isEstimate }
            if (replacement == null || !navigationManager.replaceRoute(replacement)) {
                navigationManager.setRecalculating(false)
                _errorMessage.value = failureMessage
            }
        }
    }

    /**
     * Steer a strayed rider back onto the planned route.
     *
     * Only the detour is requested from the routing service; the rest of the original
     * route is spliced on unchanged. Replanning end-to-end would silently throw away
     * the curvy route the rider chose and hand them back the service's idea of the
     * best way to the destination.
     */
    private fun rejoinRoute() {
        val rejoin = navigationManager.rejoinTarget() ?: return
        val here = navigationManager.currentPosition ?: return
        val previous = navigationManager.route ?: return

        if (recalculating) return
        recalculating = true
        lastRecalculationAtMs = System.currentTimeMillis()
        navigationManager.setRecalculating(true)

        viewModelScope.launch {
            val detours = withContext(Dispatchers.IO) {
                runCatching {
                    routeService.calculateRoute(
                        Waypoint("Current position", here),
                        Waypoint("Rejoin route", rejoin.point),
                        null,
                        previous.routeType ?: RouteType.DIRECT,
                        previous.avoidances
                    )
                }.getOrElse { emptyList() }
            }

            recalculating = false
            val detour = detours.firstOrNull { !it.isEstimate }
            val rejoined = detour?.let { navigationManager.buildRejoinRoute(it, rejoin) }

            if (rejoined == null || !navigationManager.replaceRoute(rejoined)) {
                navigationManager.setRecalculating(false)
                _errorMessage.value =
                    "Could not find a way back to the route. Rejoin it yourself or end the ride."
            }
        }
    }

    private fun onStateChanged(state: NavigationUIState) {
        if (state.state != NavigationState.NAVIGATING) return

        navigationService?.updateNavigationInstruction(
            state.currentInstruction?.instruction,
            state.currentInstruction?.let { formatDistance(it.distanceToManeuver) },
            formatETA(state.eta)
        )

        val offRouteNeedsNewRoute = state.isOffRoute && !state.isRecalculating && !state.isGpsLost
        // A failed recalculation leaves isOffRoute set, and this runs on every state
        // emission — so without a cooldown a rider out of routing coverage would
        // hammer the API once per GPS fix and be toasted each time.
        val cooledDown = System.currentTimeMillis() - lastRecalculationAtMs > RECALCULATION_COOLDOWN_MS

        if (offRouteNeedsNewRoute && cooledDown) {
            rejoinRoute()
        }
    }

    // ─── TTS ─────────────────────────────────────────────────────────────────

    fun setTTSEnabled(enabled: Boolean) {
        navigationManager.setTTSEnabled(enabled)
    }

    fun isTTSEnabled(): Boolean = navigationManager.isTTSEnabled()

    // ─── Permissions ─────────────────────────────────────────────────────────

    /**
     * Foreground navigation needs only fine location. Background location is a
     * separate, settings-only grant on Android 11+ and must never gate the ride:
     * the foreground service keeps GPS alive while the app is in use regardless.
     */
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun isNavigationAvailable(): Boolean = hasLocationPermission()

    fun dismissPermissionRationale() {
        _showPermissionRationale.value = false
    }

    fun dismissTtsUnavailable() {
        _showTtsUnavailable.value = false
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    // ─── Formatting ──────────────────────────────────────────────────────────

    /** @param meters distance in metres, as published in [NavigationUIState]. */
    fun formatDistance(meters: Double): String {
        return if (meters >= 1000) {
            "%.1f km".format(meters / 1000.0)
        } else {
            "${meters.toInt()} m"
        }
    }

    /** @param etaMillis absolute arrival time in epoch milliseconds. */
    fun formatETA(etaMillis: Long?): String? {
        if (etaMillis == null || etaMillis <= 0) return null
        val calendar = Calendar.getInstance().apply { timeInMillis = etaMillis }
        return "Arrive %02d:%02d".format(
            calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
        )
    }

    fun formatTimeRemaining(seconds: Double): String {
        val minutes = (seconds / 60.0).toInt()
        return if (minutes >= 60) {
            "${minutes / 60} h ${minutes % 60} min"
        } else {
            "$minutes min"
        }
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    private fun unbindService() {
        if (!serviceBound) return
        try {
            appContext.unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unbind from NavigationService", e)
        }
        serviceBound = false
        navigationService = null
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
        unbindService()
    }
}
