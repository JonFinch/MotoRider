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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorider.models.ManeuverType
import com.motorider.models.TurnInstruction
import com.motorider.ui.theme.BrandBlue
import com.motorider.ui.theme.ErrorRed
import com.motorider.ui.theme.AccentOrange

@Composable
fun TurnBanner(
    instruction: TurnInstruction?,
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
                        modifier = Modifier.size(56.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = getInstructionText(targetInstruction),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (targetInstruction != null) {
                            Text(
                                text = formatDistance(targetInstruction.distanceToManeuver),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }

            if (isOffRoute) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "You're off route",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = ErrorRed
                )
            }

            if (isGpsLost) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "GPS signal lost",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = ErrorRed
                )
            }
        }
    }
}

@Composable
private fun DirectionArrow(
    maneuverType: ManeuverType?,
    bearing: Double?,
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

    val arrowColor = when (maneuverType) {
        ManeuverType.ARRIVE, ManeuverType.WAYPOINT_ARRIVED -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimary
    }

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

private fun getInstructionText(instruction: TurnInstruction?): String {
    if (instruction == null) return "Navigate"

    return when (instruction.maneuverType) {
        ManeuverType.DEPART -> "Start"
        ManeuverType.CONTINUE -> "Continue"
        ManeuverType.TURN_LEFT -> "Turn left"
        ManeuverType.TURN_RIGHT -> "Turn right"
        ManeuverType.TURN_SLIGHT_LEFT -> "Turn slight left"
        ManeuverType.TURN_SLIGHT_RIGHT -> "Turn slight right"
        ManeuverType.UTURN -> "U-turn"
        ManeuverType.ARRIVE -> "Arrive at destination"
        ManeuverType.WAYPOINT_ARRIVED -> "Arrive at waypoint"
    }
}

private fun formatDistance(distanceToManeuver: Double): String {
    return if (distanceToManeuver < 1000) {
        "${"%.0f".format(distanceToManeuver)} m"
    } else {
        "${"%.1f".format(distanceToManeuver / 1000)} km"
    }
}
