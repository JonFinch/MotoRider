# MotoRider — Future Features Roadmap

> Research compiled from Kurviger and Calimoto, the two leading motorcycle navigation apps.

---

## Competitor Feature Analysis

### Kurviger
- Curvature-optimized route calculation (Direct/Fast/Curvy/Extra Curvy)
- Round trip generation
- Route import/export (GPX, KML, FIT, ITN, TRK)
- Transfer to Garmin/TomTom/Google Maps
- Cloud route sync across devices
- Roadbook creation with turn-by-turn instructions
- Offline maps and offline navigation
- Voice guidance
- Android Auto / Apple CarPlay
- Ride recording with statistics
- POI search along route (gas stations, restaurants, etc.)
- Roadblock/closure avoidance during navigation
- Skip waypoints during active navigation
- Split/merge routes
- Route history
- Photo upload to waypoints
- Map overlay manager (weather, traffic, etc.)
- Headset navigation (Sena integration)
- Remote control (handlebar controller support)
- Web route planner + mobile app

### Calimoto
- "Super Curvy" routing algorithm
- One-tap round trip generation
- Turn-by-turn navigation with voice guidance
- Offline maps and navigation
- Ride recording with stats (speed, elevation, lean angle, ride time)
- Elevation profile graphs
- Route sharing with community
- Social features (feed, likes, comments)
- Cross-platform sync (web + mobile)
- Speed and altitude warnings
- Fuel cost estimation
- Weather data along route
- Free tier with premium subscription model
- Web trip planner
- POI database

---

## MotoRider TODO — Features to Implement

### Phase 1 — Core Routing & Planning [COMPLETE except elevation]

- [x] OpenStreetMap map display (osmdroid)
- [x] Waypoint-based route planning (start / stops / destination)
- [x] Curvature preference per leg (Direct / Fast / Curvy / Extra Curvy) — legs with
      different styles are routed separately and stitched, see `RoutePlanning.kt`
- [x] Avoidance options — five: highways, tolls, ferries, unpaved, tracks & service
- [x] Place search with local biasing — full-screen picker; a picked result carries
      its coordinates so it is never re-geocoded
- [x] GPS "use current location" for any stop
- [x] Route distance, duration, and curvature display
- [x] Routing API integration — self-hosted MotoRiderMaps. **OSRM was removed**;
      see `MIGRATION_PLAN.md`
