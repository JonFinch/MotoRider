# Implementing `improvements.md` on the current setup

An assessment of the "Kurviger AI" specification against what MotoRider and
MotoRiderMaps actually are today, with a route to shipping the features that are
worth shipping and a plain statement of the ones that are not reachable from here.

*Written 2026-08-12, audited against the code in `~/code/Android/MotoRider` and
`~/code/MotoRiderMaps`.*

---

## 1. The specification is for a different application

`improvements.md` specifies a two-phase product: an MCP server wrapping the
commercial Kurviger routing profile, and a React Native/Expo app talking to a
Fastify backend over PostgreSQL/Drizzle, rendering with Mapbox or MapLibre GL.

That is not this project. This project is:

| | Spec | Actual |
|---|---|---|
| App | React Native + Expo, TypeScript | Native Android, Kotlin + Compose, ~9.5k lines |
| Map | Mapbox GL / MapLibre GL (vector) | osmdroid 6.1.20 (raster tiles) |
| Backend | Fastify + Drizzle + PostGIS | FastAPI + GraphHopper + PostGIS (MotoRiderMaps) |
| Routing | GraphHopper cloud, Kurviger profile | Self-hosted GraphHopper, own custom model |
| Geocoding | GraphHopper Geocoding API | Nominatim |
| Accounts | JWT auth, per-user data | None — the app is entirely local, the API stateless |
| Coverage | Worldwide | `united-kingdom-latest.osm.pbf` |

**The recommendation is not to adopt the spec's technology choices.** Adopting them
means discarding a working, unit-tested navigation stack — the state machine,
off-route detection, camera, instruction parsing and the whole colour/contrast
discipline in `AGENTS.md` — and rebuilding it in a framework that would then have to
re-earn all of it. Standing up Fastify + Drizzle alongside the existing FastAPI
service would also mean two backend stacks over one PostGIS database.

What the spec is genuinely useful for is its **feature list and its API surface**.
Read as a contract to implement — endpoints, data model, screens — it maps onto
MotoRider and MotoRiderMaps well, and the rest of this document does that mapping.

Note also that `FUTURE_FEATURES.md` already tracks most of these features with
accurate implementation status. Two roadmaps will drift apart. Whichever survives,
the other should become a pointer to it.

---

## 2. What the current setup already gives you

Worth being explicit about, because it is a lot more than the spec assumes.

**Already shipped in the app** (spec features F1 and F2 are largely done):
multi-waypoint planning, four curviness levels settable per leg, five avoidances,
ranked alternatives, real service-generated round trips, place search with
coordinate carry-through, full turn-by-turn navigation with TTS, off-route
recalculation, live ETA, waypoint skipping, background tracking under a foreground
service with a wake lock, offline tile regions, and dark-mode map inversion.

**Already in the backend, unused by the app:**

- `osm_way_id` path details are already requested from GraphHopper and used
  server-side for curvature scoring (`routing-api/graphhopper_client.py:110`,
  `curvature_scorer.py`). They are simply not passed back in the response.
- PostGIS holds `curvature_segments` with `geom geometry(LineString)` and
  `segment_ways` with `fk_maxspeed`, `surface` and `highway` — road geometry,
  speed limits and surface, per OSM way, queryable by bounding box
  (`GET /curvature/bbox` already does exactly this shape of query).
- `road_class_details` and `street_name` path details are already returned.

That combination means the heat-map, road-rating and POI features are far cheaper
than the spec implies: the spatial database and the road geometry are already there.

**What the app does not have, and would need:** no HTTP client library (raw
`HttpURLConnection` + `org.json`), no database (SharedPreferences and a hand-rolled
JSON-file repository), no DI, no WorkManager, no GPX handling, no user accounts.

---

## 3. Feature-by-feature

Cost is rough calendar effort for one developer. "Backend" means MotoRiderMaps.

### Free or nearly free — no new data, no new services

