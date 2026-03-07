package com.bpmanalyzer.engine.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.bpmanalyzer.data.model.AnalysisState
import com.bpmanalyzer.data.model.AudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class AudioScanner {

    companion object {
        val SUPPORTED_FORMATS = setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus")
    }

    suspend fun scanFolder(context: Context, folderUri: Uri): List<AudioFile> =
        withContext(Dispatchers.IO) {
            val folder = DocumentFile.fromTreeUri(context, folderUri)
                ?: return@withContext emptyList()
            scanDocumentFile(context, folder)
        }

    private suspend fun scanDocumentFile(
        context: Context,
        folder: DocumentFile
    ): List<AudioFile> {
        val results = mutableListOf<AudioFile>()
        val files = folder.listFiles()

        for (file in files) {
            if (!file.isFile) continue
            val name = file.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED_FORMATS) continue

            val audioFile = buildAudioFile(context, file, name, ext)
            if (audioFile != null) results.add(audioFile)
        }

        return results.sortedBy { it.name.lowercase() }
    }

    private fun buildAudioFile(
        context: Context,
        file: DocumentFile,
        name: String,
        ext: String
    ): AudioFile? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, file.uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L
            retriever.release()

            AudioFile(
                id = UUID.randomUUID().toString(),
                name = name,
                originalName = name,
                uri = file.uri,
                path = file.uri.toString(),
                format = ext.uppercase(),
                duration = duration,
                size = file.length(),
                bpm = 0.0,
                bpmConfidence = 0f,
                analysisState = AnalysisState.PENDING
            )
        } catch (e: Exception) {
            null
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%d:%02d".format(minutes, seconds)
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
