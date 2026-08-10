package com.motorider.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorider.R
import com.motorider.models.ManeuverType
import com.motorider.models.TurnInstruction
import com.motorider.ui.theme.BrandBlue
import com.motorider.ui.theme.onBrandColor
import com.motorider.ui.theme.ErrorRed
import com.motorider.ui.theme.AccentOrange

@Composable
fun TurnBanner(
    instruction: TurnInstruction?,
    /**
     * The manoeuvre straight after [instruction], when the two come close enough
     * together to be one action. Null the rest of the time — see
     * [com.motorider.navigation.NavigationUIState.followOnInstruction].
     */
    nextInstruction: TurnInstruction?,
    isOffRoute: Boolean,
    isGpsLost: Boolean,
    modifier: Modifier = Modifier
) {
    val bannerColor = when {
        isOffRoute -> ErrorRed
        isGpsLost -> ErrorRed
        instruction == null -> BrandBlue
        else -> getUrgencyColor(instruction.distanceToManeuver)
    }

    // The banner is painted a fixed brand colour, never colorScheme.primary, so its
    // foreground has to be derived from that colour rather than taken from
    // onPrimary. See onBrandColor for what went wrong when it wasn't.
    val onBanner = onBrandColor(bannerColor)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = bannerColor.copy(alpha = 0.95f),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedContent(
                targetState = instruction,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "instruction_animation"
            ) { targetInstruction ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DirectionArrow(
                        maneuverType = targetInstruction?.maneuverType,
                        bearing = targetInstruction?.bearing,
                        color = onBanner,
                        modifier = Modifier.size(56.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = getInstructionText(targetInstruction),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = onBanner,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (targetInstruction != null) {
                            Text(
                                text = formatDistance(targetInstruction.distanceToManeuver),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = onBanner
                            )
                        }
                    }
                }
            }

            // The "then" line. Suppressed while off route or without GPS: the
            // banner is reporting a problem at that point, and a confident
            // follow-on manoeuvre derived from a route the rider is not on would
            // be actively misleading.
            if (nextInstruction != null && !isOffRoute && !isGpsLost) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DirectionArrow(
                        maneuverType = nextInstruction.maneuverType,
                        bearing = nextInstruction.bearing,
                        color = onBanner,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    // "then" is a separate word from the instruction rather than a
                    // format string, so it never has to sit in front of a
                    // capitalised manoeuvre and read as "then Turn right".
                    Text(
                        text = stringResource(R.string.nav_then),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBanner.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = getInstructionText(nextInstruction),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = onBanner,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Both of these draw on top of a banner that is *already* ErrorRed when
            // they are showing — the previous ErrorRed text was invisible against
            // it, hiding the two states a rider most needs to see.
            if (isOffRoute) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.nav_off_route),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = onBanner
                )
            }

            if (isGpsLost) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.nav_gps_lost),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = onBanner
                )
            }
        }
    }
}

@Composable
private fun DirectionArrow(
    maneuverType: ManeuverType?,
    bearing: Double?,
    /** Foreground for the banner this arrow sits on — see `onBanner` in [TurnBanner]. */
    color: Color,
    modifier: Modifier = Modifier
) {
    val rotationAngle = when (maneuverType) {
        ManeuverType.TURN_LEFT -> -45.0
        ManeuverType.TURN_RIGHT -> 45.0
        ManeuverType.TURN_SLIGHT_LEFT -> -22.5
        ManeuverType.TURN_SLIGHT_RIGHT -> 22.5
        ManeuverType.UTURN -> 180.0
        ManeuverType.DEPART -> 0.0
        ManeuverType.ARRIVE -> 0.0
        ManeuverType.WAYPOINT_ARRIVED -> 0.0
        ManeuverType.CONTINUE -> 0.0
        null -> 0.0
    }

    val arrowColor = color

    Canvas(modifier = modifier) {
        rotate(rotationAngle.toFloat()) {
            val arrowH = size.minDimension * 0.4f
            val arrowBaseY = size.height * 0.7f
            val halfW = arrowH * 0.35f
            val arrowPath = Path().apply {
                moveTo(size.width / 2f, arrowBaseY - arrowH)
                lineTo(size.width / 2f - halfW, arrowBaseY)
                lineTo(size.width / 2f + halfW, arrowBaseY)
                close()
            }
            drawPath(path = arrowPath, color = arrowColor, style = Stroke(width = 3.dp.toPx()))
            drawPath(path = arrowPath, color = arrowColor)
        }
    }
}

private fun getUrgencyColor(distanceToManeuver: Double): Color {
    return when {
        distanceToManeuver < 200 -> ErrorRed
        distanceToManeuver < 500 -> AccentOrange
        else -> BrandBlue
    }
}

/**
 * The manoeuvre, in the rider's language.
 *
 * [TurnInstruction.instruction] carries an English string built in [com.motorider.utils.RouteUtils],
 * which has no `Context` and is unit-tested on the JVM — deliberately, so the
 * geometry stays testable. The type is the durable fact; the words for it belong
 * here, where resources are available. The arrival cases keep the generated text
 * because it names the actual waypoint ("Arrive at Buxton"), which no fixed string
 * can supply.
 */
@Composable
private fun getInstructionText(instruction: TurnInstruction?): String {
    if (instruction == null) return stringResource(R.string.screen_navigation)

    return when (instruction.maneuverType) {
        ManeuverType.DEPART -> stringResource(R.string.waypoint_start)
        ManeuverType.CONTINUE -> stringResource(R.string.nav_continue)
        ManeuverType.TURN_LEFT -> stringResource(R.string.nav_turn_left)
        ManeuverType.TURN_RIGHT -> stringResource(R.string.nav_turn_right)
        ManeuverType.TURN_SLIGHT_LEFT -> stringResource(R.string.nav_turn_slight_left)
        ManeuverType.TURN_SLIGHT_RIGHT -> stringResource(R.string.nav_turn_slight_right)
        ManeuverType.UTURN -> stringResource(R.string.nav_u_turn)
        ManeuverType.ARRIVE, ManeuverType.WAYPOINT_ARRIVED ->
            instruction.instruction.ifBlank { stringResource(R.string.nav_arrive) }
    }
}

/**
 * Distance to the manoeuvre, rounded the way road signs are.
 *
 * Metres are rounded to 10 below a kilometre: a banner counting "437 m, 431 m,
 * 428 m" is noise on a bike, and the last digit is inside GPS error anyway.
 */
private fun formatDistance(distanceToManeuver: Double): String {
    return if (distanceToManeuver < 1000) {
        val rounded = (distanceToManeuver / 10.0).toInt() * 10
        "$rounded m"
    } else {
        "${"%.1f".format(distanceToManeuver / 1000)} km"
    }
}
