package com.bpmanalyzer.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bpmanalyzer.data.model.*
import com.bpmanalyzer.engine.SortEngine
import com.bpmanalyzer.engine.audio.AudioScanner
import com.bpmanalyzer.engine.bpm.BpmDetector
import com.bpmanalyzer.engine.export.PdfExporter
import com.bpmanalyzer.engine.rename.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val scanner = AudioScanner()
    private val bpmDetector = BpmDetector()
    private val pdfExporter = PdfExporter()
    private val renameEngine = RenameEngine()

    // ─── State ────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var analysisJob: Job? = null
    private val allFiles = MutableStateFlow<List<AudioFile>>(emptyList())

    init {
        // React to filter/sort changes and rebuild displayed list
        viewModelScope.launch {
            combine(
                allFiles,
                _uiState.map { it.sortOption }.distinctUntilChanged(),
                _uiState.map { it.searchQuery }.distinctUntilChanged(),
                _uiState.map { it.filterMinBpm }.distinctUntilChanged(),
                _uiState.map { it.filterMaxBpm }.distinctUntilChanged()
            ) { files, sort, query, minBpm, maxBpm ->
                val filtered = SortEngine.filter(files, query, minBpm, maxBpm)
                SortEngine.sort(filtered, sort)
            }.collect { sorted ->
                _uiState.update { it.copy(displayedFiles = sorted) }
            }
        }
    }

    // ─── Folder Selection ─────────────────────────────────────────────────────

    fun onFolderSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, folderUri = uri) }
            val files = scanner.scanFolder(context, uri)
            allFiles.value = files
            val folderName = uri.lastPathSegment?.substringAfterLast(':') ?: "Dossier"
            _uiState.update { it.copy(
                isScanning = false,
                folderName = folderName,
                totalFiles = files.size
            )}
        }
    }

    // ─── Analysis ─────────────────────────────────────────────────────────────

    fun startAnalysis() {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val files = allFiles.value.toMutableList()
            _uiState.update { it.copy(
                isAnalyzing = true,
                analyzedCount = 0,
                analysisError = null
            )}

            files.forEachIndexed { i, file ->
                val updated = file.copy(analysisState = AnalysisState.ANALYZING)
                updateFile(updated)

                val result = try {
                    bpmDetector.detectBpm(context, file.uri)
                } catch (e: Exception) {
                    null
                }

                val finalFile = if (result != null && result.bpm > 0) {
                    file.copy(
                        bpm = result.bpm,
                        bpmConfidence = result.confidence,
                        analysisState = AnalysisState.DONE
                    )
                } else {
                    file.copy(analysisState = AnalysisState.ERROR)
                }

                updateFile(finalFile)
                _uiState.update { it.copy(analyzedCount = i + 1) }
            }

            _uiState.update { it.copy(isAnalyzing = false) }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        _uiState.update { it.copy(isAnalyzing = false) }
    }

    private fun updateFile(file: AudioFile) {
        val current = allFiles.value.toMutableList()
        val idx = current.indexOfFirst { it.id == file.id }
        if (idx >= 0) {
            current[idx] = file
            allFiles.value = current
        }
    }

    // ─── Sorting & Filtering ──────────────────────────────────────────────────

    fun setSortOption(option: SortOption) {
        _uiState.update { it.copy(sortOption = option) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilterBpm(min: Double, max: Double) {
        _uiState.update { it.copy(filterMinBpm = min, filterMaxBpm = max) }
    }

    fun clearFilters() {
        _uiState.update { it.copy(searchQuery = "", filterMinBpm = 0.0, filterMaxBpm = 999.0) }
    }

    fun toggleGroupByBpm() {
        _uiState.update { it.copy(groupByBpm = !it.groupByBpm) }
    }

    fun getBpmGroups(): Map<BpmRange, List<AudioFile>> {
        return SortEngine.groupByBpmRange(_uiState.value.displayedFiles)
    }

    // ─── Export ───────────────────────────────────────────────────────────────

    fun exportPdf() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportError = null) }
            val result = pdfExporter.exportToPdf(
                context,
                _uiState.value.displayedFiles,
                _uiState.value.folderName
            )
            result.fold(
                onSuccess = { file ->
                    _uiState.update { it.copy(
                        isExporting = false,
                        exportedFile = file,
                        showExportSuccess = true
                    )}
                },
                onFailure = { e ->
                    _uiState.update { it.copy(
                        isExporting = false,
                        exportError = e.message ?: "Erreur d'export"
                    )}
                }
            )
        }
    }

    fun dismissExportSuccess() {
        _uiState.update { it.copy(showExportSuccess = false, exportedFile = null) }
    }

    // ─── Rename ───────────────────────────────────────────────────────────────

    fun generateRenamePreview(
        format: RenameFormat,
        sortAscending: Boolean,
        customTemplate: String = "{index}_{bpm}_{nom}"
    ) {
        val previews = renameEngine.generatePreview(
            allFiles.value.filter { it.analysisState == AnalysisState.DONE },
            format,
            sortAscending,
            customTemplate
        )
        _uiState.update { it.copy(renamePreviews = previews) }
    }

    fun executeRename() {
        val folderUri = _uiState.value.folderUri ?: return
        val previews = _uiState.value.renamePreviews

        viewModelScope.launch {
            _uiState.update { it.copy(isRenaming = true, renameError = null) }

            val result = renameEngine.executeRename(context, folderUri, previews)

            when (result) {
                is RenameResult.Success -> {
                    // Save history for potential rollback
                    val histories = _uiState.value.renameHistories.toMutableList()
                    histories.add(0, result.history)

                    _uiState.update { it.copy(
                        isRenaming = false,
                        renameHistories = histories,
                        showRenameSuccess = true,
                        renameSuccessCount = result.successCount,
                        renameErrors = result.errors
                    )}

                    // Refresh file list
                    onFolderSelected(folderUri)
                }
                is RenameResult.Error -> {
                    _uiState.update { it.copy(
                        isRenaming = false,
                        renameError = result.message
                    )}
                }
            }
        }
    }

    fun rollbackRename(history: RenameHistory) {
        val folderUri = _uiState.value.folderUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isRenaming = true) }
            val result = renameEngine.rollback(context, folderUri, history)
            _uiState.update { it.copy(
                isRenaming = false,
                showRollbackSuccess = result.successCount > 0
            )}
            onFolderSelected(folderUri)
        }
    }

    fun dismissRenameDialogs() {
        _uiState.update { it.copy(
            showRenameSuccess = false,
            showRollbackSuccess = false,
            renamePreviews = emptyList()
        )}
    }
}

