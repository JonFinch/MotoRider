# MotoRider

An Android application for motorcycle-specific route planning and navigation, inspired by apps like Kurviger and Calimoto.

## Overview

MotoRider helps motorcyclists plan and follow routes optimized for the riding experience, focusing on curvy roads and scenic routes. The app uses OpenStreetMap data for map rendering and GraphHopper for routing calculations.

## Features

- **Motorcycle-specific routing** - Route optimization tailored for motorcycles with curvature scoring
- **OpenStreetMap integration** - Uses osmdroid for offline-capable map rendering
- **Curvature scoring** - Algorithms to evaluate and prioritize scenic, winding roads
- **Turn-by-turn navigation** - Foreground navigation service with persistent notifications
- **Multi-vehicle support** - Routing profiles for motorcycles, trucks, cars, and bikes

## Tech Stack

- **Language:** Java
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 36
- **Map Rendering:** osmdroid 6.1.20 (OpenStreetMap)
- **Routing:** GraphHopper 4.0
- **UI:** Material Components, AndroidX

## Project Structure

```
app/src/main/java/com/motorider/
├── MotoRiderApplication.java   # Application class, osmdroid initialization
├── activities/
│   └── MainActivity.java       # Main activity, hosts MapFragment
├── fragments/
│   └── MapFragment.java        # Map view, route planning UI, location handling
├── maps/
│   └── MotorcycleMapRenderer.java  # Renders motorcycle routes on the map
├── models/
│   ├── Route.java              # Route data (distance, duration, curvature, elevation)
│   ├── RouteType.java          # Vehicle type enum (Motorcycle, Truck, Car, Bike)
│   └── Waypoint.java           # Route waypoints with curvature and elevation data
├── services/
│   ├── NavigationService.java  # Foreground navigation service with notifications
│   └── RouteService.java       # Motorcycle routing calculation logic
└── utils/
    └── RouteUtils.java         # Curvature and elevation calculation utilities
```

## Getting Started

1. Open the project in Android Studio
2. Sync Gradle files
3. Build and run on an emulator or device

## Building

```bash
./gradlew assembleDebug
```

## Running Tests

```bash
./gradlew test
./gradlew connectedAndroidTest
```
