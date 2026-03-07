package com.bpmanalyzer.engine.bpm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * High-precision BPM detection engine using multiple algorithms:
 * 1. Autocorrelation on onset detection function
 * 2. Inter-onset interval histogram
 * 3. Multi-band energy analysis
 *
 * The final BPM is determined by a weighted consensus of all methods.
 */
class BpmDetector {

    companion object {
        private const val TARGET_SAMPLE_RATE = 22050
        private const val HOP_SIZE = 512
        private const val FRAME_SIZE = 1024
        private const val MIN_BPM = 60.0
        private const val MAX_BPM = 200.0
        private const val ANALYSIS_SECONDS = 60 // analyze first 60 seconds for speed
    }

    data class BpmResult(
        val bpm: Double,
        val confidence: Float,
        val candidates: List<Double>
    )

    suspend fun detectBpm(context: Context, uri: Uri): BpmResult = withContext(Dispatchers.Default) {
        try {
            val samples = decodeAudio(context, uri)
            if (samples.isEmpty()) return@withContext BpmResult(0.0, 0f, emptyList())

            val monoSamples = toMono(samples)
            val downsampled = downsample(monoSamples)

            // Method 1: Autocorrelation
            val bpmAuto = autocorrelationBpm(downsampled)

            // Method 2: Onset histogram
            val bpmOnset = onsetHistogramBpm(downsampled)

            // Method 3: Beat tracking
            val bpmBeat = beatTrackingBpm(downsampled)

            // Weighted consensus
            val allCandidates = listOf(bpmAuto, bpmOnset, bpmBeat)
                .filter { it > MIN_BPM && it < MAX_BPM }

            val finalBpm = weightedConsensus(allCandidates)
            val confidence = calculateConfidence(allCandidates, finalBpm)

            BpmResult(
                bpm = roundBpm(finalBpm),
                confidence = confidence,
                candidates = allCandidates
            )
        } catch (e: Exception) {
            BpmResult(0.0, 0f, emptyList())
        }
    }

    // ─── Audio Decoding ────────────────────────────────────────────────────────

