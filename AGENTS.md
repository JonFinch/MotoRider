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
# Deployed server (default), using the credential from local.properties
./gradlew installDebug

# A local docker-compose stack instead (no credential needed or sent)
./gradlew installDebug \
    -PmotoRiderApiBase=http://10.0.2.2:8080 \
    -PmotoRiderTileBase=http://10.0.2.2:8081/styles/basic-preview

# An APK for a tester, carrying their own credential rather than yours
./gradlew assembleDebug -PmotoRiderApiUser=dave -PmotoRiderApiPassword=...
```

Both build types point at the deployed server; only `debuggable` and shrinking
differ. One property name per URL — there is no separate debug variant, because
a flag that silently does nothing is worse than no flag.

The API sits behind HTTP Basic over TLS. Credentials come from
`local.properties` (gitignored) via `BuildConfig`; unset means no
`Authorization` header at all, which is what a local stack expects. They are
recoverable from any APK that carries them, so build a tester their own with
`-PmotoRiderApiUser`/`-PmotoRiderApiPassword` and issue it server-side with
`scripts/manage_api_keys.sh` — never hand out a build carrying yours.

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
│   │                            manoeuvre parsing (+ geometry fallback)
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

**Quick Ride** — asks the routing service for a loop of a chosen distance and
compass heading from the rider's current position, with its own ride style.
Avoidances are shared with the planning sheet: they constrain what can be ridden at
all, so they mean the same thing whichever screen produced the route.

The app does **not** build the loop. It used to, by placing three via points on a
circle around the rider and routing through them — but those are arbitrary
coordinates, so each snapped to whatever road was nearest, often a dead-end lane,
and the rider was sent up it and back. Over five varied requests that produced a
mean worst out-and-back of 4.2 km. The service generates its own via points on the
network and picks between candidate loops; the same measurement came to 0.45 km.
See `round_trip` in MotoRiderMaps' `API_REFERENCE.md`, and Gate H of its
`scripts/verify_routing.py`.

**Search** — find a place, see it on the map, send it to the plan as the destination
or as another stop.

**Turn-by-turn navigation** — GPS tracking against the planned route geometry, with
spoken and on-screen manoeuvres, live ETA, a speedometer, a route-progress bar,
off-route recalculation and skippable intermediate waypoints. The route line is
split at the rider: the road already ridden is drawn duller and thinner, so what is
left reads as the primary line. Manoeuvres name the
road they lead onto ("Turn left onto Tagg Lane", "Roundabout, exit 2"). When two
fall close together the banner adds a "then …" line, which is the only case a rider
cannot react to unaided. See "Navigation architecture" below.

**Offline maps** — five predefined regions (South East UK, South West UK, Wales,
Peak & Lake District, Scottish Highlands) downloaded into osmdroid's tile cache for
riding without coverage, with a connectivity banner making it obvious when routing
calls will fail. Custom user-drawn regions are not supported.

**The rider's position** — a motorcycle seen from above, held upright on screen
while the *map* turns beneath it, so the way ahead is always out in front. The bike
points up because up is the direction of travel.

`MyLocationNewOverlay` picks between two behaviours by whether the fix carries a
bearing: with one it turns the icon to that heading, without one it counter-rotates
by the map's orientation and holds the icon upright. The second is what this app
wants, so `uprightFix` in `OsmMapView` strips the bearing from every fix reaching
the overlay. Letting it rotate as well fights the heading-up camera — the two
rotations very nearly cancel, and the bike wobbles about vertical as the camera's
smoothing lags the raw course.

The heading is not lost by that: it travels in `LocationResult.bearing`, which is
what turns the camera.

**Theming** — light / dark / system. Dark mode applies osmdroid's `INVERT_COLORS`
to the map: OSM's default white tiles are a genuine glare hazard on a
handlebar-mounted phone at night. See "Colour" below.

## Colour

The legibility bar is higher here than for a typical app: this is read on a
handlebar-mounted phone, in direct sun or at night, at a glance. Every
foreground/background pair the UI puts together clears WCAG AA (4.5:1) and most
clear AAA (7:1).

```bash
python3 scripts/contrast.py .    # exits non-zero if any pair drops below AA
```

The script reads `Color.kt` and `Theme.kt` directly, so it fails on a bad palette
edit rather than on a screenshot someone happens to look at. Run it after touching
either file. It separates text pairs (4.5:1) from non-text components (3:1), and it
replicates `onBrandColor` exactly rather than assuming the better of black/white —
that assumption is what hid a real bug, where a hand-picked 0.35 lightness threshold
put white on the orange turn banner at 3.08:1 when black would have given 6.82:1.
The crossover is a luminance of 0.179, and it is now derived rather than guessed.

Each brand hue carries a **ramp**, not a single value, because one colour cannot
serve both themes: a blue dark enough to read on white is too dark on the night
surface. The schemes pick the tone that suits their surface — light tones on top of
dark ones in the dark scheme, and the mirror in the light scheme.

**The brand orange is a fill, never a foreground.** Kept vivid on purpose, it
reaches 3.79:1 on white — enough for the 3:1 WCAG asks of a non-text component (the
Generate button, the slider track, the compass needle) and nowhere near the 4.5:1
text needs. So `secondary` never colours text or a glyph: anything that should read
as orange fills with `secondaryContainer` and writes in `onSecondaryContainer`.
That is why the Quick Ride readout is a pill and the warning banners are filled
rather than tinted. The audit encodes this — `secondary` is checked at the non-text
threshold, so using it for text would be a silent regression the script cannot see.

Three things are deliberately *not* theme-aware, and pair with `onBrandColor`
instead: the turn banner's urgency fills (blue "in good time", orange "getting
close", red "now" — the colour *is* the message, and a rider learns it), and the
start/via/destination map markers, which sit on map tiles rather than an app
surface.

`RouteRemaining` and `RouteTravelled` are fixed for a related reason: the polyline
is drawn over the tiles and is **not** touched by the dark theme's `INVERT_COLORS`,
which applies to `mapOverlay` alone. One pair therefore has to read against
near-white land and near-black land, which rules out anything at either end of the
lightness range. The audit checks both against representative tiles at the 3:1 a
non-text graphic needs. The two are separated by saturation and width rather than
luminance — as they are squeezed between the two tile extremes there is little
lightness room, which is also why every mainstream nav app does the same.

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

- **A refusal is not an outage.** `RouteService` throws `RouteRejectedException`
  when the service answers with an error, and the message reaches the rider
  unchanged. The straight-line estimate is only for a service that never replied.
  Conflating the two told a rider whose destination was a bridleway that the
  routing service was unreachable, and drew a confident line across the Cambrian
  Mountains to a place no motorcycle can legally go.
- **Honesty about degraded results is a product requirement, not polish.** When the
  routing API is unreachable, `RouteService` returns a straight-line estimate with
  `isEstimate = true` and the UI says so loudly — a rider following an unflagged
  straight line would ride across whatever lies between the points. The same applies
  to `avoidancesHonoured` and `curvatureAvailable`: silently ignoring a ticked
  "Avoid Ferries" is the exact failure the feature exists to prevent. The rule now
  covers addresses too: a stop that cannot be geocoded stops planning and is named
  in a snackbar. `planRoute` used to substitute a point a couple of kilometres from
  the rider and route there, so a typo produced a confident-looking route to a field.
- **Manoeuvres come from the routing service, never from geometry.** A bend and a
  junction are the same polyline, so heading changes cannot tell them apart:
  `generateTurnInstructions` emitted 8 manoeuvres where 4 were real on a 19 km
  route, and one every 770 m on a curvy one, nearly all announcing that the road
  curves. `parseInstructions` reads the service's own, which also carry the road
  name — something no amount of geometry can supply. The geometry path survives
  only as a fallback for the offline straight-line estimate and for an API that
  returns none; it is not an equal alternative. The same rule applies after an
  off-route rejoin, where `NavigationManager` stitches the two halves' service
  instructions rather than re-deriving from the joined line.
- **A missing bearing is not north.** `bearingForFix` prefers the receiver's course
  while the fix is actually moving, falls back to the heading implied by movement
  since the last fix, and otherwise holds the last known value. That last fallback
  must beat a reported-but-untrusted bearing, not the other way round: a receiver
  reporting 0.0 while stopped otherwise resets the heading between every pair of
  moving fixes, and the map flicks back to north-up at every standstill. The code here used
  to substitute `0f` whenever the GPS withheld a bearing, which swung the heading-up
  map to north and span the rider's marker to face it every time they slowed. Note
  the Android emulator reports a course of exactly 0 on every synthetic fix, so
  neither the map nor the marker will rotate there however the app moves — that is
  the emulator, not the app.
- **Every `ManeuverType` needs a TTS trigger distance.** The lookup in
  `maybeSpeakInstruction` returns early on a miss, so a type added to the enum and
  forgotten in `defaultTtsTriggerZones` is never spoken at all — silent failure on
  the one feature used without looking at the screen. A test fails the build for it.
- **Per-leg ride styles mean separate requests.** The routing API takes one
  `curviness` per call, so legs with different styles are routed one at a time and
  joined by `stitchLegs`. Sending only the first leg's style would be the same quiet
  betrayal as an ignored avoidance. Alternatives are dropped for mixed-style trips —
  leg 1's alternatives and leg 2's are not comparable as whole-trip options.
- **Picked places carry their coordinates.** `RouteStop.point` is filled in when a
  search result is tapped. Re-geocoding a display name at routing time can resolve
  to a different place, because Nominatim ranks by relevance.
- **Take colours from `MaterialTheme.colorScheme`, not from the palette file.** The
  constants in `Color.kt` are tonal steps for the two schemes to choose between, not
  colours to paint with. A single `BrandBlue` used in both themes was 5.75:1 on white
  and 2.97:1 on the night surface; the fix was two tones per hue, picked by the
  scheme. The only exceptions are the fixed fills (`Banner*`, `Marker*`), which pair
  with `onBrandColor`.
- **`ButtonDefaults.buttonColors(containerColor = …)` is a trap.** It replaces only
  the container and leaves the content colour at `onPrimary`, so a button given a
  non-primary container gets a foreground for a different background — and it looks
  correct in the light theme, which is how it survived. Name `contentColor` too.
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

163 JVM unit tests, no device required:

| Suite | Covers |
|---|---|
| `NavigationUtilsTest` | snapping, cross-track distance, bearings, off-route |
| `NavigationManagerTest` | state machine, progress, units, arrival, waypoints, the "then" rule |
| `NavigationCameraTest` | speed→zoom curve, heading smoothing, pinch override |
| `RideProgressTest` | the index the map splits the route line at |
| `BearingForFixTest` | which way the rider is pointing, and what to do without a course |
| `TurnInstructionsTest` | the geometry fallback's manoeuvre generation |
| `ServiceInstructionsTest` | parsing the service's manoeuvres, sign codes, TTS coverage |
| `RouteUtilsTest` | geocoding and API response parsing |
| `PlaceSearchTest` | place-name splitting, suggestion parsing, coordinate carry |
| `StitchLegsTest` | joining per-leg routes: geometry, sums, weighted curvature |
| `RouteServiceTest`, `RouteModelTest` | routing and models |
| `RoundTripTest` | the loop request contract — field names, and metres not kilometres |
| `RouteRejectionTest` | a service refusal must not become a straight-line estimate |

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
- The arrival screen shows no ride summary. `NavigationUIState` publishes only
  what remains, not totals, so that would need the ViewModel to keep them.
- No search history or saved places, so a regular route is retyped every time.

## Related docs

`README.md` (overview), `FUTURE_FEATURES.md` (roadmap), `PHASE2_PLAN.md` (offline
maps), `PHASE4_PLAN.md` (navigation), `MIGRATION_PLAN.md` (historical Java→Kotlin
migration). Plan documents describe intent at the time of writing and may not match
the shipped code — trust the source.
