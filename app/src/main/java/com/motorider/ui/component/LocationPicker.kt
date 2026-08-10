package com.motorider.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.motorider.R
import com.motorider.ui.theme.BrandBlue
import kotlinx.coroutines.delay
import org.osmdroid.util.GeoPoint

/**
 * A place on a route, as the rider chose it.
 *
 * [point] is what actually gets routed. It is filled in the moment a suggestion is
 * picked, so a chosen place never needs geocoding again — see
 * [com.motorider.utils.RouteUtils.LocationSuggestion]. A stop with a [label] but no
 * [point] is free text the rider typed and never confirmed; planning has to resolve
 * it, and is allowed to fail loudly if it cannot.
 */
data class RouteStop(
    val label: String = "",
    val point: GeoPoint? = null,
    /** True when this stop tracks the rider's live position rather than a fixed place. */
    val isCurrentLocation: Boolean = false
) {
    val isSet: Boolean get() = label.isNotBlank()
}

/** What the picker is filling in — drives its title and the icon on the stop row. */
enum class StopKind { START, VIA, DESTINATION }

/**
 * One stop in the planning sheet: a tappable row, not an editable field.
 *
 * The previous design squeezed an `OutlinedTextField` and three 48 dp icon buttons
 * into a single row, leaving the address itself a few characters wide and putting a
 * suggestion list where it shoved the rest of the sheet down the screen every
 * keystroke. Editing now happens in [LocationPickerDialog], which has the whole
 * screen for it; this row's only job is to show what is set and be easy to hit with
 * a gloved thumb.
 */
@Composable
fun StopRow(
    kind: StopKind,
    index: Int,
    stop: RouteStop,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (icon, tint) = stopIcon(kind)
    val label = when (kind) {
        StopKind.START -> stringResource(R.string.stop_start)
        StopKind.DESTINATION -> stringResource(R.string.stop_destination)
        StopKind.VIA -> stringResource(R.string.stop_via_fmt, index + 1)
    }

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A tinted disc rather than a bare glyph: at a glance the rider is
            // matching a colour to the marker on the map, not reading an icon.
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (stop.isSet) stop.label else stringResource(R.string.stop_unset),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (stop.isSet) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onClear != null && stop.isSet) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun stopIcon(kind: StopKind): Pair<ImageVector, Color> = when (kind) {
    StopKind.START -> Icons.Default.MyLocation to BrandBlue
    StopKind.VIA -> Icons.Default.LocationOn to MaterialTheme.colorScheme.secondary
    StopKind.DESTINATION -> Icons.Default.LocationOn to MaterialTheme.colorScheme.error
}

private sealed interface SearchUi {
    data object Idle : SearchUi
    data object Searching : SearchUi
    data class Results(val places: List<com.motorider.utils.RouteUtils.LocationSuggestion>) : SearchUi
    data class Empty(val query: String) : SearchUi
    data object Failed : SearchUi
}

/**
 * Full-screen place search.
 *
 * Full-screen on purpose: the keyboard already takes half a phone, and a dropdown
 * competing with it for the remainder is what made the old inline field feel
 * cramped. Here the field sits under the thumb at the top, results get every
 * remaining pixel, and the three outcomes a search can have — results, nothing
 * found, service unreachable — each get their own honest state instead of all
 * three rendering as a silent empty list.
 */
