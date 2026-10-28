package org.sand.ticwatch.phone.snore

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Duration
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/** How much of a listening slot was snoring (0..1), or null when the slot couldn't be measured. */
fun interface SnoreMeter {
    suspend fun snoringFraction(slot: Duration): Double?
}

/**
 * [SnoreMeter] on the phone microphone (mirrored from watch-app). Audio is analysed frame by frame in memory and dropped: only
 * the per-frame features reach [SnoreDetector], nothing is stored or sent. Without RECORD_AUDIO, or
 * when the system silences the microphone (a foreground service started from the background gets no
 * while-in-use microphone access - it then reads pure digital silence), the slot yields null.
 */
class MicrophoneSnoreMeter(private val context: Context) : SnoreMeter {

    override suspend fun snoringFraction(slot: Duration): Double? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; snoring not measured")
            return@withContext null
        }
        val record = openRecorder() ?: return@withContext null
        val detector = SnoreDetector(SAMPLE_RATE_HZ)
        val frame = ShortArray(SnoreDetector.FRAME_SIZE)
        try {
            record.startRecording()
            val deadline = SystemClock.elapsedRealtime() + slot.toMillis()
            while (SystemClock.elapsedRealtime() < deadline) {
                ensureActive()
                val read = record.read(frame, 0, frame.size)
                if (read < 0) {
                    Log.w(TAG, "AudioRecord read failed: $read")
                    return@withContext null
                }
                if (read == frame.size) detector.onFrame(frame)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        detector.snoringFraction().also { Log.i(TAG, "Snoring fraction over ${slot.seconds}s: $it") }
    }

    // Permission is checked by the caller right before; lint can't see it through the early return.
    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, ENCODING)
        if (minBuffer <= 0) return null.also { Log.w(TAG, "No microphone buffer size: $minBuffer") }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE_HZ, CHANNEL, ENCODING,
            maxOf(minBuffer, SnoreDetector.FRAME_SIZE * BYTES_PER_SAMPLE * BUFFERED_FRAMES),
        )
        if (record.state == AudioRecord.STATE_INITIALIZED) return record
        record.release()
        Log.w(TAG, "Microphone unavailable")
        return null
    }

    private companion object {
        const val TAG = "MicrophoneSnoreMeter"
        const val SAMPLE_RATE_HZ = 8_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
        const val BUFFERED_FRAMES = 8
    }
}

/**
 * Snoring from audio frame features. A snore is a loud (well above the slot's noise floor), low-pitched
 * (energy mostly below [LOW_PASS_HZ], few zero crossings) burst of [MIN_BURST_SECONDS]..[MAX_BURST_SECONDS],
 * and snores repeat with the breath: only runs of at least [MIN_CHAIN_BURSTS] bursts whose onsets are
 * [MIN_BREATH_SECONDS]..[MAX_BREATH_SECONDS] apart, with a regular rhythm, count. Speech, a TV or a single
 * cough fail the rhythm test. The result is the share of the slot covered by such runs. Thresholds are
 * uncalibrated starting points.
 */
class SnoreDetector(private val sampleRateHz: Int) {

    private class FrameFeatures(val decibels: Double, val lowBandRatio: Double, val zeroCrossingRate: Double)

    private class Burst(val startFrame: Int, val endFrame: Int)

    private val frames = mutableListOf<FrameFeatures>()
    private var anySound = false
    private val lowPassAlpha = 1 - exp(-2 * Math.PI * LOW_PASS_HZ / sampleRateHz)

    fun onFrame(samples: ShortArray) {
        var energy = 0.0
        var lowEnergy = 0.0
        var lowPassed = 0.0
        var crossings = 0
        for (i in samples.indices) {
            val x = samples[i].toDouble()
            if (x != 0.0) anySound = true
            energy += x * x
            lowPassed += lowPassAlpha * (x - lowPassed)
            lowEnergy += lowPassed * lowPassed
            if (i > 0 && (samples[i - 1] < 0) != (samples[i] < 0)) crossings++
        }
        val rms = sqrt(energy / samples.size)
        frames += FrameFeatures(
            decibels = 20 * log10(rms / FULL_SCALE + EPSILON),
            lowBandRatio = if (energy > 0) lowEnergy / energy else 0.0,
            zeroCrossingRate = crossings.toDouble() / samples.size,
        )
    }

