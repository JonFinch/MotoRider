# Phase 4 — Navigation: Implementation Plan (Revised)

> **STATUS: SHIPPED.** This is the plan as written before the work, kept for its
> design reasoning. It is not a description of the code — where the two disagree,
> the code is right. `AGENTS.md` describes what actually shipped.
>
> Known differences between this plan and the shipped app:
>
> | Planned | Shipped |
> |---|---|
> | `navigation/TurnInstruction.kt`, `navigation/NavigationWarning.kt` | live in `models/` |
> | `navigation/NavigationViewModel.kt` | lives in `ui/viewmodel/` |
> | `component/NextManeuverCard.kt` | **deliberately not built.** A scrollable list of 3–5 manoeuvres is the wrong shape for this app: the routing API returns no road names, so rows carry no landmark to tell them apart, and the 20° manoeuvre threshold means a curvy route — the kind this app exists to plan — fills the list with curve-following noise. Replaced by a single "then …" line in `TurnBanner`, shown only when the follow-on lands within 150 m of a manoeuvre the rider is already approaching. `upcomingInstructions` was dropped for `followOnInstruction`. |
> | `component/NavigationMapView.kt` | never built — navigation drives the single shared `OsmMapView` via `NavigationMapCamera` |
> | Full-screen `NavigationScreen` | a **transparent overlay** over the map, controls top and bottom |
> | Speed-limit display and warnings | not implemented; the routing API returns no limit data |
>
> The "Current State" section immediately below describes the code *before* Phase 4
> and is retained only as a record of the starting point.

## Overview

Build full turn-by-turn navigation for MotoRider, enabling riders to follow planned routes with live GPS tracking, audio guidance, and safety features. The navigation system uses the existing online routing API (Phase 1) and offline map tiles (Phase 2.1, 2.4). No offline routing engine is required — navigation simply follows the route geometry already returned by the routing API.

**Estimated Timeline:** 6 weeks (3 sprints, ~20 working days)
**Complexity:** High
**Dependencies:** RouteService (existing), Route/Waypoint models (existing), osmdroid (existing), Android Location APIs (existing)

---

## Architecture

### Current State *(as of writing, before Phase 4 — historical)*

- `NavigationService.kt`: Stub foreground service — shows a notification, no routing logic
- `MapScreen.kt` (1567 lines): "Start Navigation" button calls the stub service
- `RouteService.kt`: Returns `Route` objects with `routeGeometry: List<GeoPoint>`
- `MotorcycleMapRenderer.kt`: Draws purple polyline on map
- No GPS tracking during navigation
- No turn-by-turn instructions
- No audio/TTS guidance
- No ETA or remaining distance

### Target Architecture

```
app/src/main/java/com/motorider/
├── navigation/                       # NEW package
│   ├── NavigationManager.kt          # Core navigation logic (position checking, instructions)
│   ├── NavigationState.kt            # State machine (IDLE, NAVIGATING, PAUSED, ARRIVED)
│   ├── TurnInstruction.kt            # Data class for turn instructions
│   ├── NavigationWarning.kt          # Data class for safety warnings (speed limits, etc.)
│   └── NavigationViewModel.kt        # Compose ViewModel binding state to UI
├── services/
│   └── NavigationService.kt          # REWRITE: GPS tracking via LocationManager
├── ui/
│   ├── screen/
│   │   └── NavigationScreen.kt       # NEW: Full-screen navigation UI
│   └── component/
│       ├── TurnBanner.kt             # NEW: Visual turn instruction banner
│       ├── Speedometer.kt            # NEW: Current speed display
│       ├── NextManeuverCard.kt       # NEW: Upcoming maneuvers list
│       └── NavigationMapView.kt      # EXTEND: Navigation-specific map rendering
└── utils/
    └── NavigationUtils.kt            # NEW: Snapping, distance, bearing calculations
```

### Component Boundaries (Clarified)

