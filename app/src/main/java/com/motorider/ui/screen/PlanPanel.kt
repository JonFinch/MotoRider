package com.motorider.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motorider.R
import com.motorider.models.Avoidance
import com.motorider.models.RouteType
import com.motorider.ui.component.LocationPickerDialog
import com.motorider.ui.component.RouteStop
import com.motorider.ui.component.StopKind
import com.motorider.ui.component.StopRow
import org.osmdroid.util.GeoPoint

/**
 * The route the rider is assembling: where they start, where they are going, and
 * anywhere they want to go via.
 *
 * Held as one value so a plan survives the panel leaving composition — switching
 * screens, or planning and tapping Edit to come back — and so the coordinates
 * resolved when a place was picked travel with it.
 */
data class PlanDraft(
    val start: RouteStop = RouteStop(),
    val destination: RouteStop = RouteStop(),
    val via: List<RouteStop> = emptyList()
) {
    val isPlannable: Boolean get() = start.isSet && destination.isSet

    /** Start, then any via points, then the destination — the order they are ridden. */
    val ordered: List<RouteStop> get() = listOf(start) + via + destination
}

/**
 * The route-planning sheet.
 *
 * Every stop is a [StopRow] opening a full-screen picker rather than an inline text
 * field: see [com.motorider.ui.component.LocationPickerDialog] for why. The Find
 * route button is pinned below the scroll area, so it is reachable without scrolling
 * however many stops the rider adds.
 */
