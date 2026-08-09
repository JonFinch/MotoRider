# MotoRider

An Android app for planning and riding motorcycle routes, built around curvy-road
routing rather than fastest-route routing. Kotlin + Jetpack Compose, OpenStreetMap
map data via osmdroid, and a separate self-hosted routing API (MotoRiderMaps).

## Build and test

```bash
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # unit tests (JVM, no device needed)
./gradlew installDebug         # install on a connected device/emulator
```

Kotlin 100%, no Java sources. Gradle Groovy DSL, `compileSdk`/`targetSdk` 36,
`minSdk` 24.

### Pointing at a routing API

`ApiConfig` reads its URLs from `BuildConfig`, set per build type in
`app/build.gradle` — nothing is hardcoded, because the right value differs per
target and a release build must not point at a developer's LAN box.

```bash
# Emulator (default): host loopback at 10.0.2.2:8080
./gradlew installDebug

# Physical device: the host's LAN IP
./gradlew installDebug -PmotoRiderDevApiBase=http://192.168.68.52:8080

# Self-hosted tileserver-gl instead of public OSM tiles
./gradlew installDebug -PmotoRiderTileBase=http://192.168.68.52:8081/styles/basic-preview
```

Release builds default `ROUTING_API_BASE_URL` to `https://api.motorider.invalid`
deliberately — override with `-PmotoRiderApiBase=` when a hosted API exists.

## Layout

```
app/src/main/java/com/motorider/
├── MotoRiderApplication.kt      osmdroid config; raises the tile-cache cap so
│                                trimming cannot delete offline regions
├── activities/MainActivity.kt   single activity, hosts MapScreen
├── config/ApiConfig.kt          API + tile base URLs from BuildConfig
├── models/                      Route, Waypoint, RouteType, Avoidance,
│                                TurnInstruction, NavigationWarning, OfflineRegion
├── services/
│   ├── RouteService.kt          POSTs to the routing API; falls back to a
│   │                            straight-line ESTIMATE flagged as such
│   ├── NavigationService.kt     foreground service, GPS collection only
│   ├── TileDownloadService.kt   foreground service, offline region downloads
│   └── TileStorageManager.kt    osmdroid tile cache read/write and expiry repair
├── navigation/
│   ├── NavigationManager.kt     the live navigation state machine
│   ├── NavigationState.kt       NavigationState enum + NavigationUIState
│   └── TTSManager.kt            TextToSpeech wrapper
├── ui/
│   ├── screen/                  MapScreen (planning, ~1600 lines),
│   │                            NavigationScreen, OfflineMapManagerScreen
│   ├── component/               OsmMapView, TurnBanner, Speedometer,
│   │                            NextManeuverCard
│   ├── viewmodel/               NavigationViewModel, OfflineMapManagerViewModel
│   └── theme/                   Material 3 theme, light/dark/system
├── utils/
│   ├── RouteUtils.kt            geocoding, API response parsing, turn-instruction
│   │                            generation
│   ├── NavigationUtils.kt       pure geometry: snapping, bearings, off-route
│   └── MapTileSource.kt         tile source wiring
└── maps/MotorcycleMapRenderer.kt   route polyline rendering
```

## Features

**Route planning** — start, end and intermediate waypoints, geocoded through
Nominatim. Four ride styles (Direct / Fast / Curvy / Extra Curvy) and five
avoidances (highways, tolls, ferries, unpaved, tracks & service roads). Returns
several ranked alternatives with distance, duration, curves/km and elevation gain.

**Quick Ride** — generates a round trip of a chosen distance and compass direction
from the rider's current position.

**Turn-by-turn navigation** — GPS tracking against the planned route geometry, with
spoken and on-screen manoeuvres, live ETA, a speedometer, off-route recalculation
and skippable intermediate waypoints. See "Navigation architecture" below.

**Offline maps** — predefined regions downloaded into osmdroid's tile cache for
riding without coverage, with a connectivity banner making it obvious when routing
calls will fail.

**Theming** — light / dark / system. Dark mode applies osmdroid's `INVERT_COLORS`
to the map: OSM's default white tiles are a genuine glare hazard on a
handlebar-mounted phone at night.

## Navigation architecture

```
NavigationScreen (Compose, stateless — takes state, emits callbacks)
   └── NavigationViewModel      binds the service, owns TTS + recalculation
        ├── NavigationService   foreground service; GPS only, no route logic.
        │                       Publishes StateFlow<LocationResult?> over a Binder
        └── NavigationManager   state machine: snapping, progress, instructions,
             └── NavigationUtils    off-route detection, arrival
                                    (pure functions, fully unit-tested)

NavigationMapCamera (in MapScreen) — follows the rider on the shared MapView
   └── NavigationCamera         heading-up rotation, speed-based zoom, pinch
                                override (pure logic, unit-tested)
```

While navigating, `NavigationScreen` is a **transparent overlay** over the map that
`MapScreen` already owns — controls at top and bottom, map through the middle.
There is deliberately only one `MapView` in the app: it already carries the route
polyline, the tile cache and any offline regions, and a second one would duplicate
all of that. `MapScreen` also holds `view.keepScreenOn` for the duration of a ride;
the service's `PARTIAL_WAKE_LOCK` keeps only the CPU alive, not the display.