| Feature | Where | Cost | Notes |
|---|---|---|---|
| **F6 Bike profiles** (Sport/Adventure/Cruiser/Tourer) | App | 1–2 d | A preset mapping onto the existing `curviness` + `Avoidance` set, stored in SharedPreferences. 80% works today; see gradients below. |
| **F2 HUD mode** | App | 2–3 d | A third Compose screen over existing `NavigationUIState`. All the data is already flowing. |
| **F2 Ride recording** | App | 3–5 d | `NavigationService` already emits a `StateFlow<LocationResult>` at 1 Hz with speed, bearing and accuracy. Recording is a collector plus storage. |
| **F8 Ride history + stats + detail map** | App | 1–2 w | Distance/duration/avg/max speed all derive from the trace. Elevation gain does not — see §4. |
| **GPX export** (F1) | App | 2–3 d | Pure formatting over `Route.routeGeometry` + `turnInstructions`. Not in the spec's endpoint list but in `FUTURE_FEATURES.md` and more useful than most of what is. |
| **F1 Save routes / saved places** | App | 3–5 d | Local first. Cloud sync is a different feature with a much higher price — §4. |
| **F7 Tour planner** | App | 3–4 w | The largest UI surface in the spec, but it needs no new data at all: a tour is a list of days, each a saved route. Multi-track GPX export falls out of the export work. |

**Storage decision.** Seven of these want persistence. `OfflineRegionRepository`'s
JSON-file-plus-StateFlow pattern works for five regions; it will not do for
thousands of trackpoints. Add Room for rides, trackpoints, saved routes and tours,
and leave the existing preferences where they are. This is the one new dependency
worth taking without hesitation.

### Cheap backend change, then cheap app change

| Feature | Cost | Notes |
|---|---|---|
| **Expose `osm_way_id` intervals in `/route`** | 1 h backend | The data is already in hand at `routing-api/main.py:479`. Add it next to `road_class_details`. This single change unlocks road ratings, per-segment surface and speed limits. Do it first. |
| **Curvy-roads heat map** | 3–5 d | `GET /curvature/bbox` already returns scored LineStrings. This is the spec's F5 heat map with curvature substituted for community ratings — available *today*, with no accounts, no moderation and no cold-start problem. See the rendering caveat in §4. |
| **Speed limit display** (not in this spec; in `FUTURE_FEATURES.md`) | 3–5 d | `segment_ways.fk_maxspeed` carries OSM's tagged limit. `FUTURE_FEATURES.md` rules this out on the grounds that no data exists; that is now out of date. **But show it only where OSM has it tagged and never infer it from road class** — the original reasoning still holds for the untagged case, and a rider may act on a wrong number. |
| **F4 Weather along route** | 1–2 w | Straightforward, with one hard constraint: the OpenWeatherMap key must never ship in the APK, so it needs a `/weather` proxy on MotoRiderMaps. Check OWM's current tier terms before designing around "free tier sufficient" — the spec's assumption is stale in general. |
| **F3 POI fuel & food** | 2–3 w | Two routes to it: proxy Overpass through the backend, or import fuel/restaurant POIs into PostGIS during the existing curvature pipeline. **Prefer the second** — the OSM extract is already being processed, PostGIS is already there, and it removes a dependence on public Overpass instances that are rate-limited and periodically unavailable mid-ride. Detour preview is two extra `/route` calls. Opening hours are the messy part; see §4. |

### Substantial, but reachable

| Feature | Cost | Notes |
|---|---|---|
| **F1 Mid-ride waypoint editing** | 1–2 w | Touches `NavigationManager`'s state machine, which is the most carefully tested code in the app. Budget for the tests, not the feature. |
| **F1 Non-circular out-and-back** | 1–2 w backend | The spec names `avoid_edges`, which is not a parameter GraphHopper OSS exposes. The workable version: route A→B, buffer that geometry into a polygon, then route B→A with a custom model that penalises the resulting custom area. The custom-model machinery is already in use (`motorcycle_custom.json`), so this is fiddly rather than blocked. |
| **F1 Alternative-route comparison UI** | 3–5 d | Alternatives already come back ranked; this is presentation, minus the elevation column. |