@Composable
fun LocationPickerDialog(
    kind: StopKind,
    index: Int,
    initialQuery: String,
    currentLocation: GeoPoint?,
    isOffline: Boolean,
    onPick: (label: String, point: GeoPoint?, isCurrentLocation: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }
    var state by remember { mutableStateOf<SearchUi>(SearchUi.Idle) }
    // Bumped to force a re-run of the search effect for the same query, which is
    // what "Retry" has to do after a failure.
    var attempt by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val title = when (kind) {
        StopKind.START -> stringResource(R.string.pick_start)
        StopKind.DESTINATION -> stringResource(R.string.pick_destination)
        StopKind.VIA -> stringResource(R.string.pick_via_fmt, index + 1)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // One effect owns the debounce, the request and the cancellation. The previous
    // version launched into an outer scope and cancelled the job by hand, which left
    // a stale response able to land after the rider had already typed something else.
    LaunchedEffect(query, attempt) {
        val trimmed = query.trim()
        if (trimmed.length < 3) {
            state = SearchUi.Idle
            return@LaunchedEffect
        }
        state = SearchUi.Searching
        // Long enough that typing a town name is one request, not eight — Nominatim
        // asks for at most one a second and will block a client that ignores it.
        delay(450)
        com.motorider.utils.RouteUtils.searchLocations(
            trimmed,
            currentLocation?.latitude,
            currentLocation?.longitude
        ) { result ->
            state = result.fold(
                onSuccess = { places ->
                    if (places.isEmpty()) SearchUi.Empty(trimmed) else SearchUi.Results(places)
                },
                onFailure = { SearchUi.Failed }
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Lets imePadding() below react to the real keyboard height.
            decorFitsSystemWindows = false
        )
    ) {
        // A Compose Dialog window is sized to the content area and dims what is
        // behind it, so an otherwise full-screen sheet came with a grey scrim band
        // across the status bar. Taking the window to MATCH_PARENT and dropping the
        // dim makes this read as a screen, which is what it is — there is nothing
        // behind it worth showing through.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            dialogWindow?.setDimAmount(0f)
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text(stringResource(R.string.location_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.clear)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // Enter re-runs the search rather than doing nothing, which is what
                    // a rider who has just corrected a spelling expects it to do.
                    keyboardActions = KeyboardActions(onSearch = { attempt++ }),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )

                Spacer(Modifier.height(8.dp))

                if (currentLocation != null) {
                    PickerRow(
                        icon = Icons.Default.MyLocation,
                        tint = BrandBlue,
                        primary = stringResource(R.string.use_current_location),
                        secondary = "",
                        onClick = {
                            keyboard?.hide()
                            onPick(
                                /* label = */ "",
                                /* point = */ currentLocation,
                                /* isCurrentLocation = */ true
                            )
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                AnimatedVisibility(state is SearchUi.Searching, enter = fadeIn(), exit = fadeOut()) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = BrandBlue,
                        trackColor = BrandBlue.copy(alpha = 0.12f)
                    )
                }

                when (val s = state) {
                    is SearchUi.Results -> LazyColumn(Modifier.fillMaxSize()) {
                        items(s.places) { place ->
                            PickerRow(
                                icon = Icons.Default.LocationOn,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                primary = place.primaryName,
                                secondary = place.secondaryName,
                                onClick = {
                                    keyboard?.hide()
                                    onPick(place.primaryName, place.point, false)
                                }
                            )
                        }
                    }

                    is SearchUi.Empty -> PickerMessage(
                        icon = Icons.Outlined.SearchOff,
                        message = stringResource(R.string.location_search_empty, s.query)
                    )

                    SearchUi.Failed -> PickerMessage(
                        icon = Icons.Outlined.CloudOff,
                        message = if (isOffline) stringResource(R.string.location_search_offline)
                                  else stringResource(R.string.location_search_error),
                        actionLabel = stringResource(R.string.retry),
                        onAction = { attempt++ }
                    )

                    SearchUi.Idle -> PickerMessage(
                        icon = Icons.Default.Search,
                        message = if (isOffline) stringResource(R.string.location_search_offline)
                                  else stringResource(R.string.location_search_idle)
                    )

                    SearchUi.Searching -> Unit
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    icon: ImageVector,
    tint: Color,
    primary: String,
    secondary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // 64 dp, not the 48 dp minimum: this list is tapped one-handed, often
            // in gloves, and mis-hitting sends the rider to the wrong town.
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                primary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (secondary.isNotBlank()) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PickerMessage(
    icon: ImageVector,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