Deliberate choices worth preserving:

- **`LocationManager`, not Play Services.** Keeps the app usable on devices without
  Google Play and avoids a ~5 MB dependency.
- **The service knows nothing about routes.** It collects fixes and renders
  whatever notification text it is handed. All logic lives in `NavigationManager`,
  which is why the manager is testable on the JVM.
- **`NavigationManager` takes plain `GeoPoint`s** via `setPosition`, so tests never
  need Android's `Location` class. `setLocation` delegates to it.
- **Foreground-only navigation.** `ACCESS_BACKGROUND_LOCATION` is not declared; a
  location-typed foreground service covers the use case without the Play Store
  justification background location demands.

### Units — the easiest thing to get wrong here

| Type | Distance | Duration |
|---|---|---|
| `Route` | **kilometres** | **minutes** |
| `NavigationUIState` | **metres** | **seconds** |
| `TurnInstruction` | **metres** | **seconds** |
| `LocationResult.speed` | — | **metres/second** |

`NavigationManager` converts at the boundary and nowhere else. Mixing these was the
single largest source of bugs in this code.

`TurnInstruction` carries two distances that are easy to confuse:
`distanceAlongRoute` is fixed at route-creation time (distance from the *start*),
while `distanceToManeuver` is filled in live by `NavigationManager` on each fix
(distance from the *rider*). Stored instructions always have the latter at 0.

## Conventions

- **Honesty about degraded results is a product requirement, not polish.** When the
  routing API is unreachable, `RouteService` returns a straight-line estimate with
  `isEstimate = true` and the UI says so loudly — a rider following an unflagged
  straight line would ride across whatever lies between the points. The same applies
  to `avoidancesHonoured` and `curvatureAvailable`: silently ignoring a ticked
  "Avoid Ferries" is the exact failure the feature exists to prevent.
- **Comments explain *why*, not *what*.** Most non-obvious code here encodes a
  platform constraint or a safety consideration; keep that reasoning with it.
- **User-facing strings live in `strings.xml`.** No hardcoded UI text.
- **Route geometry is dense.** Thousands of vertices on a long route — anything
  running per-vertex per-GPS-fix needs to be cheap or windowed.

## Testing

87 JVM unit tests, no device required:

| Suite | Covers |
|---|---|
| `NavigationUtilsTest` | snapping, cross-track distance, bearings, off-route |
| `NavigationManagerTest` | state machine, progress, units, arrival, waypoints |
| `NavigationCameraTest` | speed→zoom curve, heading smoothing, pinch override |
| `TurnInstructionsTest` | manoeuvre generation from geometry |
| `RouteUtilsTest` | geocoding and API response parsing |
| `RouteServiceTest`, `RouteModelTest`, `RoundTripTest` | routing and models |

`testOptions { unitTests.returnDefaultValues = true }` lets classes that log be
exercised on the JVM. `NavigationManager.clock` is injectable so time-based
behaviour (GPS loss) is testable without waiting.

Two scenarios are worth keeping tests around, because both produce plausible-looking
wrong answers rather than obvious failures:

- **Round trips.** Outbound and return legs can run metres apart, so an
  unconstrained nearest-point search snaps to the wrong leg and reports the rider as
  nearly home on departure. Snapping is windowed around the last known position.
- **GPS spikes.** An EMA carries a single outlier above the threshold for several
  samples, so "3 consecutive readings" must count *raw* readings; the smoothed value
  is only for hysteresis.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | routing API, Nominatim, tile downloads |
| `ACCESS_NETWORK_STATE` | offline banner, download guard |
| `ACCESS_FINE_LOCATION` | position on map and along route |
| `ACCESS_COARSE_LOCATION` | not optional — from Android 12 the system *ignores* a runtime request for FINE that does not also ask for COARSE |
| `FOREGROUND_SERVICE` + `_LOCATION` + `_DATA_SYNC` | navigation and tile-download services |
| `POST_NOTIFICATIONS` | both services show an ongoing notification. Requested at runtime on API 33+ when a ride starts, and never allowed to block one |

`android.hardware.location.gps` is declared as required — the app is not useful
without it.

## Known gaps

- `NavigationService` also registers a low-rate `NETWORK_PROVIDER` request (4 s, 10 m)
  as a coarse stand-in while GPS acquires, so a ride holds one GPS listener plus one
  network listener. Deliberate, but worth revisiting for battery.
- No speed-limit data: the routing API returns none, and inferring limits from road
  class would be guesswork a rider might act on. `CompactSpeedometer` therefore shows
  current speed only.
- No offline routing. Offline maps cover tiles only; planning a route needs a
  connection.
- Planning UI lives in one ~1600-line `MapScreen.kt` and is the obvious next
  refactor.

## Related docs

`README.md` (overview), `FUTURE_FEATURES.md` (roadmap), `PHASE2_PLAN.md` (offline
maps), `PHASE4_PLAN.md` (navigation), `MIGRATION_PLAN.md` (historical Java→Kotlin
migration). Plan documents describe intent at the time of writing and may not match
the shipped code — trust the source.