@Composable
fun BoxScope.PlanPanel(
    draft: PlanDraft,
    onDraftChange: (PlanDraft) -> Unit,
    onDismiss: () -> Unit,
    currentLocation: GeoPoint?,
    isOffline: Boolean,
    legPrefs: List<RouteType>,
    avoidances: Set<Avoidance>,
    isBusy: Boolean,
    onLegPrefsChange: (List<RouteType>) -> Unit,
    onAvoidancesChange: (Set<Avoidance>) -> Unit,
    onPlanRoute: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var showPreferenceDialog by remember { mutableStateOf(false) }
    var showAvoidanceDialog by remember { mutableStateOf(false) }
    var editingLegIndex by remember { mutableIntStateOf(0) }
    // Which stop the picker is currently filling in; null means it is closed.
    var picking by remember { mutableStateOf<Pair<StopKind, Int>?>(null) }

    val currentLocationLabel = stringResource(R.string.current_location)

    val prefs = remember { mutableStateListOf<RouteType>() }
    LaunchedEffect(legPrefs) {
        if (prefs.toList() != legPrefs) {
            prefs.clear()
            prefs.addAll(legPrefs.ifEmpty { listOf(RouteType.DIRECT) })
        }
    }
    // One leg per gap between stops. Resized here rather than at every call site so
    // adding a via point cannot leave the list out of step with the route.
    LaunchedEffect(draft.via.size) {
        val needed = 1 + draft.via.size
        var changed = false
        while (prefs.size < needed) { prefs.add(prefs.lastOrNull() ?: RouteType.DIRECT); changed = true }
        while (prefs.size > needed) { prefs.removeAt(prefs.size - 1); changed = true }
        if (changed) onLegPrefsChange(prefs.toList())
    }

    Surface(
        modifier = Modifier.align(Alignment.BottomCenter),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 12.dp,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            SheetGrip(onDismiss)

            Column(
                // fill = false so the sheet still hugs its content on a short plan;
                // the weight only bites once the content would overflow, and then it
                // is the scroll area that gives, never the Find route button.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Stops ──
                StopRow(
                    kind = StopKind.START,
                    index = 0,
                    stop = draft.start,
                    onClick = { picking = StopKind.START to 0 },
                    onClear = { onDraftChange(draft.copy(start = RouteStop())) }
                )

                draft.via.forEachIndexed { i, stop ->
                    Spacer(Modifier.height(6.dp))
                    StopRow(
                        kind = StopKind.VIA,
                        index = i,
                        stop = stop,
                        onClick = { picking = StopKind.VIA to i },
                        onClear = {
                            onDraftChange(draft.copy(via = draft.via.filterIndexed { j, _ -> j != i }))
                        }
                    )
                }

                Spacer(Modifier.height(6.dp))
                StopRow(
                    kind = StopKind.DESTINATION,
                    index = 0,
                    stop = draft.destination,
                    onClick = { picking = StopKind.DESTINATION to 0 },
                    onClear = { onDraftChange(draft.copy(destination = RouteStop())) }
                )

                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = { onDraftChange(draft.copy(via = draft.via + RouteStop())) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.add_stop), color = MaterialTheme.colorScheme.primary)
                    }
                    // Riding a route back the other way is a different ride, and
                    // re-entering both ends to get it was the most obvious thing
                    // missing from the old sheet.
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDraftChange(
                                draft.copy(
                                    start = draft.destination,
                                    destination = draft.start,
                                    via = draft.via.reversed()
                                )
                            )
                        },
                        enabled = draft.start.isSet || draft.destination.isSet
                    ) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = stringResource(R.string.swap_start_end),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))

                // ── Ride style ──
                Text(
                    stringResource(R.string.ride_style),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                val allLegsSame = prefs.distinct().size <= 1
                val shownStyle = if (allLegsSame) prefs.firstOrNull() else null
                RideStyleSelector(shownStyle) { style ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    for (i in prefs.indices) prefs[i] = style
                    onLegPrefsChange(prefs.toList())
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    shownStyle?.let { stringResource(rideStyleDescription(it)) }
                        ?: stringResource(R.string.mixed_leg_styles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (prefs.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        prefs.forEachIndexed { i, pref ->
                            LegChip(stringResource(R.string.leg_fmt, i + 1), pref) {
                                editingLegIndex = i
                                showPreferenceDialog = true
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                AvoidanceSummaryRow(avoidances) { showAvoidanceDialog = true }
                Spacer(Modifier.height(16.dp))
            }

            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPlanRoute()
                },
                enabled = draft.isPlannable && !isBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.finding_route),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                } else {
                    Icon(Icons.Outlined.Route, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.find_route),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }

    picking?.let { (kind, index) ->
        val existing = when (kind) {
            StopKind.START -> draft.start
            StopKind.DESTINATION -> draft.destination
            StopKind.VIA -> draft.via.getOrNull(index) ?: RouteStop()
        }
        LocationPickerDialog(
            kind = kind,
            index = index,
            // A stop that follows the rider has no query text worth pre-filling.
            initialQuery = if (existing.isCurrentLocation) "" else existing.label,
            currentLocation = currentLocation,
            isOffline = isOffline,
            onPick = { label, point, isCurrent ->
                val stop = RouteStop(
                    label = if (isCurrent) currentLocationLabel else label,
                    point = point,
                    isCurrentLocation = isCurrent
                )
                onDraftChange(
                    when (kind) {
                        StopKind.START -> draft.copy(start = stop)
                        StopKind.DESTINATION -> draft.copy(destination = stop)
                        StopKind.VIA -> draft.copy(
                            via = draft.via.toMutableList().also {
                                if (index in it.indices) it[index] = stop else it.add(stop)
                            }
                        )
                    }
                )
                picking = null
            },
            onDismiss = { picking = null }
        )
    }

    if (showPreferenceDialog) {
        PreferenceDialog(
            prefs.getOrElse(editingLegIndex) { RouteType.DIRECT },
            onSelect = {
                if (editingLegIndex in prefs.indices) prefs[editingLegIndex] = it
                onLegPrefsChange(prefs.toList())
                showPreferenceDialog = false
            },
            onDismiss = { showPreferenceDialog = false }
        )
    }
    if (showAvoidanceDialog) {
        AvoidanceDialog(
            avoidances,
            onUpdate = {
                onAvoidancesChange(it)
                showAvoidanceDialog = false
            },
            onDismiss = { showAvoidanceDialog = false }
        )
    }
}

/**
 * The standard bottom-sheet grip. Dragging it down past a small threshold, or
 * tapping it, stows the sheet; [PlanPeekHandle] then takes its place.
 */
@Composable
private fun SheetGrip(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragEnd = { dragged = 0f },
                    onDragCancel = { dragged = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    dragged += dragAmount
                    if (dragged > 48f) {
                        onDismiss()
                        dragged = 0f
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

/**
 * Compact handle shown in the bottom slot when the planning sheet is stowed. Tapping
 * it (or dragging up) restores the full [PlanPanel]. Deliberately small so the map
 * stays almost fully visible while it is up.
 */
@Composable
fun BoxScope.PlanPeekHandle(onReveal: () -> Unit) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragEnd = { dragged = 0f },
                    onDragCancel = { dragged = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    dragged += dragAmount
                    if (dragged < -32f) {
                        onReveal()
                        dragged = 0f
                    }
                }
            }
            .clickable(onClick = onReveal),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 8.dp,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.show_planner),
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ─── Ride style and avoidances ──────────────────────────────────────────────

/**
 * Icon and tint per ride style. The tints escalate through the scheme's own roles
 * rather than four hand-picked brand colours, so each one is a pairing the contrast
 * audit already covers — and so they invert correctly at night.
 */
@Composable
fun rideStyleIcon(pref: RouteType): Pair<ImageVector, Color> = when (pref) {
    RouteType.DIRECT -> Icons.Outlined.Speed to MaterialTheme.colorScheme.onSurfaceVariant
    RouteType.FAST -> Icons.Outlined.Navigation to MaterialTheme.colorScheme.primary
    RouteType.CURVY -> Icons.Outlined.Timeline to MaterialTheme.colorScheme.secondary
    RouteType.EXTRA_CURVY -> Icons.Outlined.Landscape to MaterialTheme.colorScheme.error
}

fun rideStyleDescription(pref: RouteType): Int = when (pref) {
    RouteType.DIRECT -> R.string.direct_desc
    RouteType.FAST -> R.string.fast_desc
    RouteType.CURVY -> R.string.curvy_desc
    RouteType.EXTRA_CURVY -> R.string.extra_curvy_desc
}

@Composable
private fun LegChip(label: String, pref: RouteType, onClick: () -> Unit) {
    val (icon, color) = rideStyleIcon(pref)
    AssistChip(
        onClick = onClick,
        label = { Text("$label · ${pref.shortLabel}", style = MaterialTheme.typography.labelLarge) },
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        }
    )
}

/** Inline single-choice ride-style selector. Replaces the tap-open-dialog-tap-close flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideStyleSelector(selected: RouteType?, onSelect: (RouteType) -> Unit) {
    val styles = RouteType.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        styles.forEachIndexed { index, style ->
            SegmentedButton(
                selected = style == selected,
                onClick = { onSelect(style) },
                shape = SegmentedButtonDefaults.itemShape(index, styles.size),
                // Suppress the default check icon so four segments fit on narrow phones.
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    activeContentColor = MaterialTheme.colorScheme.primary,
                    activeBorderColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(style.shortLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** Full-width row summarising selected avoidances and opening the multi-select dialog. */
@Composable
fun AvoidanceSummaryRow(selected: Set<Avoidance>, onClick: () -> Unit) {
    val summary = if (selected.isEmpty()) stringResource(R.string.avoid_none)
                  else selected.joinToString(", ") { it.displayName }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.avoid_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreferenceDialog(current: RouteType, onSelect: (RouteType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ride_style)) },
        text = {
            Column {
                RouteType.entries.forEach { pref ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(pref) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            pref == current,
                            { onSelect(pref) },
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                pref.displayName,
                                fontWeight = if (pref == current) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                stringResource(rideStyleDescription(pref)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } }
    )
}

@Composable
private fun AvoidanceDialog(
    selected: Set<Avoidance>,
    onUpdate: (Set<Avoidance>) -> Unit,
    onDismiss: () -> Unit
) {
    var cur by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.avoidances_title)) },
        text = {
            Column {
                Avoidance.entries.forEach { a ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { cur = if (cur.contains(a)) cur - a else cur + a }
                            .defaultMinSize(minHeight = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(a in cur, { cur = if (it) cur + a else cur - a })
                        Text(a.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onUpdate(cur) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = { cur = emptySet() }) { Text(stringResource(R.string.clear)) } }
    )
}
