package com.example.audio

import android.content.Context
import com.example.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

object BuiltInPartyTracks {

    private const val SAMPLE_RATE = 44100
    private const val DURATION_SECONDS = 28

    private data class TrackSpec(
        val id: String,
        val fileName: String,
        val title: String,
        val artist: String,
        val artworkKey: String,
        val bpm: Double,
        val bassRootsHz: DoubleArray,
        val arpRatios: DoubleArray,
        val style: Int
    )

    private val specs = listOf(
        TrackSpec(
            id = "track_neon_pulse",
            fileName = "neon_pulse.wav",
            title = "Neon Pulse",
            artist = "SYNCBEAT Collective",
            artworkKey = "pulse",
            bpm = 124.0,
            bassRootsHz = doubleArrayOf(55.0, 55.0, 65.41, 73.42), // A1, A1, C2, D2
            arpRatios = doubleArrayOf(4.0, 6.0, 8.0, 10.0, 8.0, 6.0),
            style = 0
        ),
        TrackSpec(
            id = "track_midnight_horizon",
            fileName = "midnight_horizon.wav",
            title = "Midnight Horizon",
            artist = "Kairo & The Wave",
            artworkKey = "horizon",
            bpm = 114.0,
            bassRootsHz = doubleArrayOf(61.74, 49.0, 55.0, 41.20), // B1, G1, A1, E1
            arpRatios = doubleArrayOf(4.0, 5.0, 6.0, 7.5, 6.0, 5.0),
            style = 1
        ),
        TrackSpec(
            id = "track_voltage_drift",
            fileName = "voltage_drift.wav",
            title = "Voltage Drift",
            artist = "Hyperion Sound",
            artworkKey = "voltage",
            bpm = 128.0,
            bassRootsHz = doubleArrayOf(43.65, 43.65, 51.91, 58.27), // F1, F1, G#1, A#1
            arpRatios = doubleArrayOf(4.0, 6.0, 7.0, 8.0, 12.0, 8.0),
            style = 2
        )
    )

