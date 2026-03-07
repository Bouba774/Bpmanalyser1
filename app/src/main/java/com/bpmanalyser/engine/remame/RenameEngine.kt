package com.bpmanalyzer.engine.rename

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.bpmanalyzer.data.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RenameEngine {

    private val gson = Gson()

    // ─── Preview ──────────────────────────────────────────────────────────────

    fun generatePreview(
        files: List<AudioFile>,
        format: RenameFormat,
        sortAscending: Boolean,
        customTemplate: String = "{index}_{bpm}_{nom}"
    ): List<RenamePreview> {
        val sorted = if (sortAscending) {
            files.filter { it.bpm > 0 }.sortedBy { it.bpm }
        } else {
            files.filter { it.bpm > 0 }.sortedByDescending { it.bpm }
        }

        return sorted.mapIndexed { index, file ->
            val newName = buildNewName(
                originalName = file.originalName,
                bpm = file.bpm,
                index = index + 1,
                total = sorted.size,
                format = format,
                customTemplate = customTemplate
            )
            RenamePreview(
                audioFile = file,
                newName = newName,
                index = index + 1
            )
        }
    }

    private fun buildNewName(
        originalName: String,
        bpm: Double,
        index: Int,
        total: Int,
        format: RenameFormat,
        customTemplate: String
    ): String {
        val ext = originalName.substringAfterLast('.', "")
        val baseName = if (ext.isNotEmpty()) originalName.dropLast(ext.length + 1) else originalName
        val paddedIndex = index.toString().padStart(total.toString().length, '0')
        val bpmInt = bpm.toInt()

        return when (format) {
            RenameFormat.NUMERIC_ONLY ->
                "${paddedIndex}_${baseName}.${ext}"

            RenameFormat.NUMERIC_BPM ->
                "${paddedIndex}_${bpmInt}BPM_${baseName}.${ext}"

            RenameFormat.BPM_ONLY ->
                "${bpmInt}BPM_${baseName}.${ext}"

            RenameFormat.CUSTOM -> {
                customTemplate
                    .replace("{index}", paddedIndex)
                    .replace("{bpm}", bpmInt.toString())
                    .replace("{nom}", baseName)
                    .let { "$it.$ext" }
            }
        }
    }

    // ─── Execute Rename ───────────────────────────────────────────────────────

    suspend fun executeRename(
        context: Context,
        folderUri: Uri,
        previews: List<RenamePreview>
    ): RenameResult = withContext(Dispatchers.IO) {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: return@withContext RenameResult.Error("Impossible d'accéder au dossier")

        val historyEntries = mutableListOf<RenameEntry>()
        val errors = mutableListOf<String>()
        var successCount = 0

        for (preview in previews) {
            try {
                val file = DocumentFile.fromSingleUri(context, preview.audioFile.uri)
                    ?: continue

                // Safety checks
                if (!file.exists()) {
                    errors.add("Fichier introuvable : ${preview.audioFile.originalName}")
                    continue
                }
                if (preview.newName == preview.audioFile.originalName) {
                    successCount++
                    continue
                }

                // Check for name conflict
                val existing = folder.findFile(preview.newName)
                if (existing != null && existing.uri != file.uri) {
                    errors.add("Conflit de nom : ${preview.newName}")
                    continue
                }

                // Rename
                val renamed = file.renameTo(preview.newName)
                if (renamed) {
                    historyEntries.add(
                        RenameEntry(
                            originalName = preview.audioFile.originalName,
                            newName = preview.newName,
                            filePath = preview.audioFile.path
                        )
                    )
                    successCount++
                } else {
                    errors.add("Échec du renommage : ${preview.audioFile.originalName}")
                }
            } catch (e: Exception) {
                errors.add("Erreur : ${preview.audioFile.originalName} — ${e.message}")
            }
        }

        val history = RenameHistory(
            folderPath = folderUri.toString(),
            renames = historyEntries
        )

        RenameResult.Success(
            successCount = successCount,
            errorCount = errors.size,
            errors = errors,
            history = history
        )
    }

    // ─── Rollback ─────────────────────────────────────────────────────────────

    suspend fun rollback(
        context: Context,
        folderUri: Uri,
        history: RenameHistory
    ): RollbackResult = withContext(Dispatchers.IO) {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: return@withContext RollbackResult(0, listOf("Impossible d'accéder au dossier"))

        var successCount = 0
        val errors = mutableListOf<String>()

        // Rollback in reverse order to handle dependencies
        for (entry in history.renames.reversed()) {
            try {
                val file = folder.findFile(entry.newName)
                if (file == null) {
                    errors.add("Fichier non trouvé pour rollback : ${entry.newName}")
                    continue
                }
                val restored = file.renameTo(entry.originalName)
                if (restored) successCount++ else errors.add("Échec rollback : ${entry.newName}")
            } catch (e: Exception) {
                errors.add("Erreur rollback : ${entry.newName}")
            }
        }

        RollbackResult(successCount, errors)
    }
}

sealed class RenameResult {
    data class Success(
        val successCount: Int,
        val errorCount: Int,
        val errors: List<String>,
        val history: RenameHistory
    ) : RenameResult()

    data class Error(val message: String) : RenameResult()
}

data class RollbackResult(
    val successCount: Int,
    val errors: List<String>
)
