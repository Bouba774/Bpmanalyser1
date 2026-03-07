package com.bpmanalyzer.engine.export

import android.content.Context
import android.os.Environment
import com.bpmanalyzer.data.model.AudioFile
import com.bpmanalyzer.data.model.AnalysisState
import com.bpmanalyzer.engine.audio.AudioScanner
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfExporter {

    private val scanner = AudioScanner()

    // Colors
    private val colorPrimary = DeviceRgb(99, 102, 241)   // Indigo
    private val colorDark = DeviceRgb(15, 23, 42)
    private val colorHeaderBg = DeviceRgb(30, 41, 59)
    private val colorRowAlt = DeviceRgb(248, 250, 252)
    private val colorText = DeviceRgb(30, 41, 59)
    private val colorGray = DeviceRgb(100, 116, 139)

    suspend fun exportToPdf(
        context: Context,
        files: List<AudioFile>,
        folderName: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val fileName = "bpm_export_$dateStr.pdf"

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val outputFile = File(downloadsDir, fileName)

            PdfWriter(outputFile).use { writer ->
                val pdfDoc = PdfDocument(writer)
                Document(pdfDoc).use { doc ->
                    doc.setMargins(40f, 30f, 40f, 30f)

                    val boldFont = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD)
                    val regularFont = PdfFontFactory.createFont(StandardFonts.HELVETICA)
                    val monoFont = PdfFontFactory.createFont(StandardFonts.COURIER)

                    // ── Header ───────────────────────────────────────────────
                    val titlePara = Paragraph("🎵 BPM Analyzer — Export")
                        .setFont(boldFont)
                        .setFontSize(20f)
                        .setFontColor(colorPrimary)
                        .setMarginBottom(4f)
                    doc.add(titlePara)

                    val subPara = Paragraph("Dossier : $folderName  •  ${files.size} fichiers  •  $dateStr")
                        .setFont(regularFont)
                        .setFontSize(9f)
                        .setFontColor(colorGray)
                        .setMarginBottom(20f)
                    doc.add(subPara)

                    // ── Stats bar ────────────────────────────────────────────
                    val done = files.filter { it.analysisState == AnalysisState.DONE }
                    if (done.isNotEmpty()) {
                        val avgBpm = done.map { it.bpm }.average()
                        val minBpm = done.minOf { it.bpm }
                        val maxBpm = done.maxOf { it.bpm }
                        val statsTable = Table(UnitValue.createPercentArray(floatArrayOf(1f, 1f, 1f, 1f)))
                            .setWidth(UnitValue.createPercentValue(100f))
                            .setMarginBottom(20f)

                        addStatCell(statsTable, "Fichiers analysés", "${done.size}", boldFont, regularFont)
                        addStatCell(statsTable, "BPM moyen", "%.1f".format(avgBpm), boldFont, regularFont)
                        addStatCell(statsTable, "BPM min", "%.1f".format(minBpm), boldFont, regularFont)
                        addStatCell(statsTable, "BPM max", "%.1f".format(maxBpm), boldFont, regularFont)
                        doc.add(statsTable)
                    }

                    // ── Main table ───────────────────────────────────────────
                    val colWidths = floatArrayOf(3f, 1.2f, 1.2f, 1f, 1f)
                    val table = Table(UnitValue.createPercentArray(colWidths))
                        .setWidth(UnitValue.createPercentValue(100f))

                    // Headers
                    val headers = listOf("Fichier", "BPM", "Durée", "Format", "Confiance")
                    for (header in headers) {
                        table.addHeaderCell(
                            Cell().add(
                                Paragraph(header)
                                    .setFont(boldFont)
                                    .setFontSize(9f)
                                    .setFontColor(ColorConstants.WHITE)
                            ).setBackgroundColor(colorHeaderBg)
                                .setPadding(8f)
                        )
                    }

                    // Rows
                    files.forEachIndexed { i, file ->
                        val bg = if (i % 2 == 0) ColorConstants.WHITE else colorRowAlt
                        val bpmStr = if (file.bpm > 0) "%.1f".format(file.bpm) else "—"
                        val durationStr = scanner.formatDuration(file.duration)
                        val confStr = if (file.bpmConfidence > 0)
                            "${(file.bpmConfidence * 100).toInt()}%" else "—"

                        addCell(table, file.name, regularFont, 8f, bg, colorText)
                        addCell(table, bpmStr, boldFont, 9f, bg,
                            if (file.bpm > 0) colorPrimary else colorGray)
                        addCell(table, durationStr, regularFont, 8f, bg, colorText)
                        addCell(table, file.format, monoFont, 8f, bg, colorGray)
                        addCell(table, confStr, regularFont, 8f, bg, colorGray)
                    }

                    doc.add(table)

                    // Footer
                    doc.add(
                        Paragraph("Généré par BPM Analyzer • $dateStr")
                            .setFont(regularFont)
                            .setFontSize(7f)
                            .setFontColor(colorGray)
                            .setTextAlignment(TextAlignment.CENTER)
                            .setMarginTop(20f)
                    )
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun addStatCell(
        table: Table,
        label: String,
        value: String,
        boldFont: com.itextpdf.kernel.font.PdfFont,
        regularFont: com.itextpdf.kernel.font.PdfFont
    ) {
        val cell = Cell()
            .add(Paragraph(value).setFont(boldFont).setFontSize(14f).setFontColor(colorPrimary))
            .add(Paragraph(label).setFont(regularFont).setFontSize(8f).setFontColor(colorGray))
            .setBackgroundColor(colorRowAlt)
            .setPadding(10f)
            .setBorderRadius(com.itextpdf.layout.properties.BorderRadius(4f))
        table.addCell(cell)
    }

    private fun addCell(
        table: Table,
        text: String,
        font: com.itextpdf.kernel.font.PdfFont,
        fontSize: Float,
        bgColor: com.itextpdf.kernel.colors.Color,
        textColor: com.itextpdf.kernel.colors.Color
    ) {
        table.addCell(
            Cell().add(
                Paragraph(text)
                    .setFont(font)
                    .setFontSize(fontSize)
                    .setFontColor(textColor)
            ).setBackgroundColor(bgColor)
                .setPadding(6f)
        )
    }
}