    suspend fun ensureBuiltInTracks(context: Context): List<AudioTrack> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "party_tracks").apply { mkdirs() }
        specs.map { spec ->
            val file = File(dir, spec.fileName)
            if (!file.exists() || file.length() < 100_000L) {
                synthesizeWavTrack(file, spec)
            }
            val waveform = AudioWaveformExtractor.extractWaveformFromWavFile(file)
            AudioTrack(
                id = spec.id,
                title = spec.title,
                artist = spec.artist,
                durationMs = DURATION_SECONDS * 1000L,
                artworkKey = spec.artworkKey,
                mimeType = "audio/wav",
                fileSizeBytes = file.length(),
                localPath = file.absolutePath,
                waveformBins = waveform
            )
        }
    }

    private fun synthesizeWavTrack(outputFile: File, spec: TrackSpec) {
        val totalSamples = SAMPLE_RATE * DURATION_SECONDS
        val numChannels = 2
        val byteRate = SAMPLE_RATE * numChannels * 2
        val dataBytes = totalSamples * numChannels * 2

        val beatDuration = 60.0 / spec.bpm
        val sixteenthDuration = beatDuration / 4.0

        BufferedOutputStream(FileOutputStream(outputFile), 65536).use { out ->
            // 44-byte WAV RIFF header
            val header = ByteArray(44)
            fun writeString(offset: Int, value: String) {
                for (i in value.indices) header[offset + i] = value[i].code.toByte()
            }
            fun writeIntLE(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = ((value shr 8) and 0xFF).toByte()
                header[offset + 2] = ((value shr 16) and 0xFF).toByte()
                header[offset + 3] = ((value shr 24) and 0xFF).toByte()
            }
            fun writeShortLE(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            }

            writeString(0, "RIFF")
            writeIntLE(4, 36 + dataBytes)
            writeString(8, "WAVE")
            writeString(12, "fmt ")
            writeIntLE(16, 16)
            writeShortLE(20, 1) // PCM
            writeShortLE(22, numChannels)
            writeIntLE(24, SAMPLE_RATE)
            writeIntLE(28, byteRate)
            writeShortLE(32, numChannels * 2)
            writeShortLE(34, 16)
            writeString(36, "data")
            writeIntLE(40, dataBytes)
            out.write(header)

            val chunk = ByteArray(4096)
            var chunkPos = 0
            var noiseSeed = 0x13579BDF

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val beatPos = t / beatDuration
                val beatIndex = beatPos.toInt()
                val beatPhase = beatPos - beatIndex
                val stepPos = t / sixteenthDuration
                val stepIndex = stepPos.toInt()
                val stepPhase = stepPos - stepIndex

                val barIndex = beatIndex / 4
                val rootHz = spec.bassRootsHz[barIndex % spec.bassRootsHz.size]

                // 1. Punchy 4-on-the-floor kick drum
                val kickEnv = exp(-beatPhase * 11.5)
                val kickFreq = 46.0 + 110.0 * exp(-beatPhase * 28.0)
                val kick = sin(2.0 * PI * kickFreq * (beatPhase * beatDuration)) * kickEnv * 0.62

                // 2. Crisp off-beat / 2&4 clap-snare + 16th hi-hat
                noiseSeed = noiseSeed * 1664525 + 1013904223
                val whiteNoise = ((noiseSeed shr 16) and 0xFFFF) / 32768.0 - 1.0
                val isSnareBeat = (beatIndex % 2 == 1)
                val snareEnv = if (isSnareBeat) exp(-beatPhase * 15.0) else 0.0
                val hatEnv = if (stepIndex % 2 == 1) exp(-stepPhase * 22.0) else exp(-stepPhase * 34.0) * 0.45
                val percussion = whiteNoise * (snareEnv * 0.24 + hatEnv * 0.11)

                // 3. Sidechained rolling synth bass
                val sidechain = (1.0 - exp(-beatPhase * 7.5)).coerceIn(0.15, 1.0)
                val bassGate = if (spec.style == 2 && stepIndex % 4 == 0) 0.4 else 1.0
                val bassOsc = sawApprox(rootHz * t) * 0.65 + sin(2.0 * PI * rootHz * t) * 0.55
                val bass = bassOsc * sidechain * bassGate * 0.34

                // 4. Stereo shimmer arpeggio & warm pad
                val arpFreq = rootHz * spec.arpRatios[stepIndex % spec.arpRatios.size]
                val arpEnv = exp(-stepPhase * 5.2) * (if (barIndex >= 1) 1.0 else 0.45)
                val arpL = sin(2.0 * PI * arpFreq * t) * arpEnv * 0.16
                val arpR = sin(2.0 * PI * (arpFreq * 1.003) * t) * arpEnv * 0.16

                val padFreq1 = rootHz * 4.0
                val padFreq2 = rootHz * 6.0
                val pad = (sin(2.0 * PI * padFreq1 * t) + 0.6 * sin(2.0 * PI * padFreq2 * t)) * 0.09 * sidechain

                // Smooth fade-in and fade-out at track boundaries
                val fadeIn = (t / 0.35).coerceIn(0.0, 1.0)
                val fadeOut = ((DURATION_SECONDS - t) / 0.6).coerceIn(0.0, 1.0)
                val masterEnv = fadeIn * fadeOut

                val mixL = tanh((kick + percussion + bass + arpL + pad) * 1.15) * masterEnv
                val mixR = tanh((kick + percussion * 0.92 + bass + arpR + pad) * 1.15) * masterEnv

                val sL = (mixL * 31500.0).toInt().coerceIn(-32767, 32767)
                val sR = (mixR * 31500.0).toInt().coerceIn(-32767, 32767)

                chunk[chunkPos++] = (sL and 0xFF).toByte()
                chunk[chunkPos++] = ((sL shr 8) and 0xFF).toByte()
                chunk[chunkPos++] = (sR and 0xFF).toByte()
                chunk[chunkPos++] = ((sR shr 8) and 0xFF).toByte()

                if (chunkPos == chunk.size) {
                    out.write(chunk)
                    chunkPos = 0
                }
            }
            if (chunkPos > 0) {
                out.write(chunk, 0, chunkPos)
            }
        }
    }

    private fun sawApprox(phaseCycles: Double): Double {
        val f = phaseCycles - phaseCycles.toLong()
        return 2.0 * f - 1.0
    }
}
