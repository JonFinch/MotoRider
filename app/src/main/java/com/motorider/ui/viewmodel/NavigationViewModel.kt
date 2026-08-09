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
import com.motorider.models.Route
import com.motorider.models.RouteType
import com.motorider.models.Waypoint
import com.motorider.navigation.NavigationManager
import com.motorider.navigation.NavigationState
import com.motorider.navigation.NavigationUIState
import com.motorider.navigation.TTSManager
import com.motorider.services.LocationResult
import com.motorider.services.NavigationService
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

    val uiState: StateFlow<NavigationUIState> = navigationManager.uiState
    val locationFlow: StateFlow<LocationResult?> = navigationManager.locationFlow

    private val _showTtsUnavailable = MutableStateFlow(false)
    val showTtsUnavailable: StateFlow<Boolean> = _showTtsUnavailable.asStateFlow()

    private val _showPermissionRationale = MutableStateFlow(false)
    val showPermissionRationale: StateFlow<Boolean> = _showPermissionRationale.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var navigationService: NavigationService? = null
    private var serviceBound = false
    /** Route to start as soon as the service connects, if navigation was requested first. */
    private var pendingRoute: Route? = null
    private var recalculating = false
    private var lastRecalculationAtMs = 0L

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

    /**
     * Rebuild the route from the rider's current position to the waypoints still
     * ahead of them. Used both for an explicit skip and for automatic off-route
     * recovery.
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
            recalculateTo(
                navigationManager.remainingWaypoints(),
                "Could not find a new route. Rejoin the original route or end the ride."
            )
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