- [x] Compose-based modern UI
- [x] **Round trip generator** — create a circular route from current location
- [x] **Reverse geocoding** — resolves an address for the "current location" button
- [x] Route alternative generation (chips state each option's distance and duration)
- [ ] Route elevation gain calculation display — *the routing API returns no
      elevation, so `Route.elevationGain` is hardcoded 0.0 and nothing displays it.
      Was previously ticked here in error.*
- [ ] Route elevation profile graph — *never built; blocked on the above*

### Phase 2 — Maps & Offline [IN PROGRESS — 2 of 6 complete]

- [x] **2.1 Offline map downloads (per region)** — five predefined regions: South
      East UK, South West UK, Wales, Peak & Lake District, Scottish Highlands
      (`OfflineRegion.DEFAULTS`). User-drawn custom regions are not implemented.
- [ ] 2.2 Offline routing engine (embedded) — *Deferred: not reasonably implementable on Android without excessive storage and complexity*
- [ ] 2.3 Offline address search — *Deferred: not feasible without offline routing engine*
- [x] 2.4 Map tile caching for frequently visited areas
- [ ] 2.5 Map layer selection (standard, satellite, terrain) — *Saved for later*
  - [ ] Additional tile sources: OpenTopoMap (terrain)
  - [ ] Additional tile sources: Esri World Imagery (satellite)
  - [ ] Additional tile sources: CyclOSM (cycling-focused)
  - [ ] Additional tile sources: Stamen Terrain
  - [ ] Custom tile source support (user-defined URLs)
- [ ] 2.6 Weather overlay on map — *Saved for later*

### Phase 3 — Import / Export & Sharing

- [ ] GPX file import
- [ ] GPX route export
- [ ] KML import/export
- [ ] Share route as link
- [ ] Send route to another device
- [ ] Route QR code for easy sharing
- [ ] Export to Garmin devices
- [ ] Transfer to Google Maps

### Phase 4 — Navigation [SHIPPED — see PHASE4_PLAN.md]

- [x] Turn-by-turn navigation with live map tracking — heading-up camera,
      speed-based zoom, off-route detection and recalculation
- [x] Voice guidance (TTS-based turn instructions), with silent fallback when no
      engine is installed
- [x] Skip waypoint during navigation
- [x] Arrival time estimation (live ETA and remaining distance/time)
- [x] Route progress indicator
- [x] Night mode map theme — osmdroid `INVERT_COLORS`
- [x] Navigation notification persistence — ongoing notification with Pause/End
      actions, plus a `PARTIAL_WAKE_LOCK` so fixes keep arriving with the screen off
- [ ] Speed limit display and warnings — *the routing API returns no speed-limit
      data, and inferring limits from road class would be guesswork a rider might
      act on. Deliberately not attempted.*
- [ ] Lane guidance — *no lane data from the routing API*
- [ ] Road closure / roadblock detection — *needs an external feed*
- [ ] Handlebar controller / remote input support

### Phase 5 — Ride Data & Recording

- [ ] Ride recording (GPS track logging)
- [ ] Ride statistics dashboard (distance, duration, avg speed, top speed)
- [ ] Lean angle estimation from accelerometer
- [ ] Elevation gain/loss per ride
- [ ] Ride history list with filtering
- [ ] Ride replay on map
- [ ] Ride comparison (same route, different days)

### Phase 6 — POI & Search [PARTIAL]

- [x] Place search near current location — the Search screen queries Nominatim
      biased to the rider's position, marks the result on the map, and can send it
      into the plan as the destination or another stop
- [ ] POI search along route (fuel stations, restaurants, rest areas)
- [ ] Custom POI categories — *search is free-text only; no category filtering*
- [ ] POI display on map with icons — *a searched place gets a plain marker; there
      are no per-category icons*
- [ ] Search history / saved places — *a regular route is retyped every time*
- [ ] "Find curvy roads near me" discovery

### Phase 7 — Cloud & Premium

- [ ] Route cloud sync between devices
- [ ] User accounts
- [ ] Route library management (folders, favorites)
- [ ] Premium subscription infrastructure
- [ ] Community route sharing feed
- [ ] Leaderboard / achievements system

### Phase 8 — Platform Expansion

- [ ] Android Auto support
- [ ] Apple CarPlay support
- [ ] Wear OS companion app
- [ ] Web route planner
- [ ] Tablet-optimized layout

### Phase 9 — Polish & Delight [PARTIAL]

- [x] Dark theme support — including inverting the map tiles, which is a safety
      matter at night rather than a preference
- [x] Dynamic theming (follow system) — Light / System / Dark selector in the drawer
- [ ] Haptic feedback on navigation alerts — *the planning sheet has haptics
      (ride-style change, swap, Find route); navigation itself has none*
- [ ] Animated map transitions
- [ ] Route curvature heatmap overlay
- [ ] Fuel cost calculator
- [ ] Weather forecast along route
- [ ] Sunrise/sunset time for route planning
- [ ] Multiple language support (i18n) — *all user-facing text is already in
      `strings.xml`, but English is the only locale shipped*
- [ ] Accessibility improvements

---

## Phase status at a glance

| Phase | State |
|---|---|
| 1 — Core routing & planning | Complete, except elevation (no data from the API) |
| 2 — Maps & offline | 2 of 6: downloads and tile caching done; 2.2/2.3 deferred, 2.5/2.6 later |
| 3 — Import / export & sharing | Not started |
| 4 — Navigation | Shipped, minus speed limits, lane guidance, closures, remote input |
| 5 — Ride data & recording | Not started |
| 6 — POI & search | Partial: place search shipped; no POI categories or history |
| 7 — Cloud & premium | Not started |
| 8 — Platform expansion | Not started |
| 9 — Polish & delight | Partial: theming shipped; haptics planning-only; no i18n |

*Last updated: 2026-08-10 — audited against the shipped code. Phase 4 marked
shipped; Phase 1's two elevation items un-ticked (they were never implemented);
OSRM references corrected to the self-hosted routing API; Phase 6 and 9 updated for
the place-search and theming work.*