```
┌─────────────────────────────────────────────────────────────┐
│  NavigationScreen (Compose UI)                               │
│  └── NavigationViewModel (StateFlow<NavigationUIState>)     │
│       └── NavigationManager (business logic)                │
│            └── NavigationUtils (pure functions)             │
│                                                            │
│  NavigationService (Foreground Service)                     │
│  └── LocationManager (Android API, NOT Play Services)      │
│       └── exposes StateFlow<Location?> via Binder          │
│                                                            │
│  Communication: ServiceConnection bridges Service → ViewModel│
└─────────────────────────────────────────────────────────────┘
```

**Key decisions:**
- **NO `play-services-location`** — use Android's built-in `LocationManager` API exclusively. This avoids a ~5MB dependency and works on all Android devices (including those without Google Play Services).
- **`NavigationService`** is a pure GPS data collector. It exposes raw `Location` objects via a `Binder` that returns a `StateFlow<Location?>`.
- **`NavigationManager`** runs in the `ViewModel`'s scope (UI thread). It consumes the `StateFlow<Location?>`, computes nearest point on route, generates instructions, manages the state machine, and triggers recalculation.
- **`NavigationViewModel`** bridges the service to the manager: it creates a `ServiceConnection`, connects to `NavigationService`, and exposes the location flow to `NavigationManager`.

---

## Feature Breakdown

### 4.1 — Core GPS Tracking & Position Monitoring

**What:** Continuously track the rider's GPS position and determine proximity to the route.

**Implementation:**

1. **`NavigationService.kt` — Full Rewrite**
   - Use Android's built-in `LocationManager` (NOT Play Services)
   - Request location updates via `requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener)`
   - Run as foreground service with persistent notification (as required by Android 10+)
   - Handle `ACCESS_FINE_LOCATION` and `ACCESS_BACKGROUND_LOCATION` permissions (already declared in manifest)
   - Request `ACCESS_BACKGROUND_LOCATION` at runtime when user taps "Start Navigation" (with rationale: "MotoRider needs your location even when the app is in the background to provide turn-by-turn navigation")
   - Expose location via a `Binder` that returns `StateFlow<Location?>`
   - Handle screen-off: acquire `PARTIAL_WAKE_LOCK` to keep GPS running, reduce update rate to 0.5Hz (2 seconds) when screen is off, restore 1Hz when screen turns on

2. **Position-to-Route Matching (Map Snapping)**
   - For each GPS fix, find the nearest point on the route geometry (nearest-point-on-polyline algorithm)
   - Compute perpendicular distance from GPS fix to route
   - If distance < threshold (50m), mark as "on route"
   - If distance > threshold, mark as "off route" and trigger recalculation

3. **GPS Loss & Dead Reckoning**
   - If no GPS fix for > 60 seconds (tunnel, underground parking):
     - Mark state as `isGpsLost = true`
     - Show warning: "GPS signal lost"
     - Use last known GPS fix for up to 60 seconds (do NOT attempt recalculation — no valid starting point)
     - After 60 seconds, show "No GPS — cannot navigate" and pause navigation
   - No dead reckoning (accelerometer/gyro fusion is unreliable on phones mounted on handlebars)

4. **Progress Tracking**
   - Track progress along the route as a value 0.0 → 1.0 (percentage of route completed)
   - Use cumulative distance along polyline (not straight-line to destination)
   - Update: distance remaining, time remaining, estimated arrival time

**Technical Details:**

