# MotoRider - Motorcycle Travel Application

This is an Android application for motorcycle travel with routing capabilities similar to Kurviger and Calimoto.

## Features
- Motorcycle-specific route planning
- OpenStreetMap integration
- Curvy road optimization
- Elevation profile visualization
- Offline map support

## Architecture
The application follows standard Android architecture with:
- MVVM pattern for clean separation of concerns
- Repository pattern for data management
- Room database for local storage
- Google Maps / OSM integration for map display
- Custom routing algorithms for motorcycle-specific routes

## Development Setup
1. Install Android Studio
2. Clone this repository
3. Open in Android Studio
4. Build and run the application

## Project Structure
- `app/src/main/java/com/motorider/` - Main application code
  - `activities/` - Android Activity classes
  - `fragments/` - Fragment components
  - `models/` - Data models (Route, Waypoint)
  - `services/` - Background services (RouteService, NavigationService)
  - `utils/` - Utility classes (RouteUtils)
  - `maps/` - Map rendering components (MotorcycleMapRenderer)
- `app/src/main/res/` - Resource files (layouts, values, drawables)
- `app/src/test/` - Unit tests
- `app/src/androidTest/` - Instrumentation tests

## Key Dependencies
- OpenStreetMap (osmdroid) for map rendering
- GraphHopper for routing calculations
- AndroidX libraries for modern Android development

## Building the Project
```bash
./gradlew assembleDebug
```

## Running Tests
```bash
./gradlew test
./gradlew connectedAndroidTest
```