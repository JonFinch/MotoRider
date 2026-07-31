package com.motorider.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Motorcycle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorider.models.Avoidance
import com.motorider.models.RouteType
import com.motorider.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlanningSheet(
    onPlanRoute: (start: String, end: String, intermediates: List<String>, vehicleType: RouteType, preference: RouteType, avoidances: Set<Avoidance>) -> Unit,
    onDismiss: () -> Unit
) {
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var selectedVehicle by remember { mutableStateOf(RouteType.MOTORCYCLE) }
    var selectedPreference by remember { mutableStateOf(RouteType.DIRECT) }
    var selectedAvoidances by remember { mutableStateOf<Set<Avoidance>>(emptySet()) }
    var intermediateWaypoints by remember { mutableStateOf<List<String>>(emptyList()) }

    var showPreferenceDialog by remember { mutableStateOf(false) }
    var showAvoidancesDialog by remember { mutableStateOf(false) }
    var showMoreOptions by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showPreferenceDialog = true },
                    modifier = Modifier.padding(end = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        Icons.Outlined.Timeline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(selectedPreference.displayName, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = { showAvoidancesDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (selectedAvoidances.isEmpty()) "Avoidances"
                        else "Avoidances (${selectedAvoidances.size})",
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = { showMoreOptions = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = Gray200
            )

            WaypointInputCard(
                icon = Icons.Default.Home,
                iconTint = Primary,
                hint = "Start location",
                value = startText,
                onValueChange = { startText = it },
                backgroundColor = StartLocationBg
            )

            DottedConnector()

            intermediateWaypoints.forEachIndexed { index, wp ->
                WaypointInputCard(
                    icon = Icons.Default.MyLocation,
                    iconTint = Gray600,
                    hint = "Waypoint ${index + 1}",
                    value = wp,
                    onValueChange = { updated ->
                        intermediateWaypoints = intermediateWaypoints.toMutableList().also { it[index] = updated }
                    },
                    onRemove = {
                        intermediateWaypoints = intermediateWaypoints.toMutableList().also { it.removeAt(index) }
                    },
                    backgroundColor = SurfaceVariant
                )
                DottedConnector()
            }

            WaypointInputCard(
                icon = Icons.Default.Flag,
                iconTint = Error,
                hint = "End location",
                value = endText,
                onValueChange = { endText = it },
                backgroundColor = EndLocationBg
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Vehicle",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )

                VehicleTypeButton(
                    icon = Icons.Default.Motorcycle,
                    selected = selectedVehicle == RouteType.MOTORCYCLE,
                    onClick = { selectedVehicle = RouteType.MOTORCYCLE }
                )
                Spacer(Modifier.width(4.dp))
                VehicleTypeButton(
                    icon = Icons.Default.TwoWheeler,
                    selected = selectedVehicle == RouteType.TRUCK,
                    onClick = { selectedVehicle = RouteType.TRUCK }
                )
                Spacer(Modifier.width(4.dp))
                VehicleTypeButton(
                    icon = Icons.Default.DirectionsCar,
                    selected = selectedVehicle == RouteType.CAR,
                    onClick = { selectedVehicle = RouteType.CAR }
                )
                Spacer(Modifier.width(4.dp))
                VehicleTypeButton(
                    icon = Icons.Default.DirectionsBike,
                    selected = selectedVehicle == RouteType.BIKE,
                    onClick = { selectedVehicle = RouteType.BIKE }
                )

                Spacer(Modifier.weight(1f))

                IconButton(onClick = {
                    intermediateWaypoints = intermediateWaypoints + ""
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add waypoint", tint = Primary)
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = Gray200
            )

            Button(
                onClick = {
                    val intermediates = intermediateWaypoints.filter { it.isNotBlank() }
                    onPlanRoute(startText, endText, intermediates, selectedVehicle, selectedPreference, selectedAvoidances)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Plan Route", fontSize = 16.sp)
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showPreferenceDialog) {
        AlertDialog(
            onDismissRequest = { showPreferenceDialog = false },
            title = { Text("Route Preference") },
            text = {
                Column {
                    val preferences = listOf(RouteType.DIRECT, RouteType.FAST, RouteType.CURVY, RouteType.EXTRA_CURVY)
                    preferences.forEach { pref ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedPreference = pref
                                    showPreferenceDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedPreference == pref,
                                onClick = {
                                    selectedPreference = pref
                                    showPreferenceDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(pref.displayName)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showAvoidancesDialog) {
        AlertDialog(
            onDismissRequest = { showAvoidancesDialog = false },
            title = { Text("Avoidances") },
            text = {
                Column {
                    Avoidance.entries.forEach { avoidance ->
                        var checked = selectedAvoidances.contains(avoidance)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    checked = !checked
                                    if (checked) selectedAvoidances += avoidance
                                    else selectedAvoidances -= avoidance
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) selectedAvoidances += avoidance
                                    else selectedAvoidances -= avoidance
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(avoidance.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAvoidancesDialog = false }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedAvoidances = emptySet()
                    showAvoidancesDialog = false
                }) { Text("Clear All") }
            }
        )
    }

    if (showMoreOptions) {
        AlertDialog(
            onDismissRequest = { showMoreOptions = false },
            title = { Text("More Options") },
            text = { Text("Additional options coming soon.") },
            confirmButton = { TextButton(onClick = { showMoreOptions = false }) { Text("OK") } }
        )
    }
}

@Composable
private fun WaypointInputCard(
    icon: ImageVector,
    iconTint: Color,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    backgroundColor: Color,
    onRemove: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(hint, fontSize = 14.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MyLocation, contentDescription = "Set location", tint = iconTint, modifier = Modifier.size(18.dp))
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Gray600, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun DottedConnector() {
    Box(
        modifier = Modifier
            .padding(start = 28.dp)
            .height(20.dp)
            .width(2.dp)
            .background(Gray400)
    )
}

@Composable
private fun VehicleTypeButton(
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .then(
                if (selected) Modifier
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.15f))
                else Modifier
            )
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Primary else Gray600,
            modifier = Modifier.size(24.dp)
        )
    }
}