```kotlin
// Nearest point on polyline algorithm (in NavigationUtils.kt)
fun nearestPointOnPolyline(point: GeoPoint, geometry: List<GeoPoint>): NearestPointResult {
    if (geometry.size < 10) {
        // Too few points — use distance-to-destination only
        return NearestPointResult(
            point = geometry.last(),
            distance = point.distanceTo(geometry.last()),
            segmentIndex = geometry.lastIndex,
            distanceAlongRoute = geometry.accumulateDistance()
        )
    }
    
    var minDistance = Double.MAX_VALUE
    var nearestPoint = GeoPoint(0.0, 0.0)
    var segmentIndex = 0
    var distanceAlongRoute = 0.0
    
    for (i in 0 until geometry.size - 1) {
        val a = geometry[i]
        val b = geometry[i + 1]
        val dist = perpendicularDistance(point, a, b)
        if (dist < minDistance) {
            minDistance = dist
            nearestPoint = projectPointOntoSegment(point, a, b)
            segmentIndex = i
            distanceAlongRoute = geometry.accumulateDistanceTo(i) + dist
        }
    }
    
    return NearestPointResult(
        point = nearestPoint,
        distance = minDistance,
        segmentIndex = segmentIndex,
        distanceAlongRoute = distanceAlongRoute
    )
}

// Off-route detection: exponential moving average to filter GPS noise
class OffRouteDetector(private val thresholdMeters: Float = 50f) {
    private var smoothedDistance = 0.0f
    
    fun update(distance: Float): Boolean {
        smoothedDistance = smoothedDistance * 0.7f + distance * 0.3f
        return smoothedDistance > thresholdMeters
    }
}
```

**Off-route detection:**
- Threshold: 50 meters (configurable)
- Exponential moving average of distance (alpha = 0.3) to filter GPS noise
- Only trigger "off route" if smoothed distance > threshold for 3 consecutive fixes (avoid false positives from GPS jitter)
- When off-route: call `RouteService.calculateRouteAsync()` from current position to original destination
- Show recalculation UI with "Recalculating..." indicator

---

### 4.2 — Turn-by-Turn Instructions (Geometry-Derived Only)

**What:** Provide clear, actionable instructions at each maneuver point along the route.

**Critical constraint:** The current routing API returns only `distance`, `duration`, `score`, `geometry`, and `curvature_metadata`. There is **NO** road name, speed limit, lane, or elevation data in the API response. All instructions are derived purely from route geometry (heading changes between consecutive points).

**Implementation:**

1. **Instruction Generation (Geometry-Derived)**
   - Compute instructions during route calculation (in `RouteUtils`)
   - Analyze heading changes between consecutive route segments:
     - Angle change < 15°: "Continue" (no maneuver)
     - 15°–45°: "Slight left/right"
     - 45°–135°: "Turn left/right"
     - 135°–180°: "Sharp turn / U-turn"
     - Angle ~180°: "U-turn"
   - For multi-waypoint routes: "Arrive at [waypoint name]" at each intermediate waypoint
   - Store as `List<TurnInstruction>` on `Route` object (computed once at route creation)

2. **`TurnInstruction` Data Class**
   ```kotlin
   data class TurnInstruction(
       val maneuverType: ManeuverType,
       val instruction: String,
       val distanceToManeuver: Double,    // meters to next maneuver
       val distanceRemaining: Double,     // meters to destination
       val timeRemaining: Double,         // seconds to destination (from route duration)
       val bearing: Double,               // compass bearing after maneuver (degrees)
       val segmentIndex: Int              // index in route geometry
   )
   
   enum class ManeuverType {
       DEPART, CONTINUE, TURN_LEFT, TURN_RIGHT, TURN_SLIGHT_LEFT, TURN_SLIGHT_RIGHT,
       UTURN, ARRIVE, WAYPOINT_ARRIVED
   }
   ```

3. **Instruction Computation**
   - Compute instructions in `RouteUtils.generateTurnInstructions(geometry: List<GeoPoint>, waypoints: List<Waypoint>): List<TurnInstruction>`
   - Called from `RouteService.fetchRoutesFromApi()` after parsing API response
   - Pre-compute all instructions upfront (no real-time computation during navigation)
   - For each GPS fix, determine which instruction is "active" (next upcoming maneuver within triggering distance)

4. **UI: Turn Banner (`TurnBanner.kt`)**
   - Full-width banner at top of navigation screen
   - Large directional arrow (SVG icon, rotated to bearing)
   - Distance text (e.g., "200 m")
   - Instruction text (e.g., "Turn right" / "Continue straight" / "Arrive at destination")
   - Color-coded by urgency:
     - Blue: > 500m
     - Orange: 200–500m
     - Red: < 200m (current maneuver)
   - Smooth animation on maneuver transition

