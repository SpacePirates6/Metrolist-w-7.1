package com.metrolist.music.eq.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.metrolist.music.eq.data.ParametricEQ
import com.metrolist.music.eq.data.ParametricEQBand
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Custom audio processor for ExoPlayer that applies parametric EQ using biquad filters.
 * Supports filter stacking: hardware correction (AutoEq) runs first, then user preference (custom profile).
 * Uses ParametricEQ format from AutoEQ project.
 */
@UnstableApi
@SuppressWarnings("Deprecated")
class CustomEqualizerAudioProcessor : AudioProcessor {

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var isActive = false

    private var inputBuffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    @Volatile
    private var userFilters: List<BiquadFilter> = emptyList()

    @Volatile
    private var autoEqFilters: List<BiquadFilter> = emptyList()

    @Volatile
    var autoEqEnabled: Boolean = false

    @Volatile
    private var userPreampGain: Double = 1.0

    @Volatile
    private var autoEqPreampGain: Double = 1.0

    private var pendingUserProfile: ParametricEQ? = null

    companion object {
        private const val TAG = "CustomEqualizerAudioProcessor"
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    /**
     * Apply a user EQ profile (Bass Boost, Acoustic, custom, etc.)
     */
    @Synchronized
    fun applyProfile(parametricEQ: ParametricEQ) {
        if (sampleRate == 0) {
            Timber.tag(TAG)
                .d("Audio processor not configured yet. Storing user profile as pending with ${parametricEQ.bands.size} bands")
            pendingUserProfile = parametricEQ
            return
        }

        userPreampGain = 10.0.pow(parametricEQ.preamp / 20.0)
        userFilters = createFiltersFromBands(parametricEQ.bands)
        userFilters.forEach { it.reset() }

        Timber.tag(TAG)
            .d("Applied user EQ profile with ${userFilters.size} bands and ${parametricEQ.preamp} dB preamp")
    }

    /**
     * Apply the hardware correction (AutoEq) profile. Pass null to clear.
     */
    @Synchronized
    fun applyAutoEqProfile(profile: ParametricEQ?) {
        if (profile == null) {
            autoEqPreampGain = 1.0
            autoEqFilters = emptyList()
            Timber.tag(TAG).d("AutoEq profile cleared")
            return
        }

        if (sampleRate == 0) {
            Timber.tag(TAG).d("Audio processor not configured yet. AutoEq profile will be applied on configure")
            return
        }

        autoEqPreampGain = 10.0.pow(profile.preamp / 20.0)
        autoEqFilters = createFiltersFromBands(profile.bands)
        autoEqFilters.forEach { it.reset() }

        Timber.tag(TAG)
            .d("Applied AutoEq profile with ${autoEqFilters.size} bands and ${profile.preamp} dB preamp")
    }

    /**
     * Disable the user equalizer (clears user profile only; AutoEq chain is untouched)
     */
    @Synchronized
    fun disable() {
        userPreampGain = 1.0
        userFilters = emptyList()
        pendingUserProfile = null
        Timber.tag(TAG).d("User equalizer disabled")
    }

    /**
     * Check if any EQ is active (user or AutoEq)
     */
    fun isEnabled(): Boolean =
        (autoEqEnabled && autoEqFilters.isNotEmpty()) || userFilters.isNotEmpty()

    /**
     * Create biquad filters from ParametricEQ bands.
     * Only creates filters for enabled bands below Nyquist frequency.
     */
    private fun createFiltersFromBands(bands: List<ParametricEQBand>): List<BiquadFilter> {
        if (sampleRate == 0) return emptyList()
        return bands
            .filter { it.enabled && it.frequency < sampleRate / 2.0 }
            .map { band ->
                BiquadFilter(
                    sampleRate = sampleRate,
                    frequency = band.frequency,
                    gain = band.gain,
                    q = band.q,
                    filterType = band.filterType
                )
            }
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        Timber.tag(TAG)
            .d("Configured: sampleRate=$sampleRate, channels=$channelCount, encoding=$encoding")

        // Apply pending user profile if one exists
        pendingUserProfile?.let { profile ->
            userPreampGain = 10.0.pow(profile.preamp / 20.0)
            userFilters = createFiltersFromBands(profile.bands)
            pendingUserProfile = null
            Timber.tag(TAG)
                .d("Applied pending user profile with ${userFilters.size} bands")
        }

        if (encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (channelCount !in 1..8) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        isActive = true
        return inputAudioFormat
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        val hasAutoEq = autoEqEnabled && autoEqFilters.isNotEmpty()
        val hasUserEq = userFilters.isNotEmpty()
        if (!hasAutoEq && !hasUserEq) {
            // Passthrough mode - directly use input as output
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return

            if (outputBuffer.capacity() < remaining) {
                outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
            } else {
                outputBuffer.clear()
            }
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) {
            return
        }

        // Ensure we have our own output buffer (reuse if possible to avoid allocations)
        // Note: We MUST NOT use inputBuffer as outputBuffer if we modify it
        if (outputBuffer === EMPTY_BUFFER || outputBuffer === inputBuffer) {
            // Need new buffer - was empty or same as input
            outputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
        } else if (outputBuffer.capacity() < inputSize) {
            // Need larger buffer
            outputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
        } else {
            // Reuse existing buffer (most common path)
            outputBuffer.clear()
        }

        // Process audio samples
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                // Ensure the output buffer is ready to receive data
                // We don't set limit() here because putShort will advance position
                processAudioBuffer16Bit(inputBuffer, outputBuffer)
            }
            else -> {
                // Unsupported format, passthrough
                outputBuffer.put(inputBuffer)
            }
        }

        outputBuffer.flip()
        // inputBuffer position is already updated by processAudioBuffer16Bit/put
    }

