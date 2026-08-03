package com.motorider.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motorider.R
import com.motorider.models.DownloadStatus
import com.motorider.models.OfflineRegion
import com.motorider.ui.viewmodel.OfflineMapManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapManagerScreen(
    viewModel: OfflineMapManagerViewModel = viewModel(),
    onNavigateBack: () -> Unit
) {
    val regions by viewModel.regions.collectAsState()
    val storageStats by viewModel.storageStats.collectAsState()
    
    var showDeleteDialog by remember { mutableStateOf<OfflineRegion?>(null) }
    var showStorageWarning by remember { mutableStateOf<OfflineRegion?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.offline_maps)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.menu))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            StorageStatsCard(
                usedMB = storageStats.usedBytes / (1024.0 * 1024.0),
                availableMB = storageStats.availableBytes / (1024.0 * 1024.0)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(regions) { region ->
                    RegionCard(
                        region = region,
                        onDownload = {
                            if (viewModel.hasEnoughStorage(region.id)) {
                                viewModel.downloadRegion(region.id)
                            } else {
                                showStorageWarning = region
                            }
                        },
                        onDelete = { showDeleteDialog = region }
                    )
                }
            }
        }
    }
    
    showDeleteDialog?.let { region ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.confirm_delete_message, region.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRegion(region.id)
                        showDeleteDialog = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    showStorageWarning?.let { region ->
        AlertDialog(
            onDismissRequest = { showStorageWarning = null },
            title = { Text(stringResource(R.string.insufficient_storage)) },
            text = {
                Text(
                    stringResource(
                        R.string.storage_required,
                        viewModel.getStorageRequiredMB(region.id),
                        viewModel.getAvailableStorageMB()
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showStorageWarning = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun StorageStatsCard(
    usedMB: Double,
    availableMB: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Storage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.storage_used, usedMB),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.available_space, availableMB),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun RegionCard(
    region: OfflineRegion,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = region.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = region.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                when (region.downloadStatus) {
                    DownloadStatus.DOWNLOADED -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.downloaded),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    DownloadStatus.DOWNLOADING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    }
                    else -> {
                        IconButton(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(R.string.download),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.tile_count, region.tileCount),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(R.string.estimated_size, region.estimatedSizeMB),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            if (region.downloadStatus == DownloadStatus.DOWNLOADED) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}