5. **UI: Next Maneuvers List (`NextManeuverCard.kt`)**
   - Scrollable list of upcoming maneuvers (next 3–5)
   - Each item: small arrow + distance + instruction
   - Highlight current maneuver
   - Hide when not needed (e.g., straight route with no turns)

---

### 4.3 — Audio/TTS Guidance

**What:** Speak turn instructions aloud so the rider can keep eyes on the road.

**Implementation:**

1. **Android TextToSpeech API**
   - Use `android.speech.tts.TextToSpeech` (built into Android, no external dependencies)
   - Initialize TTS on navigation start, shut down on navigation end
   - Handle voice installation missing (fallback to silent mode)
   - Check TTS availability on app launch: `TextToSpeech.isLanguageAvailable(Locale.getDefault())`
   - If no TTS engine: show non-blocking notification "Install a TTS engine for audio guidance"

2. **Guidance Triggering**
   - Speak instruction when rider enters triggering zone (distance before maneuver):
     - DEPART: at start
     - TURN_LEFT/RIGHT: 150m before
     - TURN_SLIGHT_LEFT/RIGHT: 100m before
     - UTURN: 100m before
     - ARRIVE/WAYPOINT_ARRIVED: at destination
   - Cooldown: don't re-trigger same instruction within 10 seconds (prevents re-speaking on GPS jitter)

3. **Volume & Audio Routing**
   - Use `AudioManager` STREAM_MUSIC (respects media volume)
   - Check if Bluetooth audio is connected via `AudioManager.getBluetoothA2dpSink()` (Android 11+) or `BluetoothAdapter#getConnectedDevices(BluetoothDevice.TYPE_AUDIO)`
   - If Bluetooth audio headset connected: audio routes automatically via Android
   - If no headset: play through speaker (louder for motorcycle riding)
   - Check Do Not Disturb mode: `AudioManager.isMusicSilent()` (Android 10+). If DND is active, skip TTS but continue visual guidance

4. **UI: Audio Indicator**
   - Small speaker icon in navigation screen
   - Shows "TTS: ON" / "TTS: OFF"
   - Toggle button to mute/unmute
   - Default: ON (rider can disable if they prefer their own GPS voice)

---

### 4.4 — Speed Display & Warnings

**What:** Show the rider's current speed. Optionally show speed limit if available from future API extensions.

**Implementation:**

1. **Speed Computation**
   - Compute rider's current speed from GPS fixes: `location.speed` (m/s, provided by `LocationManager`)
   - If GPS does not provide speed (some devices), compute from distance between consecutive fixes / time delta
   - Use moving average over 3 seconds to filter GPS noise

2. **Speed Limit (If Available)**
   - The current routing API does NOT return speed limit data
   - If a future API extension provides speed limits per segment, display them
   - **Do NOT infer speed limits from road classification** — inferred limits are unreliable and potentially dangerous
   - When no speed limit data: show current speed only (no limit indicator)

3. **UI: Speedometer (`Speedometer.kt`)**
   - Circular gauge showing current speed (large, readable at a glance)
   - Speed limit indicator (smaller, with red border when exceeded) — only shown if data available
   - Color coding:
     - Green: within limit (or no limit data)
     - Yellow: approaching limit (within 10%)
     - Red: exceeding limit
   - Update at 1Hz (same as GPS refresh)

4. **Vibration Feedback**
   - Brief vibration when entering warning zone (exceeding speed limit, if limit data available)
   - Use `Vibrator` API (Android built-in)
   - Short pulse (100ms), not continuous
   - Add `VIBRATE` permission to manifest

---

### 4.5 — Skip Waypoint During Navigation

**What:** Allow the rider to skip an intermediate waypoint while actively navigating.

**Implementation:**

1. **Skip Logic**
   - Track `currentWaypointIndex` in `NavigationManager` (0 = start, N = destination, 1..N-1 = intermediates)
   - When navigating a multi-waypoint route, show "Skip" button for each intermediate waypoint
   - Button appears when rider is within 500m of the waypoint
   - On skip: recalculate route from current position to the waypoint after the skipped one
   - Update ETA and distance accordingly
   - Log skipped waypoints for ride statistics (future phase)

