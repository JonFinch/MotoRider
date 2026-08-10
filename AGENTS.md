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

All production and unit-test code is Kotlin. The one Java file left is
`androidTest/.../ExampleInstrumentedTest.java`, the untouched new-project template;
it is not part of the JVM test run. Gradle Groovy DSL, `compileSdk`/`targetSdk` 36,
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
├── data/OfflineRegionRepository.kt  process-wide singleton over
│                                offline_regions.json; shared by the download
│                                service and the UI so progress actually reaches
│                                the screen
├── services/
│   ├── RouteService.kt          POSTs to the routing API; falls back to a
│   │                            straight-line ESTIMATE flagged as such
│   ├── NavigationService.kt     foreground service, GPS collection only
│   ├── TileDownloadService.kt   foreground service, offline region downloads
│   └── TileStorageManager.kt    osmdroid tile cache read/write and expiry repair
├── navigation/
│   ├── NavigationManager.kt     the live navigation state machine
│   ├── NavigationState.kt       NavigationState enum + NavigationUIState
│   ├── NavigationCamera.kt      heading/zoom logic for the following camera
│   └── TTSManager.kt            TextToSpeech wrapper
├── ui/
│   ├── screen/
│   │   ├── MapScreen.kt         map, drawer, Quick Ride, Search, route results
│   │   ├── PlanPanel.kt         the route-planning sheet and its dialogs
│   │   ├── RoutePlanning.kt     stop resolution, routing, leg stitching
│   │   ├── NavigationScreen.kt  the riding overlay
│   │   └── OfflineMapManagerScreen.kt
│   ├── component/               OsmMapView, LocationPicker, TurnBanner,
│   │                            Speedometer
│   ├── viewmodel/               NavigationViewModel, OfflineMapManagerViewModel
│   └── theme/                   Material 3 theme, light/dark/system
├── utils/
│   ├── RouteUtils.kt            geocoding, place search, API response parsing,
│   │                            turn-instruction generation
│   ├── NavigationUtils.kt       pure geometry: snapping, bearings, off-route
│   └── MapTileSource.kt         tile source wiring
└── maps/MotorcycleMapRenderer.kt   route polyline, stop markers, route framing
```

## Features

**Route planning** — start, destination and intermediate stops. Each is a row that
opens a full-screen place picker (`LocationPicker.kt`); picking a result stores its
coordinates on the stop, so a chosen place is never geocoded a second time. Four
ride styles (Direct / Fast / Curvy / Extra Curvy), settable per leg, and five
avoidances (highways, tolls, ferries, unpaved, tracks & service roads). Returns
several ranked alternatives with distance, duration and curves/km.

**Quick Ride** — generates a round trip of a chosen distance and compass direction
from the rider's current position, with its own ride style. Avoidances are shared
with the planning sheet: they constrain what can be ridden at all, so they mean the
same thing whichever screen produced the route.

**Search** — find a place, see it on the map, send it to the plan as the destination
or as another stop.

**Turn-by-turn navigation** — GPS tracking against the planned route geometry, with
spoken and on-screen manoeuvres, live ETA, a speedometer, a route-progress bar,
off-route recalculation and skippable intermediate waypoints. When two manoeuvres
fall close together the banner adds a "then …" line, which is the only case a rider
cannot react to unaided. See "Navigation architecture" below.

**Offline maps** — five predefined regions (South East UK, South West UK, Wales,
Peak & Lake District, Scottish Highlands) downloaded into osmdroid's tile cache for
riding without coverage, with a connectivity banner making it obvious when routing
calls will fail. Custom user-drawn regions are not supported.

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
  "Avoid Ferries" is the exact failure the feature exists to prevent. The rule now
  covers addresses too: a stop that cannot be geocoded stops planning and is named
  in a snackbar. `planRoute` used to substitute a point a couple of kilometres from
  the rider and route there, so a typo produced a confident-looking route to a field.
- **Per-leg ride styles mean separate requests.** The routing API takes one
  `curviness` per call, so legs with different styles are routed one at a time and
  joined by `stitchLegs`. Sending only the first leg's style would be the same quiet
  betrayal as an ignored avoidance. Alternatives are dropped for mixed-style trips —
  leg 1's alternatives and leg 2's are not comparable as whole-trip options.
- **Picked places carry their coordinates.** `RouteStop.point` is filled in when a
  search result is tapped. Re-geocoding a display name at routing time can resolve
  to a different place, because Nominatim ranks by relevance.
- **Anything painted a fixed brand colour picks its foreground with
  `onBrandColor`,** never `onPrimary`. The turn banner and the Find route / Generate
  buttons are brand-coloured regardless of theme, and in the dark scheme `onPrimary`
  is `BrandBlueDark` — that put dark-blue text on a red banner and on a blue button,
  at night. `ButtonDefaults.buttonColors(containerColor = …)` is the trap: it leaves
  the content colour at its default and looks correct in the light theme. Use
  `brandButtonColors(…)`.
- **Comments explain *why*, not *what*.** Most non-obvious code here encodes a
  platform constraint or a safety consideration; keep that reasoning with it.
- **User-facing strings live in `strings.xml`.** No hardcoded UI text. `RouteUtils`
  is the one exception and deliberately so: it has no `Context` because its geometry
  is unit-tested on the JVM, so it emits `ManeuverType` and the UI resolves the words
  (`TurnBanner.getInstructionText`).
- **Nominatim allows one request a second** and wants a contact in the User-Agent.
  `RouteUtils.throttleNominatim` holds the gap on the shared executor; stop
  resolution is sequential for the same reason.
- **Route geometry is dense.** Thousands of vertices on a long route — anything
  running per-vertex per-GPS-fix needs to be cheap or windowed.

## Testing

137 JVM unit tests, no device required:

| Suite | Covers |
|---|---|
| `NavigationUtilsTest` | snapping, cross-track distance, bearings, off-route |
| `NavigationManagerTest` | state machine, progress, units, arrival, waypoints, the "then" rule |
| `NavigationCameraTest` | speed→zoom curve, heading smoothing, pinch override |
| `TurnInstructionsTest` | manoeuvre generation from geometry |
| `RouteUtilsTest` | geocoding and API response parsing |
| `PlaceSearchTest` | place-name splitting, suggestion parsing, coordinate carry |
| `StitchLegsTest` | joining per-leg routes: geometry, sums, weighted curvature |
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
  connection — and place search needs one too, since Nominatim is remote.
- Stops cannot be reordered by dragging, and there is no "pick on the map" option
  in the location picker. Both are natural next additions to `PlanPanel`.
- The dark scheme pairs `primary` (`BrandBlueLight`) with `onPrimary`
  (`BrandBlueDark`) at about 3.3:1 contrast — fine for large bold labels, under AA
  for normal text. Surfaces painted a *fixed* brand colour go through
  `onBrandColor` and are unaffected; this is the default M3 pairing, and changing it
  means changing the palette everywhere.
- The arrival screen shows no ride summary. `NavigationUIState` publishes only
  what remains, not totals, so that would need the ViewModel to keep them.
- No search history or saved places, so a regular route is retyped every time.

## Related docs

`README.md` (overview), `FUTURE_FEATURES.md` (roadmap), `PHASE2_PLAN.md` (offline
maps), `PHASE4_PLAN.md` (navigation), `MIGRATION_PLAN.md` (historical Java→Kotlin
migration). Plan documents describe intent at the time of writing and may not match
the shipped code — trust the source.
