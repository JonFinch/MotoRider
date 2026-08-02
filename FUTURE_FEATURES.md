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

### Phase 1 — Core Routing & Planning [COMPLETE]

- [x] OpenStreetMap map display (osmdroid)
- [x] Basic waypoint-based route planning (start / via / end)
- [x] Curvature preference per leg (Direct / Fast / Curvy / Extra Curvy)
- [x] Avoidance options (highways, tolls, ferries)
- [x] Address autocomplete with local biasing
- [x] GPS "use current location" for any waypoint
- [x] Route distance, duration, and curvature display
- [x] OSRM routing engine integration
- [x] Compose-based modern UI
- [x] **Round trip generator** — create a circular route from current location
- [x] **Reverse geocoding** — show address name when using "current location" button
- [x] Route elevation profile graph
- [x] Route elevation gain calculation display
- [x] Route alternative generation (show 2-3 route options)

### Phase 2 — Maps & Offline [PLANNED]

- [ ] Offline map downloads (per region)
- [ ] Offline routing engine (embedded)
- [ ] Offline address search
- [ ] Map tile caching for frequently visited areas
- [ ] Map layer selection (standard, satellite, terrain)
- [ ] Weather overlay on map

### Phase 3 — Import / Export & Sharing

- [ ] GPX file import
- [ ] GPX route export
- [ ] KML import/export
- [ ] Share route as link
- [ ] Send route to another device
- [ ] Route QR code for easy sharing
- [ ] Export to Garmin devices
- [ ] Transfer to Google Maps

### Phase 4 — Navigation

- [ ] Turn-by-turn navigation with live map tracking
- [ ] Voice guidance (TTS-based turn instructions)
- [ ] Speed limit display and warnings
- [ ] Lane guidance
- [ ] Road closure / roadblock detection
- [ ] Skip waypoint during navigation
- [ ] Arrival time estimation
- [ ] Night mode map theme
- [ ] Navigation notification persistence
- [ ] Handlebar controller / remote input support

### Phase 5 — Ride Data & Recording

- [ ] Ride recording (GPS track logging)
- [ ] Ride statistics dashboard (distance, duration, avg speed, top speed)
- [ ] Lean angle estimation from accelerometer
- [ ] Elevation gain/loss per ride
- [ ] Ride history list with filtering
- [ ] Ride replay on map
- [ ] Ride comparison (same route, different days)

### Phase 6 — POI & Search

- [ ] POI search along route (fuel stations, restaurants, rest areas)
- [ ] POI search near current location
- [ ] Custom POI categories
- [ ] POI display on map with icons
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

### Phase 9 — Polish & Delight

- [ ] Dark theme support
- [ ] Dynamic theming (follow system)
- [ ] Haptic feedback on navigation alerts
- [ ] Animated map transitions
- [ ] Route curvature heatmap overlay
- [ ] Fuel cost calculator
- [ ] Weather forecast along route
- [ ] Sunrise/sunset time for route planning
- [ ] Multiple language support (i18n)
- [ ] Accessibility improvements

---

*Last updated: 2026-07-31*