2. **UI: Skip Button**
   - Small button in navigation screen: "Skip [waypoint name]"
   - Appears contextually (when within 500m of waypoint)
   - Confirmation dialog: "Skip [waypoint name]? Route will be recalculated."
   - Visual feedback: waypoint marked as "skipped" in next maneuvers list

---

### 4.6 — Arrival Time Estimation

**What:** Display accurate ETA based on current progress and remaining distance.

**Implementation:**

1. **ETA Calculation**
   - Primary: `eta = current_time + (distance_remaining / average_speed_from_route_geometry)`
   - The route geometry already includes `duration` (total estimated time). Use proportional remaining time:
     - `timeRemaining = route.duration * (1.0 - progress)` where progress = 0.0 → 1.0
   - Secondary adjustment: if actual riding speed differs significantly from route estimate, apply ±10% correction factor
   - Handle stops: if speed < 2 km/h for > 60 seconds, pause ETA countdown (rider is stopped, not moving slower)

2. **UI: ETA Display**
   - Show in navigation screen: "Arrive at 14:32" (HH:MM format)
   - Show in turn banner: "2.3 km · 8 min" (distance + time to next maneuver)
   - Show in next maneuvers list: time for each maneuver

---

### 4.7 — Navigation Notification Persistence

**What:** Ensure the navigation notification is visible and actionable even when the app is backgrounded or the screen is off.

**Implementation:**

1. **Notification Enhancement**
   - Replace static "Navigation active" notification with dynamic content:
     - Next maneuver instruction
     - Distance to next maneuver
     - ETA
   - Use `NotificationCompat.BigTextStyle` for longer instructions (Android 5+)
   - Use `NotificationCompat.RemoteAction` for lockscreen actions:
     - "Pause" action: stops navigation, keeps service running
     - "End" action: stops navigation, returns to planning screen

2. **Screen Off Behavior**
   - Keep GPS tracking running with `PARTIAL_WAKE_LOCK`
   - Reduce GPS update rate to 0.5Hz (every 2 seconds) to save battery when screen is off
   - Restore 1Hz when screen turns back on
   - Notification remains visible with screen off

3. **Battery Optimization**
   - Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission (add to manifest)
   - On first navigation start, prompt user to exclude MotoRider from battery optimization
   - If user declines: continue with normal GPS throttling (may reduce accuracy in power-saving mode)
   - Detect power saving mode: `PowerManager.isPowerSaveMode()`. If enabled, reduce GPS rate to 0.5Hz, disable TTS and vibration
   - Target: 15–20% battery drain per hour of navigation (acceptable for motorcycle riding)

---

## Existing Code to Modify