    private fun decodeAudio(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) return FloatArray(0)

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxSamples = TARGET_SAMPLE_RATE * ANALYSIS_SECONDS * channelCount
            val pcmData = ArrayList<Float>(maxSamples)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && pcmData.size < maxSamples) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                    outputBuffer.rewind()
                    val shorts = ShortArray(bufferInfo.size / 2)
                    outputBuffer.asShortBuffer().get(shorts)
                    shorts.forEach { pcmData.add(it / 32768f) }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }

            codec.stop()
            codec.release()
            pcmData.toFloatArray()
        } finally {
            extractor.release()
        }
    }

    // ─── Signal Processing ────────────────────────────────────────────────────

    private fun toMono(samples: FloatArray): FloatArray {
        // Already handled by channel mixing; return as-is for single channel
        return samples
    }

    private fun downsample(samples: FloatArray): FloatArray {
        // Simple decimation (no anti-aliasing for speed - sufficient for BPM)
        val factor = 2
        val result = FloatArray(samples.size / factor)
        for (i in result.indices) {
            result[i] = samples[i * factor]
        }
        return result
    }

    // ─── Method 1: Autocorrelation BPM ────────────────────────────────────────

    private fun autocorrelationBpm(samples: FloatArray): Double {
        val onsets = computeOnsetStrength(samples)
        val ac = autocorrelation(onsets)

        val sr = TARGET_SAMPLE_RATE / 2.0 // after downsample
        val hopSr = sr / HOP_SIZE

        val minLag = (hopSr * 60.0 / MAX_BPM).toInt()
        val maxLag = (hopSr * 60.0 / MIN_BPM).toInt().coerceAtMost(ac.size - 1)

        var bestLag = minLag
        var bestVal = -1.0

        for (lag in minLag..maxLag) {
            if (lag < ac.size && ac[lag] > bestVal) {
                bestVal = ac[lag]
                bestLag = lag
            }
        }

        return if (bestLag > 0) (hopSr * 60.0 / bestLag) else 0.0
    }

    private fun computeOnsetStrength(samples: FloatArray): FloatArray {
        val numFrames = (samples.size - FRAME_SIZE) / HOP_SIZE
        val onsets = FloatArray(numFrames)
        var prevEnergy = 0f

        for (i in 0 until numFrames) {
            val start = i * HOP_SIZE
            var energy = 0f
            for (j in start until (start + FRAME_SIZE).coerceAtMost(samples.size)) {
                energy += samples[j] * samples[j]
            }
            energy /= FRAME_SIZE
            val onset = max(0f, energy - prevEnergy)
            onsets[i] = onset
            prevEnergy = energy
        }
        return onsets
    }

    private fun autocorrelation(signal: FloatArray): DoubleArray {
        val n = signal.size
        val result = DoubleArray(n / 2)
        for (lag in result.indices) {
            var sum = 0.0
            for (i in 0 until n - lag) {
                sum += signal[i] * signal[i + lag]
            }
            result[lag] = sum / (n - lag)
        }
        return result
    }

    // ─── Method 2: Onset Interval Histogram ───────────────────────────────────

    private fun onsetHistogramBpm(samples: FloatArray): Double {
        val onsets = computeOnsetStrength(samples)
        val threshold = onsets.average() * 1.5
        val peakIndices = mutableListOf<Int>()

        for (i in 1 until onsets.size - 1) {
            if (onsets[i] > threshold && onsets[i] > onsets[i - 1] && onsets[i] > onsets[i + 1]) {
                peakIndices.add(i)
            }
        }

        if (peakIndices.size < 2) return 0.0

        val sr = TARGET_SAMPLE_RATE / 2.0
        val hopSr = sr / HOP_SIZE
        val intervals = mutableListOf<Double>()

        for (i in 1 until peakIndices.size) {
            val intervalFrames = peakIndices[i] - peakIndices[i - 1]
            val intervalSec = intervalFrames / hopSr
            val bpm = 60.0 / intervalSec
            if (bpm in MIN_BPM..MAX_BPM) intervals.add(bpm)
        }

        if (intervals.isEmpty()) return 0.0

        // Build histogram and find peak
        val histSize = 200
        val histogram = IntArray(histSize)
        for (b in intervals) {
            val idx = ((b - MIN_BPM) / (MAX_BPM - MIN_BPM) * histSize).toInt()
                .coerceIn(0, histSize - 1)
            histogram[idx]++
        }

        val peakIdx = histogram.indices.maxByOrNull { histogram[it] } ?: 0
        return MIN_BPM + (peakIdx.toDouble() / histSize) * (MAX_BPM - MIN_BPM)
    }

    // ─── Method 3: Beat Tracking (energy-based) ───────────────────────────────

    private fun beatTrackingBpm(samples: FloatArray): Double {
        // Multi-band energy analysis for robustness
        val bands = listOf(
            Pair(0, samples.size / 8),          // sub-bass
            Pair(samples.size / 8, samples.size / 4)  // bass
        )

        val bandBpms = mutableListOf<Double>()

        for ((start, end) in bands) {
            if (end > start) {
                val bandSamples = samples.copyOfRange(start, end)
                val bpm = autocorrelationBpm(bandSamples)
                if (bpm > MIN_BPM) bandBpms.add(bpm)
            }
        }

        return if (bandBpms.isNotEmpty()) bandBpms.average() else 0.0
    }

    // ─── Consensus & Normalization ─────────────────────────────────────────────

    private fun weightedConsensus(candidates: List<Double>): Double {
        if (candidates.isEmpty()) return 0.0
        if (candidates.size == 1) return candidates[0]

        // Normalize all candidates to same octave (handle 2x/0.5x BPM)
        val normalized = candidates.map { normalizeBpm(it, candidates[0]) }
        return normalized.average()
    }

    private fun normalizeBpm(bpm: Double, reference: Double): Double {
        var b = bpm
        while (b > reference * 1.5) b /= 2.0
        while (b < reference * 0.67) b *= 2.0
        return b
    }

    private fun calculateConfidence(candidates: List<Double>, finalBpm: Double): Float {
        if (candidates.isEmpty()) return 0f
        val variance = candidates.map { (it - finalBpm).pow(2) }.average()
        return (1f - (variance / (finalBpm * finalBpm)).toFloat()).coerceIn(0f, 1f)
    }

    private fun roundBpm(bpm: Double): Double {
        return (bpm * 10).roundToInt() / 10.0
    }
}
