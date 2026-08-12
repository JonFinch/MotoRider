package com.motorider.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorider.R
import com.motorider.models.NavigationWarning
import com.motorider.models.TurnInstruction
import com.motorider.models.WarningSeverity
import com.motorider.navigation.NavigationState
import com.motorider.ui.component.CompactSpeedometer
import com.motorider.ui.component.TurnBanner
import java.util.Calendar

/**
 * Turn-by-turn guidance, drawn as an overlay over the live map.
 *
 * Deliberately transparent through the middle: the map underneath is doing the
 * work of showing the rider where they are, and boxing it in would defeat the
 * point. Controls sit top and bottom where thumbs reach on a mounted phone.
 *
 * All distances arrive in metres and all times in seconds — the SI units
 * NavigationManager publishes — and are converted for display here.
 */
@Composable
fun NavigationScreen(
    navigationState: NavigationState,
    distanceRemaining: Double,
    timeRemaining: Double,
    /** Fraction of the route ridden, 0..1. */
    progress: Double,
    eta: Long?,
    currentSpeed: Float,
    currentInstruction: TurnInstruction?,
    /** Shown as a "then …" line when it follows [currentInstruction] closely. */
    followOnInstruction: TurnInstruction?,
    navigationWarnings: List<NavigationWarning>,
    isOffRoute: Boolean,
    isGpsLost: Boolean,
    isRecalculating: Boolean,
    isTtsEnabled: Boolean,
    /**
     * No data connection. Shown here rather than as a floating banner so it cannot
     * land on top of the turn instruction, and because losing signal mid-ride is
     * exactly when the rider needs to know: recalculation will fail and only cached
     * tiles will draw.
     */
    isOffline: Boolean = false,
    skipAvailableWaypointName: String?,
    /** True while the fuel/food card is showing, which replaces the quick actions. */
    poiPickerOpen: Boolean = false,
    /**
     * The fuel/food card, rendered inside this screen's bottom column rather than
     * floated over it.
     *
     * It has to be a slot: aligned independently to BottomCenter it landed on top
     * of the speedometer, the distance remaining and the End Ride button — hiding
     * the ride's vital signs and its exits behind a list of petrol stations. In
     * the column it takes its turn above the controls instead.
     */
    poiPicker: @Composable () -> Unit = {},
    onToggleTts: () -> Unit,
    onBackToPlanning: () -> Unit,
    onPauseNavigation: () -> Unit,
    onEndNavigation: () -> Unit = {},
    onSkipWaypoint: () -> Unit = {},
    onFindFuel: () -> Unit = {},
    onFindFood: () -> Unit = {},
    distanceUnitMiles: Boolean = true
) {
    fun formatDistance(meters: Double): String = if (distanceUnitMiles) {
        val miles = meters / 1609.344
        if (miles < 0.1) "${meters.toInt()} m" else "%.1f mi".format(miles)
    } else {
        if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000.0)
    }

    fun formatEta(etaMillis: Long?): String? {
        if (etaMillis == null || etaMillis <= 0) return null
        val calendar = Calendar.getInstance().apply { timeInMillis = etaMillis }
        return "%02d:%02d".format(
            calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
        )
    }

    fun formatTime(seconds: Double): String {
        val minutes = (seconds / 60.0).toInt()
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }

    Box(modifier = Modifier.fillMaxSize()) {

        if (navigationState == NavigationState.ARRIVED) {
            ArrivedPanel(onBackToPlanning = onBackToPlanning)
            return@Box
        }

        // ─── Top: the next manoeuvre ─────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            TurnBanner(
                instruction = currentInstruction,
                nextInstruction = followOnInstruction,
                isOffRoute = isOffRoute,
                isGpsLost = isGpsLost,
                modifier = Modifier.fillMaxWidth()
            )

            AnimatedVisibility(
                visible = isRecalculating,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                Surface(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ) {
                    Text(
                        stringResource(R.string.nav_recalculating),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            navigationWarnings.forEach { warning ->
                val (container, content) = warning.severity.colors()
                WarningBanner(
                    message = warning.message,
                    containerColor = container,
                    contentColor = content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                )
            }

            if (isOffline) {
                WarningBanner(
                    message = stringResource(R.string.offline_indicator),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                )
            }
        }

        // ─── Bottom: speed, progress and controls ────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var showSkipConfirm by remember { mutableStateOf(false) }

            // Fuel and food, one tap each. Deliberately above the ride controls and
            // deliberately not behind a menu: the moment a rider wants these is the
            // moment they least want to go looking, and a category picker in front
            // of the answer would cost the tap the buttons exist to save.
            //
            // Hidden while the picker is open — the card it opens lands in this
            // same strip of screen.
            // The card takes the quick actions' place in the column rather than
            // covering the controls below it.
            poiPicker()

            // Only while actually riding: paused is a deliberate "I have stopped",
            // and diverting a ride that is not running is not a quick action.
            if (!poiPickerOpen && navigationState == NavigationState.NAVIGATING) {
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickStopButton(
                        icon = Icons.Outlined.LocalGasStation,
                        label = stringResource(R.string.poi_fuel),
                        onClick = onFindFuel
                    )
                    QuickStopButton(
                        icon = Icons.Outlined.Restaurant,
                        label = stringResource(R.string.poi_food),
                        onClick = onFindFood
                    )
                }
            }

            if (skipAvailableWaypointName != null) {
                if (showSkipConfirm) {
                    AlertDialog(
                        onDismissRequest = { showSkipConfirm = false },
                        title = {
                            Text(stringResource(R.string.nav_skip_confirmation, skipAvailableWaypointName))
                        },
                        text = { Text(stringResource(R.string.nav_skip_confirmation_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                onSkipWaypoint()
                                showSkipConfirm = false
                            }) {
                                Text(stringResource(R.string.nav_skip, skipAvailableWaypointName))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSkipConfirm = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shadowElevation = 4.dp,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    TextButton(onClick = { showSkipConfirm = true }) {
                        Icon(Icons.Default.SkipNext, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.nav_skip, skipAvailableWaypointName),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // How much of the ride is behind them. NavigationManager has
                    // always published this; nothing rendered it, so the only sense
                    // of progress was watching a distance count down.
                    LinearProgressIndicator(
                        progress = { progress.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        CompactSpeedometer(
                            currentSpeed = currentSpeed,
                            useMiles = distanceUnitMiles
                        )

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                formatDistance(distanceRemaining),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                formatTime(timeRemaining),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            formatEta(eta)?.let { etaText ->
                                Text(
                                    stringResource(R.string.nav_arrive_at_time, etaText),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onToggleTts) {
                            Icon(
                                imageVector = if (isTtsEnabled) Icons.AutoMirrored.Filled.VolumeUp
                                              else Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = if (isTtsEnabled) {
                                    stringResource(R.string.nav_tts_enabled)
                                } else {
                                    stringResource(R.string.nav_tts_disabled)
                                },
                                tint = if (isTtsEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        when (navigationState) {
                            NavigationState.PAUSED -> {
                                Button(
                                    onClick = onPauseNavigation,
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.nav_resume), fontWeight = FontWeight.SemiBold)
                                }
                            }
                            else -> {
                                OutlinedButton(
                                    onClick = onPauseNavigation,
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Pause, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.nav_pause), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        Button(
                            // Ending the ride is the only thing this does. It used
                            // to call onBackToPlanning() as well, which in MapScreen
                            // is the same stopNavigating() — tearing the service
                            // down twice on one tap.
                            onClick = onEndNavigation,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            // contentColor has to be named too. buttonColors only
                            // replaces what it is given, and its default content
                            // colour is onPrimary — which in the dark scheme is a
                            // dark blue, so "End Ride" was drawn blue-on-red.
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.nav_end_ride), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One of the two mid-ride quick actions.
 *
 * A filled tonal surface rather than an outlined button: these sit over live map
 * tiles, and an outline alone gives no guaranteed contrast against whatever
 * happens to be underneath.
 */
@Composable
private fun QuickStopButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 4.dp
    ) {
        TextButton(onClick = onClick, modifier = Modifier.height(44.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Arrival, as a card over the map rather than a screen that replaces it.
 *
 * Painting an opaque surface across the whole display hid the map at the one
 * moment a rider most wants it: they have arrived somewhere, and "where exactly
 * am I now" is the next question. The card sits at the bottom, out of the way of
 * the destination marker.
 */
@Composable
private fun BoxScope.ArrivedPanel(onBackToPlanning: () -> Unit) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                null,
                Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.arrived),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onBackToPlanning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Home, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.back_to_planning), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Container and content for a warning of this severity.
 *
 * A pair rather than a single tint, because the previous version drew the message
 * *in* the severity colour on a plain surface — and the warning tint is the vivid
 * brand orange, which is a fill colour. As text on a light surface it managed
 * 3.79:1. Filling the banner with the container tone and writing on it in the
 * matching `on` tone keeps the colour coding and gets 13:1.
 */
@Composable
private fun WarningSeverity.colors(): Pair<Color, Color> = when (this) {
    WarningSeverity.CRITICAL ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    WarningSeverity.WARNING ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    WarningSeverity.INFO ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun WarningBanner(
    message: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, null, tint = contentColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
