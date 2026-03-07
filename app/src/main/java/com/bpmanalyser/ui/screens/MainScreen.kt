package com.bpmanalyzer.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bpmanalyzer.data.model.*
import com.bpmanalyzer.engine.audio.AudioScanner
import com.bpmanalyzer.ui.MainViewModel
import com.bpmanalyzer.ui.components.*
import com.bpmanalyzer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scanner = remember { AudioScanner() }

    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showRenameSheet by remember { mutableStateOf(false) }
    var showRenameHistory by remember { mutableStateOf(false) }

    // Folder picker
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.onFolderSelected(it) }
    }

    // Permission launcher
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permLauncher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        } else {
            permLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Text("BPM Analyzer", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (state.canRename) {
                        IconButton(onClick = { showRenameSheet = true }) {
                            Icon(Icons.Outlined.DriveFileRenameOutline, "Renommer")
                        }
                    }
                    if (state.canExport) {
                        IconButton(onClick = { viewModel.exportPdf() }) {
                            if (state.isExporting)
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else
                                Icon(Icons.Outlined.PictureAsPdf, "Export PDF")
                        }
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        Badge(containerColor = if (state.filterMinBpm > 0 || state.filterMaxBpm < 999) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface) {
                            Icon(Icons.Outlined.FilterList, "Filtres")
                        }
                    }
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Outlined.Sort, "Trier")
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        SortOption.values().forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt.label) },
                                onClick = { viewModel.setSortOption(opt); showSortMenu = false },
                                leadingIcon = {
                                    if (state.sortOption == opt)
                                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.isAnalyzing) {
                    FloatingActionButton(
                        onClick = { viewModel.cancelAnalysis() },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ) {
                        Icon(Icons.Filled.Stop, "Arrêter")
                    }
                } else if (state.canAnalyze) {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.startAnalysis() },
                        icon = { Icon(Icons.Filled.PlayArrow, null) },
                        text = { Text("Analyser les BPM") },
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                }
                FloatingActionButton(
                    onClick = { folderLauncher.launch(null) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Filled.FolderOpen, "Choisir dossier")
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            if (state.totalFiles > 0) {
                SearchBar(
                    query = state.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Analysis progress
            AnimatedVisibility(state.isAnalyzing || state.isScanning) {
                AnalysisProgressBar(
                    progress = state.analysisProgress,
                    current = state.analyzedCount,
                    total = state.totalFiles,
                    isScanning = state.isScanning,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Stats header
            AnimatedVisibility(state.displayedFiles.any { it.bpm > 0 }) {
                StatsHeader(
                    files = state.displayedFiles,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            when {
                state.folderUri == null -> EmptyState(onPickFolder = { folderLauncher.launch(null) })
                state.isScanning -> ScanningState()
                state.displayedFiles.isEmpty() && state.totalFiles == 0 -> EmptyFolderState()
                else -> {
                    if (state.groupByBpm) {
                        GroupedFileList(
                            groups = viewModel.getBpmGroups(),
                            scanner = scanner
                        )
                    } else {
                        FileList(
                            files = state.displayedFiles,
                            scanner = scanner,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    // Bottom sheet: Filters
    if (showFilterSheet) {
        FilterBottomSheet(
            currentMin = state.filterMinBpm,
            currentMax = state.filterMaxBpm,
            onApply = { min, max ->
                viewModel.setFilterBpm(min, max)
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false },
            onClear = {
                viewModel.clearFilters()
                showFilterSheet = false
            }
        )
    }

    // Bottom sheet: Rename
    if (showRenameSheet) {
        RenameBottomSheet(
            previews = state.renamePreviews,
            isRenaming = state.isRenaming,
            onGeneratePreview = { format, ascending, template ->
                viewModel.generateRenamePreview(format, ascending, template)
            },
            onExecute = { viewModel.executeRename() },
            onDismiss = { showRenameSheet = false; viewModel.dismissRenameDialogs() },
            onShowHistory = { showRenameHistory = true }
        )
    }

    // Rename history
    if (showRenameHistory) {
        RenameHistorySheet(
            histories = state.renameHistories,
            onRollback = { history ->
                viewModel.rollbackRename(history)
                showRenameHistory = false
            },
            onDismiss = { showRenameHistory = false }
        )
    }

    // Export success snackbar
    if (state.showExportSuccess) {
        LaunchedEffect(Unit) {
            viewModel.dismissExportSuccess()
        }
        // Shown via SnackbarHost in real app — simplified here
    }
}

private val SortOption.label: String get() = when (this) {
    SortOption.BPM_ASC -> "BPM croissant"
    SortOption.BPM_DESC -> "BPM décroissant"
    SortOption.NAME_ASC -> "Nom A→Z"
    SortOption.NAME_DESC -> "Nom Z→A"
    SortOption.DURATION_ASC -> "Durée courte"
    SortOption.DURATION_DESC -> "Durée longue"
}

// ─── Sub-Composables ──────────────────────────────────────────────────────────

@Composable
private fun EmptyState(onPickFolder: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Filled.GraphicEq, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("BPM Analyzer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Sélectionne un dossier audio", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onPickFolder, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choisir un dossier")
            }
        }
    }
}

@Composable
private fun ScanningState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("Scan du dossier en cours…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyFolderState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.MusicOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Aucun fichier audio trouvé", style = MaterialTheme.typography.bodyLarge)
            Text("Formats supportés : MP3, WAV, FLAC, AAC, M4A", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun FileList(
    files: List<AudioFile>,
    scanner: AudioScanner,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(files, key = { it.id }) { file ->
            AudioFileCard(file = file, scanner = scanner)
        }
    }
}

@Composable
fun GroupedFileList(
    groups: Map<BpmRange, List<AudioFile>>,
    scanner: AudioScanner
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { (range, files) ->
            item(key = range.name) {
                BpmRangeHeader(range = range, count = files.size)
            }
            items(files, key = { it.id }) { file ->
                AudioFileCard(file = file, scanner = scanner)
            }
        }
    }
}

@Composable
private fun BpmRangeHeader(range: BpmRange, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(bpmRangeColor(range.min.toDouble().coerceAtLeast(1.0)))
        )
        Text(range.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text("$count piste${if (count > 1) "s" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f))
    }
}
