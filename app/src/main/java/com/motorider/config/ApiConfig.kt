package com.motorider.config

import com.motorider.BuildConfig

object ApiConfig {
    /**
     * Base URL of the local MotoRiderMaps routing API.
     *
     * Supplied by the build type rather than hardcoded, because the correct value
     * differs per target: the emulator reaches the host at 10.0.2.2, a physical
     * device needs the host's LAN IP, and a release build must not ship either.
     * Override at build time:
     *
     *   ./gradlew installDebug -PmotoRiderDevApiBase=http://192.168.68.52:8080
     */
    const val ROUTING_API_BASE_URL: String = BuildConfig.ROUTING_API_BASE_URL

    const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"

    /**
     * Base URL for map tiles, supplied by the build type (see app/build.gradle).
     *
     * Defaults to public OSM. Override with -PmotoRiderTileBase=... to point at the
     * self-hosted tileserver-gl (MotoRiderMaps docker-compose `tileserver` service),
     * e.g. http://192.168.68.52:8081/styles/basic-preview - which serves the same
     * {z}/{x}/{y}.png slippy format and is not bound by OSM's bulk-download policy.
     */
    const val TILE_SERVER_BASE_URL: String = BuildConfig.TILE_SERVER_BASE_URL

    /** True when tiles come from public OSM rather than a self-hosted server. */
    val usingDefaultOsmTiles: Boolean
        get() = TILE_SERVER_BASE_URL.trimEnd('/') == "https://tile.openstreetmap.org"
}