| File | Changes |
|------|---------|
| `NavigationService.kt` | **Full rewrite** — replace stub with `LocationManager` GPS tracking, wake locks, foreground notification |
| `Route.kt` | Add `turnInstructions: List<TurnInstruction>?` property |
| `RouteService.kt` | Call `RouteUtils.generateTurnInstructions()` after parsing API response |
| `RouteUtils.kt` | Add `generateTurnInstructions(geometry, waypoints): List<TurnInstruction>` |
| `MotorcycleMapRenderer.kt` | Extend to render navigation overlays (current position arrow, progress indicator) |
| `MapScreen.kt` | Add navigation mode state, "Start Navigation" → transition to navigation screen |
| `AndroidManifest.xml` | Add missing permissions: `VIBRATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |

## New Files to Create

| File | Package | Purpose |
|------|---------|---------|
| `NavigationManager.kt` | `com.motorider.navigation` | Core navigation logic, position checking, progress tracking, state machine |
| `NavigationState.kt` | `com.motorider.navigation` | State enum (IDLE, NAVIGATING, PAUSED, ARRIVED) + transient flags |
| `TurnInstruction.kt` | `com.motorider.models` | Turn instruction data class |
| `NavigationWarning.kt` | `com.motorider.models` | Warning data class (speed, GPS loss) |
| `NavigationViewModel.kt` | `com.motorider.ui.viewmodel` | Compose ViewModel binding state to UI |
| `NavigationScreen.kt` | `com.motorider.ui.screen` | Full-screen navigation UI (Compose) |
| `TurnBanner.kt` | `com.motorider.ui.component` | Turn instruction banner composable |
| `Speedometer.kt` | `com.motorider.ui.component` | Speed display gauge composable |
| `NextManeuverCard.kt` | `com.motorider.ui.component` | Upcoming maneuvers list composable |
| `NavigationMapView.kt` | `com.motorider.ui.component` | Navigation-specific map rendering |
| `NavigationUtils.kt` | `com.motorider.utils` | Snapping, distance, bearing, instruction generation |

**Estimated new code:** ~2,500–3,500 lines of Kotlin
**Estimated modified code:** ~400–600 lines

---

## Permissions Required

### Already Declared (in `AndroidManifest.xml`)
| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Routing API, Nominatim |
| `ACCESS_FINE_LOCATION` | GPS tracking |
| `ACCESS_BACKGROUND_LOCATION` | Background GPS (Android 10+) |
| `FOREGROUND_SERVICE` | Navigation service |
| `FOREGROUND_SERVICE_LOCATION` | Navigation service type |
| `POST_NOTIFICATIONS` | Navigation notification |

### To Be Added
| Permission | Purpose |
|-----------|---------|
| `VIBRATE` | Speed warning feedback |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent GPS throttling on battery-optimized devices |

### Runtime Permission Flow (at navigation start)
1. Check `ACCESS_FINE_LOCATION` — if not granted, request it (rationale: "MotoRider needs your location to track your position on the map")
2. Check `ACCESS_BACKGROUND_LOCATION` — if not granted, request it (rationale: "MotoRider needs your location even when the app is in the background to provide turn-by-turn navigation")
3. If on Android 12+, check battery optimization — if optimized, prompt user to exclude MotoRider (rationale: "Battery optimization may reduce GPS accuracy during navigation")
4. If any permission is denied, show error and do NOT start navigation

---

## Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.7 | ViewModel + Compose (already included) |
| `androidx.core:core-ktx` | 1.15.0 | NotificationCompat.RemoteAction (already included) |

**Note:** NO external dependencies added. Uses Android's built-in `LocationManager` API exclusively (no `play-services-location`).

---

## Implementation Order

### Sprint 1 (Weeks 1–2): Core Tracking & Instructions

| # | Task | Details |
|---|------|---------|
| 1 | Add missing permissions to `AndroidManifest.xml` | `VIBRATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| 2 | Rewrite `NavigationService` with `LocationManager` | GPS updates, wake locks, foreground notification, Binder exposing `StateFlow<Location?>` |
| 3 | Implement `NavigationUtils.kt` | Nearest point on polyline, perpendicular distance, bearing, cumulative distance |
| 4 | Implement `generateTurnInstructions()` | Geometry-derived instructions from route geometry (heading changes) |
| 5 | Implement `NavigationManager` | State machine, position monitoring, off-route detection, progress tracking, GPS loss handling |
| 6 | Implement `NavigationViewModel` | ServiceConnection bridge to NavigationService, exposes `StateFlow<NavigationUIState>` |
| 7 | Unit tests | `NavigationUtilsTest`, `NavigationManagerTest`, `NavigationStateTest` |

### Sprint 2 (Weeks 3–4): UI & Audio

| # | Task | Details |
|---|------|---------|
| 8 | Build `NavigationScreen` | Full-screen navigation UI, transition from planning screen |
| 9 | Build `TurnBanner` | Directional arrow + distance + instruction text |
| 10 | Build `Speedometer` | Current speed gauge |
| 11 | Build `NextManeuverCard` | Scrollable upcoming maneuvers list |
| 12 | Integrate `NavigationMapView` | Current position arrow, route progress indicator on map |
| 13 | Integrate TextToSpeech | Audio guidance, DND handling, Bluetooth audio routing |
| 14 | Instrumented tests | Full navigation flow with real GPS (on device/emulator) |

