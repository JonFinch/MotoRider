package com.motorider.config

import android.util.Base64
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
     * Mid-ride fuel and food search, served by MotoRiderMaps from the same OSM
     * import that backs routing — no third-party POI service involved.
     *
     * Derived from [ROUTING_API_BASE_URL] rather than configured separately,
     * because it is the same host: the routing API proxies these to the curvature
     * API, which owns the PostGIS connection, so the app only ever knows one base
     * URL and the database-facing service never has to be exposed.
     */
    val POI_CORRIDOR_URL: String get() = "$ROUTING_API_BASE_URL/poi/corridor"
    val POI_NEARBY_URL: String get() = "$ROUTING_API_BASE_URL/poi/nearby"

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

    /**
     * `Authorization` header for MotoRiderMaps, or null when no credential is
     * configured.
     *
     * Null is a supported state rather than a misconfiguration: a local
     * docker-compose stack has no proxy in front of it and answers unauthenticated,
     * so a debug build against 10.0.2.2 needs no credential and should send none.
     * Callers omit the header entirely when this is null — sending `Basic` with an
     * empty pair would turn a working local setup into a 401.
     *
     * Computed per call rather than cached in a field so the credential is not held
     * in memory for the life of the process any longer than it must be. This is a
     * small thing next to the fact that it is in the APK at all, but it costs
     * nothing.
     */
    val authorizationHeader: String?
        get() {
            val user = BuildConfig.API_USERNAME
            val password = BuildConfig.API_PASSWORD
            if (user.isBlank() || password.isBlank()) return null
            val encoded = Base64.encodeToString(
                "$user:$password".toByteArray(Charsets.UTF_8),
                // NO_WRAP: the default inserts newlines every 76 characters, which
                // in a header value produces a malformed request rather than a 401,
                // and so fails in a way that does not look like an auth problem.
                Base64.NO_WRAP
            )
            return "Basic $encoded"
        }

    /** True when a credential was supplied at build time. */
    private val hasCredential: Boolean
        get() = BuildConfig.API_USERNAME.isNotBlank() && BuildConfig.API_PASSWORD.isNotBlank()

    /**
     * Whether a request to [url] may carry our credential.
     *
     * The app talks to three hosts — MotoRiderMaps, public OSM tiles and Nominatim —
     * and only the first should ever see the Authorization header. Deciding it here,
     * against the configured bases, means a new call site cannot leak the credential
     * by forgetting to check.
     */
    fun mayAuthenticate(url: String): Boolean =
        hasCredential && isOwnEndpoint(
            url = url,
            apiBase = ROUTING_API_BASE_URL,
            tileBase = TILE_SERVER_BASE_URL,
            usingDefaultTiles = usingDefaultOsmTiles
        )

    /**
     * Whether [url] belongs to one of our own services.
     *
     * Kept free of Android and BuildConfig so the rule that decides who sees the
     * credential can be tested on the JVM — see `ApiConfigTest`.
     *
     * Matching is deliberately not a bare `startsWith`. A prefix test alone accepts
     * `https://our.host.evil.com/`, which shares the prefix of `https://our.host`
     * but is somebody else's server entirely — and would hand them the credential.
     * The base must be followed by a path separator or be the whole URL.
     */
    internal fun isOwnEndpoint(
        url: String,
        apiBase: String,
        tileBase: String,
        usingDefaultTiles: Boolean
    ): Boolean {
        fun matches(base: String): Boolean {
            val trimmed = base.trimEnd('/')
            if (trimmed.isEmpty()) return false
            if (!url.startsWith(trimmed)) return false
            val remainder = url.substring(trimmed.length)
            return remainder.isEmpty() || remainder.startsWith("/") || remainder.startsWith("?")
        }
        // Public OSM tiles are somebody else's server, so they are excluded even
        // though they are "configured" — the credential is for our host alone.
        return matches(apiBase) || (!usingDefaultTiles && matches(tileBase))
    }
}