### Expensive, and the cost is not the code

| Feature | Cost | Notes |
|---|---|---|
| **F5 Road ratings + community heat map** | 6–10 w | Technically the easiest of the "hard" features — PostGIS, way geometry and way IDs are all in place, and the aggregate table is a straight port of the spec's data model. The cost is everything around it: accounts, spam control, moderation, and user-generated content obligations under GDPR (deletion, export, a privacy policy, a named controller). |
| **Accounts / auth / cloud sync** (spec's Auth + User + all `user_id` tables) | 4–8 w | The prerequisite for ratings, tours-in-cloud, route sync and ride sync. MotoRiderMaps today has **no authentication, no rate limiting and CORS open to all origins** — by design, as a stateless routing service. Turning it into a system of record means TLS, backups, migrations, key rotation, an incident story and a hosting bill that no longer scales to zero. This is the single largest item in the whole document and it delivers no riding value by itself. |
| **Phase 1: MCP server** | 1–2 w | Genuinely worth building and completely independent of the app — but build it against **MotoRiderMaps, not Kurviger**. The spec's own prerequisites concede the Kurviger motorcycle profile has no self-service signup; your own service already answers the same questions. `create_route`, `create_roundtrip` and `export_route` map directly onto `POST /route`; `geocode`/`reverse_geocode` map onto the Nominatim helpers already written in `RouteUtils.kt`; `get_route_details` needs the extra path details from §3.2. There is no credits concept, so `kurviger://api/status` becomes `/health`. |

---

## 4. What is hard, and what is not reachable from here

### Not reachable

**Traffic-aware routing (F1).** There is no traffic data in this system and no free
source of it. GraphHopper OSS has no live traffic input; real coverage means a
commercial feed (TomTom, HERE, INRIX) priced for fleets. The "prefer low-traffic
roads" toggle could be faked by penalising major road classes, but that is a
curviness preference wearing a different label and would be dishonest to name
"traffic". **Recommend dropping this from the spec** rather than shipping an
approximation, which is the same call `FUTURE_FEATURES.md` already made for speed
limits and lane guidance.

**Worldwide coverage.** The spec's stated audience is "motorcyclists worldwide";
the graph is a UK extract. A planet import for GraphHopper is a days-long job
needing tens of GB of RAM and a large SSD, and the curvature pipeline would have to
process the planet too, on every weekly update. That is not a solo self-hosted
setup — it is an infrastructure project with a recurring bill. Either the product
stays UK/Europe-regional and says so, or routing moves to a hosted provider and the
curvature work is rebuilt on top of whatever that provider exposes. This is the
headline scaling constraint and it should be decided consciously, not discovered.

### Hard, with a specific unlock

**Everything elevation** — the F1 elevation profile, `ascend_m`/`descend_m` in the
spec's `Route` and `Ride` models, per-ride elevation gain in F8, and the Cruiser
profile's "avoid steep gradients" in F6.

The graph is imported without elevation: `graphhopper/config.yml` sets no
`graph.elevation.provider` and lists no elevation in `graph.encoded_values`, so
nothing comes back and `Route.elevationGain` is hardcoded `0.0`. `FUTURE_FEATURES.md`
already records this correctly.

The unlock is one config line plus a **full graph re-import** with an SRTM/CGIAR
provider, more disk for the DEM cache, and a re-verification pass. Call it 1–2 days
of work and a rebuild window. Deriving elevation from GPS altitude instead is not a
substitute — consumer GPS altitude is noisy enough that a flat ride accumulates
hundreds of false metres of "gain". Do the re-import, or do not ship the numbers.

**Heat map rendering on osmdroid.** osmdroid draws raster tiles and overlay objects;
it has no data-driven vector styling. A bbox of community-rated or curvature-scored
roads at a useful zoom is thousands of coloured line segments, and thousands of
`Polyline` objects will not hold a frame rate on a phone on a handlebar. It is
doable — one custom `Overlay` that draws the whole set in a single `onDraw` pass,
geometry simplified server-side per zoom level, a minimum zoom of about 11, and a
hard cap on returned segments — but it is real work and it is where the feature
will feel slow if it is done naively.

**"Open now" POI filtering (F3).** OSM `opening_hours` is a small grammar with
holidays, seasonal rules and `PH`/`SH` variants, and coverage on rural UK fuel
stations is patchy and often stale. `ch.poole:OpeningHoursParser` (the Java library
Vespucci uses) handles the parsing. Coverage it cannot fix. Ship it, but label
"open now" as "OSM says open" and always show closing time and last-edited data
where present — a rider diverting 15 km to a closed pump on a reserve tank is the
exact failure mode this feature exists to prevent.

**The road-rating cold start (F5).** The spec's own three-rating minimum means the
community heat map shows nothing at all until there is a user base — and a heat map
that is empty on first launch does not attract one. The curvature heat map in §3.2
solves this: it is dense from day one, needs no users, and uses data you already
compute. Community ratings should layer on top of it later, not launch as the
headline.

---

## 5. Suggested order

Each stage is shippable on its own and none of them blocks on the stage after it.

**A — local features, no backend work** *(~6–8 weeks)*
Room; ride recording; ride history and stats; HUD mode; bike profiles; GPX export;
saved routes and places. This is most of what a rider notices, and none of it needs
a server, an account or a bill.

**B — cheap backend, high leverage** *(~4–6 weeks)*
Expose `osm_way_id` intervals; curvy-roads heat map; speed limits where tagged;
weather proxy; POI import and fuel/food quick actions.

**C — the elevation re-import** *(~1 week, mostly waiting)*
Then the elevation profile, per-ride gain, and gradient-aware bike profiles all
become small features rather than blocked ones.

**D — tour planner** *(~4 weeks)*
Large, self-contained, and local-only until accounts exist.

**E — accounts and community** *(~3 months)*
Auth, sync, road ratings, community heat map. Only worth starting once A–D have
proven there are riders who want to sync something. Everything before this point is
reversible; this is not.

**Parallel, any time — the MCP server** *(~1–2 weeks)*
Independent of all of the above. Nothing else waits on it and it waits on nothing.

---

## 6. Two decisions worth taking deliberately

**Coverage.** UK-only self-hosted, or hosted routing and worldwide? Every estimate
above assumes the former. The latter changes the cost structure of the entire
project and should not be arrived at by accident partway through §5.

**osmdroid or MapLibre.** osmdroid is the right choice for what the app does today
and the offline-region work is built on it. But the heat map, the weather overlay
and any future layer selection all want data-driven vector styling, and each one
built on osmdroid adds custom-overlay code that a later migration throws away. If
two or more overlay features are definitely wanted, price the MapLibre Native
migration *before* building the first of them, not after the third.

---

## Appendix — every feature, rated

Every item in `improvements.md`, rated **1–10 for how confidently it can be built
into this app**: 10 is already shipped or trivial, 1 is not achievable on this
stack. The rating is confidence of delivery, not calendar cost — a 9 can still be a
month of work (see §3 for effort).

### Phase 1 — MCP server

A separate Node process, not app code. Ratings assume it targets MotoRiderMaps
rather than Kurviger, for the reason given in §3.

| Feature | Rating | Why |
|---|---|---|
| `create_route` | 10 | Direct map onto `POST /route` |
| `create_roundtrip` | 10 | `round_trip` already supported |
| `export_route` (GPX/GeoJSON) | 9 | Pure formatting; GPX carries no elevation |
| `geocode` / `reverse_geocode` | 8 | Nominatim works; its usage policy caps you at low volume |
| `get_route_details` | 6 | `average_speed`/`max_speed` are not in `graph.encoded_values` — needs a re-import or a curvature-DB join |
| `kurviger://api/status` | 9 | `/health` exists; there is no credits concept to report |
| stdio + HTTP transports, structured errors | 10 | Standard SDK work |

### F1 — Route planning

| Feature | Rating | Why |
|---|---|---|
| Multi-waypoint routes via search | 10 | Shipped |
| Waypoint placement by tapping the map | 8 | Not built — no map-tap handler exists; osmdroid `MapEventsOverlay` is easy |
| Curvature preference | 10 | Shipped, four levels rather than the spec's two |
| Avoidance options | 10 | Shipped, five |
| Alternative routes (distance/time) | 9 | Already returned ranked; needs comparison UI |
| Alternatives with elevation comparison | 3 | Blocked on the graph re-import, §4 |
| Roundtrip mode | 10 | Shipped, service-generated |
| Route preview: distance, duration | 10 | Shipped |
| Route preview: road types used | 8 | `road_class_details` already returned, unused |
| Elevation profile | 3 | No elevation in the graph at all |
| Non-circular out-and-back | 5 | `avoid_edges` is not a GraphHopper OSS parameter; needs custom-area penalties around leg 1 |
| **Traffic-aware routing** | **1** | No traffic data anywhere in the system, and no free source |
| GPX export | 9 | Straightforward |
| Save routes | 9 | Needs local storage (Room) |
| Mid-ride waypoint editing | 6 | Touches the most carefully tested code in the app |

### F2 — Guidance & live tracking

| Feature | Rating | Why |
|---|---|---|
| Route guidance, next-turn, distance-to-turn | 10 | Shipped |
| Real-time GPS on the polyline | 10 | Shipped |
| Off-route detection + reroute | 10 | Shipped |
| Background tracking, screen off | 10 | Shipped, wake lock and all |
| Ride recording (GPS, speed, timestamps) | 9 | Fixes already stream at 1 Hz; needs storage only |
| Ride recording — elevation | 3 | GPS altitude invents hundreds of false metres of gain |
| HUD mode | 9 | New Compose screen over existing `NavigationUIState` |

### F3 — Quick reroute, fuel & food

| Feature | Rating | Why |
|---|---|---|
| Nearest petrol station | 7 | Best served from POIs imported into PostGIS, not live Overpass |
| Nearest restaurant | 7 | Same |
| Top 3 with detour distance/time | 8 | Two extra `/route` calls |
| Resume to the next original waypoint | 7 | State-machine work in `NavigationManager` |
| "Open now" filtering by opening hours | 4 | Parsing is solvable; OSM coverage on rural UK fuel is patchy and stale, and that is not |

### F4 — Weather

| Feature | Rating | Why |
|---|---|---|
| Weather along route | 8 | Needs a backend proxy — the API key cannot ship in the APK |
| Weather at waypoints | 8 | Same |
| Temperature/precipitation timeline | 8 | Presentation |
| Weather warnings (rain/wind/temp/visibility) | 7 | Threshold tuning is the fiddly part |

### F5 — Road ratings & heat map

| Feature | Rating | Why |
|---|---|---|
| Rate the current road (GPS → OSM way ID) | 8 | Way IDs are already computed server-side, just not returned — about an hour to expose |
| Rating categories (enjoyment/surface/scenery) | 10 | Trivial once the above lands |
| Community aggregation | 8 technically | PostGIS is already there; gated entirely on accounts existing |
| Heat map overlay rendering | 5 | osmdroid has no vector styling; thousands of `Polyline`s will not hold a frame rate |
| Heat map on the route planner | 5 | Same ceiling |
| Minimum-3-ratings threshold | 10 | One line — and it is what guarantees an empty map at launch |
| A community actually forming | 3 | Not a technical rating. Answered by shipping the curvature heat map instead |

### F6 — Bike profiles

| Feature | Rating | Why |
|---|---|---|
| Four presets (Sport/ADV/Cruiser/Tourer) | 10 | A mapping onto existing curviness + avoidances |
| Persisted locally | 10 | SharedPreferences pattern already in use |
| Overridable per route | 9 | Planning sheet already carries per-leg overrides |
| "Allow unpaved", "prefer narrow" | 7 | `surface`, `road_class` and `track_type` encoded values exist; width does not — approximations only |
| "Avoid steep gradients" | 2 | Needs elevation; becomes roughly 7 after the re-import |

### F7 — Tour planner

| Feature | Rating | Why |
|---|---|---|
| Create tour | 9 | Needs no new data — a tour is a list of saved routes |
| Daily segments | 8 | |
| Overnight stops | 9 | |
| Daily distance targets + warnings | 9 | Arithmetic |
| Tour overview map + summary table | 8 | Colour-coded polylines are well within osmdroid |
| Day-by-day navigation | 8 | Sequencing over existing guidance |
| Export multi-track GPX | 8 | Falls out of the GPX export work |
| Tour reuse / duplicate | 9 | Storage |
| Weather preview per day | 5 | Forecasts run 5–8 days out. A tour planned for next spring can have climate averages only, and they must be labelled as such |

### F8 — Ride history

| Feature | Rating | Why |
|---|---|---|
| Automatic save with full trace | 9 | Room plus the existing fix stream |
| Stats: distance, duration, avg/max speed | 9 | All derive from the trace |
| Stats: elevation gain/loss | 3 | The same GPS-altitude problem |
| Ride list with sorting | 10 | Plain list |
| Ride detail with trace on map | 9 | `MotorcycleMapRenderer` already draws polylines |
| Route reuse from a past ride | 9 | Straightforward |

### Cross-cutting — data model, API, non-functional requirements

| Item | Rating | Why |
|---|---|---|
| Attribution (routing / OSM / weather) | 10 | Text and links |
| Units km/mi | 9 | Partly there already — `useMiles` and `distanceUnitMiles` exist |
| Settings screen | 9 | Drawer pattern established |
| PostGIS data model (routes/rides/ratings/tours) | 8 | Postgres is already running |
| Rate limiting | 8 | Currently none at all, but it is middleware |
| Offline queue + sync on reconnect | 7 | New infrastructure (WorkManager) |
| Account deletion / GDPR obligations | 7 | The code is easy; the policy and process work is not |
| User accounts + JWT auth | 6 | The tech is routine. The cost is turning a stateless service into a system of record |
| Cloud sync of routes and rides | 5 | Conflict resolution on top of all of the above |
| Route calculation < 3s | 9 | Already met for typical routes |
| GPS tracking at 1 Hz | 10 | Shipped |
| Cold start < 2s | 8 | Plausible; not measured |
| Heat map tiles < 500ms | 6 | Achievable server-side; the client is the bottleneck |
| POI queries < 2s | 6 via Overpass, 8 via own PostGIS | Public Overpass instances are unreliable mid-ride |
| **Worldwide coverage** | **2** | A UK extract today; a planet import is days, tens of GB of RAM, and a weekly curvature reprocess |

### Reading the low scores

Everything at 3 or below: traffic-aware routing (1), worldwide coverage (2),
gradient-aware bike profiles (2), and the elevation cluster — profile, per-ride
gain, alternatives comparison (3 each). Community formation (3) is a product
problem wearing a technical costume.

The elevation cluster is worth separating out, because it is **five separate spec
features blocked by one missing config line and a graph re-import**. Doing that
moves all five to 7–9. It is the highest-leverage single item in the document.

Traffic and worldwide coverage are the only two genuinely out of reach, and neither
is a code problem.
