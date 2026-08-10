# MotoRider

An Android app for planning and riding motorcycle routes, built around curvy-road
routing rather than fastest-route routing. In the spirit of Kurviger and Calimoto.

## Overview

MotoRider plans rides for the riding, not the arriving: routes are scored on how
curvy they are, and the rider picks how much of that they want. Map data comes from
OpenStreetMap via osmdroid; routing comes from a separate self-hosted API
(MotoRiderMaps); place search comes from Nominatim.

## Features

- **Route planning** — start, destination and any number of intermediate stops,
  each chosen from a full-screen place search. Four ride styles (Direct, Fast,
  Curvy, Extra Curvy), settable for the whole trip or per leg, and five avoidances.
- **Quick Ride** — a round trip of a chosen distance and compass heading, looping
  back to where the rider is now.
- **Search** — find a place, see it on the map, drop it straight into the plan.
- **Turn-by-turn navigation** — GPS tracking against the route with spoken and
  on-screen manoeuvres, live ETA, speedometer, progress, off-route recalculation
  and skippable stops.
- **Offline maps** — predefined regions cached for riding without coverage, with a
  banner making it obvious when routing calls will fail.
- **Light / dark / system theming**, with the map inverted in dark mode: OSM's
  white tiles are a genuine glare hazard on a handlebar-mounted phone at night.

Degraded results are always labelled. If the routing API cannot be reached the app
returns a straight-line estimate and says, loudly, that it is not a rideable route.
If an avoidance could not be honoured, or curvature data was unavailable, the route
card says so. If an address cannot be found, planning stops and names it rather than
routing somewhere approximate.

## Tech stack

- **Language:** Kotlin — all production and unit-test code (the only Java left is
  the generated `ExampleInstrumentedTest.java` template)
- **UI:** Jetpack Compose, Material 3
- **Min SDK:** 24 (Android 7.0) · **Target/compile SDK:** 36
- **Map rendering:** osmdroid 6.1.20 (OpenStreetMap)
- **Routing:** self-hosted MotoRiderMaps HTTP API
- **Geocoding and search:** Nominatim
- **Location:** platform `LocationManager` — no Play Services dependency

## Project structure

```
app/src/main/java/com/motorider/
├── MotoRiderApplication.kt      osmdroid configuration and tile-cache sizing
├── activities/MainActivity.kt   single activity, hosts MapScreen
├── config/ApiConfig.kt          API and tile base URLs, supplied by the build type
├── models/                      Route, Waypoint, RouteType, Avoidance,
│                                TurnInstruction, NavigationWarning, OfflineRegion
├── services/                    RouteService, NavigationService,
│                                TileDownloadService, TileStorageManager
├── navigation/                  NavigationManager (state machine), TTSManager,
│                                NavigationCamera
├── ui/
│   ├── screen/                  MapScreen, PlanPanel, RoutePlanning,
│   │                            NavigationScreen, OfflineMapManagerScreen
│   ├── component/               OsmMapView, LocationPicker, TurnBanner, Speedometer
│   ├── viewmodel/               NavigationViewModel, OfflineMapManagerViewModel
│   └── theme/                   Material 3 theme
├── utils/                       RouteUtils (geocoding, search, parsing, manoeuvre
│                                generation), NavigationUtils (pure geometry)
└── maps/MotorcycleMapRenderer.kt   route polyline and stop markers
```

## Getting started

1. Open the project in Android Studio and sync Gradle.
2. Build and run on a device or emulator.

Routing needs an API to point at. The URL comes from the build type, never
hardcoded, because the right value differs per target:

```bash
# Emulator (default): host loopback at 10.0.2.2:8080
./gradlew installDebug

# Physical device: the host's LAN IP
./gradlew installDebug -PmotoRiderDevApiBase=http://192.168.68.52:8080
```

Without a reachable API the app still runs — it returns clearly-labelled
straight-line estimates instead of routes.

## Building and testing

```bash
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # 134 JVM unit tests, no device needed
./gradlew installDebug         # install on a connected device/emulator
```

## Further reading

`AGENTS.md` is the working guide to the codebase — architecture, the unit
conventions that cause the most bugs, and current known gaps.
