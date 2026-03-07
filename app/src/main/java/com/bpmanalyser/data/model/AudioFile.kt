package com.bpmanalyzer.data.model

import android.net.Uri

data class AudioFile(
    val id: String,
    val name: String,
    val originalName: String,
    val uri: Uri,
    val path: String,
    val format: String,
    val duration: Long, // ms
    val size: Long, // bytes
    var bpm: Double = 0.0,
    var bpmConfidence: Float = 0f,
    var analysisState: AnalysisState = AnalysisState.PENDING
)

enum class AnalysisState {
    PENDING, ANALYZING, DONE, ERROR
}

enum class SortOption {
    BPM_ASC, BPM_DESC, NAME_ASC, NAME_DESC, DURATION_ASC, DURATION_DESC
}

enum class BpmRange(val label: String, val min: Int, val max: Int) {
    SLOW("< 90 BPM", 0, 89),
    MEDIUM_SLOW("90–110 BPM", 90, 110),
    MEDIUM("110–125 BPM", 111, 125),
    MEDIUM_FAST("125–140 BPM", 126, 140),
    FAST("> 140 BPM", 141, Int.MAX_VALUE);

    companion object {
        fun forBpm(bpm: Double): BpmRange {
            val b = bpm.toInt()
            return values().first { b >= it.min && b <= it.max }
        }
    }
}

enum class RenameFormat(val label: String, val description: String) {
    NUMERIC_ONLY("Numérique simple", "001_nom_original.mp3"),
    NUMERIC_BPM("Numérique + BPM", "001_124BPM_nom_original.mp3"),
    BPM_ONLY("BPM seul", "124BPM_nom_original.mp3"),
    CUSTOM("Format custom", "{index}_{bpm}_{nom}")
}

data class RenamePreview(
    val audioFile: AudioFile,
    val newName: String,
    val index: Int
)

data class RenameHistory(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val folderPath: String,
    val renames: List<RenameEntry>
)

data class RenameEntry(
    val originalName: String,
    val newName: String,
    val filePath: String
)