    /**
     * Process 16-bit PCM audio through all biquad filters.
     * Order: combined preamp -> AutoEq (hardware correction) -> user profile.
     * For 5.1/7.1: FL/BL/SL/FC use left EQ, FR/BR/SR use right EQ, LFE bypassed.
     */
    private fun processAudioBuffer16Bit(input: ByteBuffer, output: ByteBuffer) {
        val combinedPreamp = userPreampGain * (if (autoEqEnabled) autoEqPreampGain else 1.0)
        val sampleCount = input.remaining() / 2

        if (channelCount == 1 || channelCount == 2) {
            processMonoOrStereo(input, output, sampleCount, combinedPreamp)
        } else {
            processSurround(input, output, sampleCount, combinedPreamp)
        }
    }

    private fun processMonoOrStereo(
        input: ByteBuffer,
        output: ByteBuffer,
        sampleCount: Int,
        combinedPreamp: Double,
    ) {
        repeat(sampleCount / channelCount) {
            if (channelCount == 1) {
                var sample = input.getShort().toDouble() / 32768.0 * combinedPreamp
                if (autoEqEnabled) {
                    for (filter in autoEqFilters) {
                        sample = filter.processSample(sample)
                    }
                }
                for (filter in userFilters) {
                    sample = filter.processSample(sample)
                }
                output.putShort(
                    (sample * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort(),
                )
            } else {
                var left = input.getShort().toDouble() / 32768.0 * combinedPreamp
                var right = input.getShort().toDouble() / 32768.0 * combinedPreamp
                if (autoEqEnabled) {
                    for (filter in autoEqFilters) {
                        val (l, r) = filter.processStereo(left, right)
                        left = l
                        right = r
                    }
                }
                for (filter in userFilters) {
                    val (l, r) = filter.processStereo(left, right)
                    left = l
                    right = r
                }
                output.putShort(
                    (left * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort(),
                )
                output.putShort(
                    (right * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort(),
                )
            }
        }
    }

    /**
     * Process 5.1 or 7.1 surround. Interleave order: FL, FR, FC, LFE, BL, BR, SL, SR.
     * Left channels (FL, BL, SL, FC) use left EQ. Right channels (FR, BR, SR) use right EQ.
     * LFE is bypassed to prevent headphone bass-boost from muddying the subwoofer.
     */
    private fun processSurround(
        input: ByteBuffer,
        output: ByteBuffer,
        sampleCount: Int,
        combinedPreamp: Double,
    ) {
        val frameCount = sampleCount / channelCount
        repeat(frameCount) {
            for (channelIndex in 0 until channelCount) {
                val rawSample = input.getShort().toDouble() / 32768.0

                val processedSample = when (channelIndex) {
                    3 -> {
                        rawSample
                    }
                    0, 2, 4, 6 -> {
                        var sample = rawSample * combinedPreamp
                        if (autoEqEnabled) {
                            for (filter in autoEqFilters) {
                                sample = filter.processSample(sample)
                            }
                        }
                        for (filter in userFilters) {
                            sample = filter.processSample(sample)
                        }
                        sample
                    }
                    1, 5, 7 -> {
                        var sample = rawSample * combinedPreamp
                        if (autoEqEnabled) {
                            for (filter in autoEqFilters) {
                                sample = filter.processRightSample(sample)
                            }
                        }
                        for (filter in userFilters) {
                            sample = filter.processRightSample(sample)
                        }
                        sample
                    }
                    else -> rawSample * combinedPreamp
                }

                val outShort =
                    (processedSample * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                output.putShort(outShort)
            }
        }
    }

    override fun getOutput(): ByteBuffer {
        // Return output buffer ready for reading (already flipped in queueInput)
        val buffer = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean {
        return inputEnded && outputBuffer.remaining() == 0
    }

    @Deprecated("Deprecated in Java")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        userFilters.forEach { it.reset() }
        autoEqFilters.forEach { it.reset() }
    }

    override fun reset() {
        @Suppress("DEPRECATION")
        flush()
        inputBuffer = EMPTY_BUFFER
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        isActive = false
        userFilters.forEach { it.reset() }
        autoEqFilters.forEach { it.reset() }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }
}