### Sprint 3 (Weeks 5–6): Polish & Edge Cases

| # | Task | Details |
|---|------|---------|
| 15 | Implement skip waypoint | Contextual skip button, recalculation on skip |
| 16 | Implement ETA calculation | Proportional remaining time, stop handling |
| 17 | Enhance notification | Dynamic content, lockscreen pause/end actions, BatteryOptimization prompt |
| 18 | Handle edge cases | GPS loss (60s warning, pause), power saving mode, TTS initialization failure, low point count routes |
| 19 | Manual testing | Real-world rides on predefined offline regions, Android 7–15 |
| 20 | Final integration testing | All features end-to-end |

---

## State Machine

```
States (4):
  IDLE — No active navigation (planning screen)
  NAVIGATING — GPS tracking active, following route
  PAUSED — GPS tracking paused, service still running
  ARRIVED — Reached destination

Transient flags (not states):
  isRecalculating — false while computing new route (sub-state of NAVIGATING)
  isOffRoute — distance > 50m from route (sub-state of NAVIGATING)
  isGpsLost — no GPS fix for > 60 seconds (sub-state of NAVIGATING)

Transitions:
  IDLE → NAVIGATING (user taps "Start Navigation")
  NAVIGATING → PAUSED (user taps "Pause")
  PAUSED → NAVIGATING (user taps "Resume")
  NAVIGATING → ARRIVED (progress reaches 1.0)
  ARRIVED → IDLE (user taps "End Ride")
  NAVIGATING → NAVIGATING (off-route → recalculating → back to navigating)
  NAVIGATING → NAVIGATING (gps lost → warning → back to navigating or paused)
```

---

## Data Flow

```
User taps "Start Navigation"
    │
    ▼
NavigationViewModel.startNavigation(route)
    │
    ├──→ Request background location permission (if not granted)
    │
    ├──→ Bind to NavigationService via ServiceConnection
    │       └── LocationManager.requestLocationUpdates(GPS_PROVIDER, 1000ms)
    │           └── exposes StateFlow<Location?> via Binder
    │
    ├──→ NavigationManager (on each GPS fix)
    │       ├── nearestPointOnPolyline() (NavigationUtils)
    │       ├── updateProgress(0.0 → 1.0)
    │       ├── checkOffRoute() (EMA filter, 50m threshold, 3-fix confirmation)
    │       ├── checkGpsLoss (> 60s no fix → isGpsLost = true)
    │       ├── getNextTurnInstruction() (from pre-computed list)
    │       ├── triggerTTSGuidance() (if in triggering zone, cooldown checked)
    │       ├── checkSpeedLimit() (if data available)
    │       └── updateStateFlow()
    │
    ├──→ RouteService (if off-route and recalculation needed)
    │       └── POST /route from current position to destination
    │
    └──→ NavigationScreen (Compose)
            ├── TurnBanner (composable)
            ├── Speedometer (composable)
            ├── NavigationMapView (osmdroid via AndroidView)
            ├── NextManeuverCard (composable)
            └── Bottom panel (ETA, pause, end, skip)
```

---

## Edge Case Handling

| Scenario | Behavior |
|----------|----------|
| **No GPS fix** | After 60s: show "GPS signal lost" warning, pause navigation. No recalculation (no valid starting point). |
| **GPS returns** | Resume navigation from last known position. |
| **Off-route (> 50m)** | Show "You're off route" warning, auto-recalculate from current position to destination. EMA filter (alpha 0.3, 3-fix confirmation) prevents false positives. |
| **Recalculation fails** | Show "Could not find new route" with option to end navigation or cancel recalculation. |
| **Route geometry has < 10 points** (straight-line estimate) | Use distance-to-destination only (no nearest-point-on-polyline). |
| **Route geometry changes during navigation** (user switches route) | Invalidate current progress index, recompute instructions from new geometry. |
| **Route becomes null** | End navigation with warning: "Route no longer available." |
| **TTS engine not installed** | Show non-blocking notification "Install a TTS engine for audio guidance." Continue with visual-only guidance. |
| **TTS initialization fails** | Silently skip TTS, continue navigation. |
| **Do Not Disturb mode active** | `AudioManager.isMusicSilent()` → skip TTS, continue visual guidance. |
| **Power saving mode** | `PowerManager.isPowerSaveMode()` → reduce GPS to 0.5Hz, disable TTS and vibration. |
| **Speed limit data unavailable** | Show current speed only (no limit indicator, no warnings). |
| **Multi-waypoint: waypoint already passed** | Skip to next unpassed waypoint. |
| **Multi-waypoint: waypoint geocoding failed** | Show "Unknown location" as waypoint name. |
| **Device without GPS hardware** | Manifest declares `android.hardware.location.gps` as required. If somehow installed, show error: "This device does not support GPS navigation." |

