package com.bpmanalyzer.engine

import com.bpmanalyzer.data.model.AudioFile
import com.bpmanalyzer.data.model.BpmRange
import com.bpmanalyzer.data.model.SortOption

object SortEngine {

    fun sort(files: List<AudioFile>, option: SortOption): List<AudioFile> {
        return when (option) {
            SortOption.BPM_ASC -> files.sortedBy { it.bpm }
            SortOption.BPM_DESC -> files.sortedByDescending { it.bpm }
            SortOption.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
            SortOption.DURATION_ASC -> files.sortedBy { it.duration }
            SortOption.DURATION_DESC -> files.sortedByDescending { it.duration }
        }
    }

    fun filter(
        files: List<AudioFile>,
        query: String = "",
        minBpm: Double = 0.0,
        maxBpm: Double = 999.0
    ): List<AudioFile> {
        return files.filter { file ->
            val matchesQuery = query.isEmpty() ||
                file.name.contains(query, ignoreCase = true)
            val matchesBpm = file.bpm == 0.0 ||
                (file.bpm >= minBpm && file.bpm <= maxBpm)
            matchesQuery && matchesBpm
        }
    }

    fun groupByBpmRange(files: List<AudioFile>): Map<BpmRange, List<AudioFile>> {
        val doneFiles = files.filter {
            it.bpm > 0 &&
            it.analysisState == com.bpmanalyzer.data.model.AnalysisState.DONE
        }
        return BpmRange.values().associateWith { range ->
            doneFiles.filter { it.bpm.toInt() in range.min..range.max }
        }.filter { it.value.isNotEmpty() }
    }
}