    /** Null when nothing was heard at all (blocked microphone) or the slot was too short to judge. */
    fun snoringFraction(): Double? {
        if (!anySound || frames.size < MIN_FRAMES) return null
        val floor = frames.map { it.decibels }.sorted()[(frames.size * NOISE_FLOOR_PERCENTILE).toInt()]
        val bursts = bursts(floor)
        val snoringFrames = snoreChains(bursts).sumOf { chain -> chain.last().endFrame - chain.first().startFrame + 1 }
        return snoringFrames.toDouble() / frames.size
    }

    private fun isSnoreLike(frame: FrameFeatures, floor: Double): Boolean =
        frame.decibels >= floor + MIN_LOUDNESS_ABOVE_FLOOR_DB &&
            frame.lowBandRatio >= MIN_LOW_BAND_RATIO &&
            frame.zeroCrossingRate <= MAX_ZERO_CROSSING_RATE

    /** Runs of snore-like frames, bridging holes of up to [MAX_HOLE_FRAMES], kept if burst-sized. */
    private fun bursts(floor: Double): List<Burst> {
        val bursts = mutableListOf<Burst>()
        var start = -1
        var lastLoud = -1
        for (index in frames.indices) {
            if (!isSnoreLike(frames[index], floor)) continue
            if (start >= 0 && index - lastLoud > MAX_HOLE_FRAMES + 1) {
                bursts += Burst(start, lastLoud)
                start = -1
            }
            if (start < 0) start = index
            lastLoud = index
        }
        if (start >= 0) bursts += Burst(start, lastLoud)
        return bursts.filter { secondsOf(it.endFrame - it.startFrame + 1) in MIN_BURST_SECONDS..MAX_BURST_SECONDS }
    }

    /** Groups bursts into breath-rhythmic chains; irregular or too short chains are dropped. */
    private fun snoreChains(bursts: List<Burst>): List<List<Burst>> {
        val chains = mutableListOf<MutableList<Burst>>()
        for (burst in bursts) {
            val chain = chains.lastOrNull()
            val gap = chain?.let { secondsOf(burst.startFrame - it.last().startFrame) }
            if (chain != null && gap != null && gap in MIN_BREATH_SECONDS..MAX_BREATH_SECONDS) chain += burst
            else chains += mutableListOf(burst)
        }
        return chains.filter { it.size >= MIN_CHAIN_BURSTS && isRegular(it) }
    }

    private fun isRegular(chain: List<Burst>): Boolean {
        val periods = chain.zipWithNext { a, b -> secondsOf(b.startFrame - a.startFrame) }
        val mean = periods.average()
        val deviation = sqrt(periods.sumOf { (it - mean) * (it - mean) } / periods.size)
        return mean > 0 && deviation / mean <= MAX_RHYTHM_VARIATION
    }

    private fun secondsOf(frameCount: Int): Double = frameCount.toDouble() * FRAME_SIZE / sampleRateHz

    companion object {
        /** 32 ms at 8 kHz. */
        const val FRAME_SIZE = 256
        private const val FULL_SCALE = 32_768.0
        private const val EPSILON = 1e-9
        private const val LOW_PASS_HZ = 500.0
        private const val MIN_FRAMES = 300
        private const val NOISE_FLOOR_PERCENTILE = 0.2
        private const val MIN_LOUDNESS_ABOVE_FLOOR_DB = 10.0
        private const val MIN_LOW_BAND_RATIO = 0.5
        private const val MAX_ZERO_CROSSING_RATE = 0.15
        private const val MAX_HOLE_FRAMES = 2
        private const val MIN_BURST_SECONDS = 0.3
        private const val MAX_BURST_SECONDS = 3.5
        private const val MIN_BREATH_SECONDS = 2.0
        private const val MAX_BREATH_SECONDS = 9.0
        private const val MIN_CHAIN_BURSTS = 3
        private const val MAX_RHYTHM_VARIATION = 0.35
    }
}