data class MainUiState(
    // Folder
    val folderUri: Uri? = null,
    val folderName: String = "",

    // Files
    val displayedFiles: List<AudioFile> = emptyList(),
    val totalFiles: Int = 0,

    // Scanning
    val isScanning: Boolean = false,

    // Analysis
    val isAnalyzing: Boolean = false,
    val analyzedCount: Int = 0,
    val analysisError: String? = null,

    // Sort/filter
    val sortOption: SortOption = SortOption.BPM_ASC,
    val searchQuery: String = "",
    val filterMinBpm: Double = 0.0,
    val filterMaxBpm: Double = 999.0,
    val groupByBpm: Boolean = false,

    // Export
    val isExporting: Boolean = false,
    val exportedFile: File? = null,
    val showExportSuccess: Boolean = false,
    val exportError: String? = null,

    // Rename
    val renamePreviews: List<RenamePreview> = emptyList(),
    val isRenaming: Boolean = false,
    val showRenameSuccess: Boolean = false,
    val renameSuccessCount: Int = 0,
    val renameErrors: List<String> = emptyList(),
    val renameError: String? = null,
    val renameHistories: List<RenameHistory> = emptyList(),
    val showRollbackSuccess: Boolean = false
) {
    val analysisProgress: Float
        get() = if (totalFiles > 0) analyzedCount.toFloat() / totalFiles else 0f

    val canAnalyze: Boolean
        get() = folderUri != null && !isAnalyzing && totalFiles > 0

    val canExport: Boolean
        get() = displayedFiles.any { it.analysisState == AnalysisState.DONE }

    val canRename: Boolean
        get() = folderUri != null && displayedFiles.any { it.analysisState == AnalysisState.DONE }
}
