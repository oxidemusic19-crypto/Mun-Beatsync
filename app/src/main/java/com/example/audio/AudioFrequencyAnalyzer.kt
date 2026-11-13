package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

data class FrequencySpectrumFrame(
    val bands: List<Float> = List(AudioFrequencyAnalyzer.BAND_COUNT) { 0.08f },
    val bassEnergy: Float = 0.08f,
    val midEnergy: Float = 0.08f,
    val highEnergy: Float = 0.08f,
    val rmsLevel: Float = 0.05f,
    val isLivePcmSignal: Boolean = false
)

/**
 * Pure-Kotlin 1024-point Radix-2 FFT spectrum analyzer operating directly on 16-bit stereo
 * 44.1 kHz WebRTC PCM frames without requiring RECORD_AUDIO permission.
 */
object AudioFrequencyAnalyzer {

    const val FFT_SIZE = 1024
    const val BAND_COUNT = 28

    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos((2.0 * PI * i) / (FFT_SIZE - 1)))).toFloat()
    }

    // Precompute logarithmic frequency bin ranges across ~43 Hz (bin 1) .. ~16 kHz (bin 372)
    private val bandBinRanges: Array<IntRange> = Array(BAND_COUNT) { b ->
        val minBin = 1.0
        val maxBin = 372.0
        val f0 = minBin * (maxBin / minBin).pow(b.toDouble() / BAND_COUNT.toDouble())
        val f1 = minBin * (maxBin / minBin).pow((b + 1).toDouble() / BAND_COUNT.toDouble())
        val start = f0.toInt().coerceIn(1, FFT_SIZE / 2 - 1)
        val end = f1.toInt().coerceIn(start, FFT_SIZE / 2 - 1)
        start..end
    }

    fun computeRawSpectrum(pcmBytes: ByteArray): FrequencySpectrumFrame {
        if (pcmBytes.size < 4) return FrequencySpectrumFrame(isLivePcmSignal = false)

        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)

        val totalStereoFrames = pcmBytes.size / 4
        val framesToRead = minOf(totalStereoFrames, FFT_SIZE)
        var sumSq = 0.0
        var sampleCount = 0

        for (i in 0 until framesToRead) {
            val byteIdx = i * 4
            val left = ((pcmBytes[byteIdx + 1].toInt() shl 8) or (pcmBytes[byteIdx].toInt() and 0xFF)).toFloat() / 32768f
            val right = ((pcmBytes[byteIdx + 3].toInt() shl 8) or (pcmBytes[byteIdx + 2].toInt() and 0xFF)).toFloat() / 32768f
            val mono = (left + right) * 0.5f
            sumSq += mono * mono
            sampleCount++
            real[i] = mono * hannWindow[i]
        }

        fftInPlace(real, imag)

        val magnitudes = FloatArray(FFT_SIZE / 2)
        for (k in 1 until FFT_SIZE / 2) {
            magnitudes[k] = hypot(real[k], imag[k]) / (FFT_SIZE * 0.25f)
        }

        val bands = List(BAND_COUNT) { b ->
            val range = bandBinRanges[b]
            var peak = 0f
            for (bin in range) {
                if (magnitudes[bin] > peak) peak = magnitudes[bin]
            }
            // Perceptual log compression scaled to 0.06..1.0
            val dbScaled = (ln(1.0 + peak * 18.0) / ln(19.0)).toFloat()
            dbScaled.coerceIn(0.06f, 1.0f)
        }

        val bass = bands.subList(0, 11).average().toFloat().coerceIn(0.06f, 1.0f)
        val mid = bands.subList(11, 20).average().toFloat().coerceIn(0.06f, 1.0f)
        val high = bands.subList(20, BAND_COUNT).average().toFloat().coerceIn(0.06f, 1.0f)
        val rms = if (sampleCount > 0) kotlin.math.sqrt(sumSq / sampleCount).toFloat().coerceIn(0f, 1f) else 0f

        return FrequencySpectrumFrame(
            bands = bands,
            bassEnergy = bass,
            midEnergy = mid,
            highEnergy = high,
            rmsLevel = rms,
            isLivePcmSignal = true
        )
    }

    fun smooth(previous: FrequencySpectrumFrame, current: FrequencySpectrumFrame): FrequencySpectrumFrame {
        if (previous.bands.size != current.bands.size) return current
        val smoothedBands = List(current.bands.size) { i ->
            val prevVal = previous.bands[i]
            val nextVal = current.bands[i]
            val coeff = if (nextVal > prevVal) 0.68f else 0.28f
            (prevVal + (nextVal - prevVal) * coeff).coerceIn(0.06f, 1.0f)
        }
        fun smoothScalar(p: Float, c: Float): Float {
            val coeff = if (c > p) 0.65f else 0.26f
            return (p + (c - p) * coeff).coerceIn(0.06f, 1.0f)
        }
        return FrequencySpectrumFrame(
            bands = smoothedBands,
            bassEnergy = smoothScalar(previous.bassEnergy, current.bassEnergy),
            midEnergy = smoothScalar(previous.midEnergy, current.midEnergy),
            highEnergy = smoothScalar(previous.highEnergy, current.highEnergy),
            rmsLevel = smoothScalar(previous.rmsLevel, current.rmsLevel),
            isLivePcmSignal = current.isLivePcmSignal
        )
    }

    private fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angle = (-2.0 * PI / len)
            val wLenR = cos(angle).toFloat()
            val wLenI = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until half) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val idx = i + k + half
                    val tR = wR * real[idx] - wI * imag[idx]
                    val tI = wR * imag[idx] + wI * real[idx]
                    real[idx] = uR - tR
                    imag[idx] = uI - tI
                    real[i + k] = uR + tR
                    imag[i + k] = uI + tI

                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