---

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| GPS inaccuracy in urban canyons / tunnels | High | Medium | Dead reckoning: use last known fix for 60s, show "GPS lost" warning, no recalculation during GPS loss |
| Battery drain during long rides | Medium | High | Target 15–20%/hr; reduce GPS rate when stationary or screen off; offer "eco mode" (0.5Hz) |
| TTS voice / engine not installed on device | Low | Low | Check on first launch; show non-blocking notification; silent fallback |
| No road name data from routing API | Certain | Low | Generate generic instructions ("turn left", "continue") — this is the only option with current API |
| Android version fragmentation (24–36) | High | Medium | Test on minSdk (24) and targetSdk (36); use backward-compatible APIs only |
| Background location restrictions (Android 10+) | High | High | Already have permission in manifest; runtime request with rationale at navigation start |
| Android battery optimization throttling GPS | High | Medium | Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` at first navigation start; detect power saving mode and adapt |

---

## Success Criteria

- [x] User can start navigation from any planned route
- [x] GPS tracking follows the route in real-time (on real device)
- [x] Turn-by-turn visual instructions display correctly (geometry-derived)
- [x] Audio/TTS guidance speaks instructions at appropriate times (with silent fallback)
- [x] Speedometer displays current speed
- [ ] Speed limit warnings trigger when exceeding limit — **dropped**: the routing
      API returns no speed-limit data, and guessing from road class is not something
      to put in front of a rider
- [x] ETA updates accurately during navigation (proportional from route geometry)
- [x] Navigation notification persists when app is backgrounded (dynamic content, lockscreen Pause/End actions)
- [x] Navigation continues with screen off (reduced GPS rate, WakeLock)
- [x] Off-route detection triggers recalculation (EMA-filtered)
- [x] User can pause/resume/end navigation at any time
- [x] User can skip intermediate waypoints
- [x] GPS loss handled gracefully (warning, pause, resume on fix)
- [ ] Battery drain ≤ 20% per hour of active navigation — **not measured.** One GPS
      listener plus one low-rate network listener are held for a ride; see the
      "Known gaps" note in `AGENTS.md`
- [x] All unit tests pass — 137 JVM tests, no device required
- [ ] Manual testing passes on Android 7–15 — **partial**: exercised on an
      emulator (API 36) only

---

## Excluded from Phase 4 (Deferred)

The following features from the original roadmap are explicitly excluded from Phase 4 and deferred:

| Feature | Phase | Reason |
|---------|-------|--------|
| Handlebar controller support | Future (Phase 5+) | Sena/Cardo use proprietary protocols, not standard Bluetooth HID. Requires proprietary SDKs. |
| Roadblock / closure detection | Future (Phase 5+) | Requires external APIs (e.g., Highways England OpenData). Not connected to routing pipeline. |
| Lane guidance | Future (Phase 5+) | Requires lane data from routing API. Current API does not provide it. |
| Night mode map theme | — | Already implemented via osmdroid's `INVERT_COLORS` in `MapScreen.kt` (line numbers have since moved). |

---

*Last updated: 2026-08-10 — audited against the shipped code: status header added,
acceptance criteria resolved, planned-vs-shipped differences recorded at the top.*
*Phase 4 scope: Navigation features from FUTURE_FEATURES.md (Phase 4 section), revised after adversarial review*
