package com.motorider.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motorider.R
import com.motorider.models.PoiCategory
import com.motorider.models.PoiResult
import com.motorider.ui.viewmodel.PoiSearchState

/**
 * The mid-ride fuel/food picker, as a card over the map.
 *
 * Everything about it is sized for a rider who has pulled up or is stopped at a
 * junction: three options at most, each a single row tall enough to hit in gloves,
 * and no scrolling. A longer list would be a list nobody reads.
 *
 * It deliberately never says "open now". OSM's opening-hours coverage on rural
 * forecourts is patchy and often years stale, so the hours are shown exactly as
 * tagged and their absence is stated rather than papered over — a rider on reserve
 * can then decide for themselves whether to gamble on it.
 */
@Composable
fun PoiPicker(
    state: PoiSearchState,
    formatDistance: (Double) -> String,
    onSelect: (PoiResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state is PoiSearchState.Closed) return

    val category = when (state) {
        is PoiSearchState.Searching -> state.category
        is PoiSearchState.Results -> state.category
        is PoiSearchState.Empty -> state.category
        is PoiSearchState.Failed -> state.category
        PoiSearchState.Closed -> return
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        category.icon(),
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(state.headingRes()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.poi_close), Modifier.size(20.dp))
                }
            }

            when (state) {
                is PoiSearchState.Searching -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.poi_searching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is PoiSearchState.Results -> {
                    if (!state.onRoute) {
                        // The rider asked for something on their way and there
                        // wasn't one. Saying so is the whole value of the message:
                        // silently listing detours would read as "these are ahead".
                        Text(
                            stringResource(R.string.poi_none_on_route),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                    }

                    state.results.forEachIndexed { index, poi ->
                        if (index > 0) HorizontalDivider()
                        PoiRow(poi, formatDistance, onSelect)
                    }
                }

                is PoiSearchState.Empty -> Message(stringResource(R.string.poi_empty))

                is PoiSearchState.Failed -> Message(
                    stringResource(R.string.poi_failed_fmt, state.message)
                )

                PoiSearchState.Closed -> Unit
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One option.
 *
 * The distance shown is the one that answers the rider's actual question: for a
 * stop on the route it is how far along the road it is, and for a detour it is how
 * far away it is as the crow flies — labelled as such, because the riding distance
 * to it is always more and sometimes much more.
 */
@Composable
private fun PoiRow(
    poi: PoiResult,
    formatDistance: (Double) -> String,
    onSelect: (PoiResult) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(poi) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                poi.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Schedule,
                    null,
                    Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    poi.openingHours ?: stringResource(R.string.poi_hours_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatDistance(poi.distanceAlongRouteMeters ?: poi.directDistanceMeters),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(
                    if (poi.isOnRoute) R.string.poi_ahead else R.string.poi_direct_line
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PoiCategory.icon() = when (this) {
    PoiCategory.FUEL -> Icons.Outlined.LocalGasStation
    PoiCategory.RESTAURANT -> Icons.Outlined.Restaurant
}

/**
 * The heading has to stop saying "ahead" the moment the results are not.
 *
 * Correcting it only in the body line left the headline asserting the one thing
 * the app had just established was false — the rider reads the big text.
 */
private fun PoiSearchState.headingRes(): Int = when (this) {
    is PoiSearchState.Searching -> category.headingRes()
    is PoiSearchState.Results -> if (onRoute) category.headingRes() else category.nearbyHeadingRes()
    is PoiSearchState.Empty -> category.headingRes()
    is PoiSearchState.Failed -> category.headingRes()
    PoiSearchState.Closed -> R.string.poi_fuel_heading
}

private fun PoiCategory.headingRes(): Int = when (this) {
    PoiCategory.FUEL -> R.string.poi_fuel_heading
    PoiCategory.RESTAURANT -> R.string.poi_food_heading
}

private fun PoiCategory.nearbyHeadingRes(): Int = when (this) {
    PoiCategory.FUEL -> R.string.poi_fuel_heading_nearby
    PoiCategory.RESTAURANT -> R.string.poi_food_heading_nearby
}
