/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Upmixes a stereo (2-channel) PCM 16-bit stream to either 5.1 (6-channel) or
 * 7.1 (8-channel) surround using Mid-Side decomposition, crossover filtering,
 * delay lines, and cascaded all-pass decorrelation.
 *
 * Channel layout — 7.1 (SMPTE/ITU):
 * FL, FR, FC, LFE, BL, BR, SL, SR
 *
 * Channel layout — 5.1 (SMPTE/ITU):
 * FL, FR, FC, LFE, SL, SR
 * The 5.1 surrounds are an equal-power fold of the 7.1 rear (BL/BR) and
 * side (SL/SR) signals: SL₅₁ = (BL + SL) × (1/√2), SR₅₁ = (BR + SR) × (1/√2)
 *
 * DSP pipeline per sample frame (identical for both modes):
 * 1. Decompose stereo into Mid (L+R)/2 and Side (L-R)/2
 * 2. FC  = bandpass(Mid, 100–7kHz) × centerGain
 * 3. LFE = lowpass(Mid, 120Hz)     × lfeGain
 * 4. FL  = L − divergence × FC,   FR = R − divergence × FC
 * 5. Sides (SL/SR) = bandpass(Side, 150–7kHz) → delay → 2× allpass → gain
 * 6. Rears (BL/BR) = bandpass(Side + 0.25×Mid, 150–7kHz) → longer delay → 2× allpass → gain
 * 7. [5.1 only] SL₅₁ = (BL+SL)/√2, SR₅₁ = (BR+SR)/√2
 *
 * The processor can be toggled at runtime via [enabled]. When disabled,
 * [isActive] returns false and ExoPlayer bypasses it with zero overhead.
 * Parameter changes are smoothed automatically to prevent audio zippering.
 */
@UnstableApi
class UpmixAudioProcessor : AudioProcessor {

    enum class UpmixMode(val channelCount: Int) {
        SURROUND_5_1(6),
        SURROUND_7_1(8),
    }

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var outputMode: UpmixMode = UpmixMode.SURROUND_7_1

    @Volatile
    var surroundIntensity: Float = DEFAULT_SURROUND_INTENSITY

    @Volatile
    var centerFocus: Float = DEFAULT_CENTER_FOCUS

    @Volatile
    var bassLevel: Float = DEFAULT_BASS_LEVEL

    @Volatile
    var channelDistances: FloatArray = FloatArray(8) { DEFAULT_CHANNEL_DISTANCE }

    @Volatile
    var channelTypes: Array<String> = Array(8) { CHANNEL_TYPE_DEFAULT }

    // Dynamic Crossovers
    @Volatile
    var lfeCutoff: Float = DEFAULT_LFE_CUTOFF

    @Volatile
    var centerHpfCutoff: Float = DEFAULT_CENTER_HPF_CUTOFF

    @Volatile
    var centerLpfCutoff: Float = DEFAULT_CENTER_LPF_CUTOFF

    @Volatile
    var surroundHpfCutoff: Float = DEFAULT_SURROUND_HPF_CUTOFF

    @Volatile
    var surroundLpfCutoff: Float = DEFAULT_SURROUND_LPF_CUTOFF

    // The output mode that was committed during the last configure() call.
    // outputMode may change at any time, but the channel layout wired into ExoPlayer's
    // audio sink can only change when configure() is called again (next seek / track change).
    // Reading outputMode directly in queueInput() would produce a channel-count mismatch and
    // audible artefacts (squeal / distortion).
    private var activeOutputMode: UpmixMode = UpmixMode.SURROUND_7_1

    // Smoothed variables for anti-zippering
    private var currentSurroundIntensity: Float = DEFAULT_SURROUND_INTENSITY
    private var currentCenterFocus: Float = DEFAULT_CENTER_FOCUS
    private var currentBassLevel: Float = DEFAULT_BASS_LEVEL

    // Track applied crossovers to avoid unnecessary biquad recalculations
    private var appliedLfeCutoff: Float = DEFAULT_LFE_CUTOFF
    private var appliedCenterHpfCutoff: Float = DEFAULT_CENTER_HPF_CUTOFF
    private var appliedCenterLpfCutoff: Float = DEFAULT_CENTER_LPF_CUTOFF
    private var appliedSurroundHpfCutoff: Float = DEFAULT_SURROUND_HPF_CUTOFF
    private var appliedSurroundLpfCutoff: Float = DEFAULT_SURROUND_LPF_CUTOFF

    private var inputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingOutputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var sampleRate: Int = 0

    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    // ── DSP components (created in initializeDsp when sample rate is known) ──

    private var centerHpf: MonoBiquad? = null
    private var centerLpf: MonoBiquad? = null
    private var lfeLpf: MonoBiquad? = null
    private var surroundHpfs: Array<MonoBiquad>? = null
    private var surroundLpfs: Array<MonoBiquad>? = null
    private var allPassesStage1: Array<MonoBiquad>? = null
    private var allPassesStage2: Array<MonoBiquad>? = null
    private var delayLines: Array<DelayLine>? = null

    // ── AudioProcessor implementation ───────────────────────────────────────

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        this.inputAudioFormat = inputAudioFormat

        if (enabled && inputAudioFormat.channelCount == 2) {
            activeOutputMode = outputMode
            sampleRate = inputAudioFormat.sampleRate
            initializeDsp()
            pendingOutputAudioFormat = AudioProcessor.AudioFormat(
                sampleRate,
                activeOutputMode.channelCount,
                C.ENCODING_PCM_16BIT,
            )
        } else {
            pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        }

        return if (pendingOutputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) {
            pendingOutputAudioFormat
        } else {
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean =
        pendingOutputAudioFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            outputBuffer = EMPTY_BUFFER
            return
        }

        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val frameCount = (limit - position) / (INPUT_CHANNELS * BYTES_PER_SAMPLE)
        val currentOutputChannels = activeOutputMode.channelCount
        val requiredSize = frameCount * currentOutputChannels * BYTES_PER_SAMPLE

        if (buffer.capacity() < requiredSize) {
            buffer = ByteBuffer.allocateDirect(requiredSize).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // Update Dynamic Crossovers if they changed
        updateCrossoversIfNeeded()

        val cHpf = centerHpf!!
        val cLpf = centerLpf!!
        val lfe = lfeLpf!!
        val sHpfs = surroundHpfs!!
        val sLpfs = surroundLpfs!!
        val aPs1 = allPassesStage1!!
        val aPs2 = allPassesStage2!!
        val dls = delayLines!!
        val dist = channelDistances.copyOf()
        val types = channelTypes.copyOf()
        val mode = activeOutputMode

        val distGains = FloatArray(8) { i ->
            (REFERENCE_DISTANCE / dist[i].coerceAtLeast(0.1f)).coerceIn(0f, 4f)
        }

        while (inputBuffer.position() < limit) {
            // Apply smoothing parameter filter per-sample to prevent zippering
            currentSurroundIntensity += (surroundIntensity - currentSurroundIntensity) * SMOOTHING_FACTOR
            currentCenterFocus += (centerFocus - currentCenterFocus) * SMOOTHING_FACTOR
            currentBassLevel += (bassLevel - currentBassLevel) * SMOOTHING_FACTOR

            val effectiveCenterGain = CENTER_GAIN * currentCenterFocus * 2f
            val effectiveDivergence = CENTER_DIVERGENCE * currentCenterFocus * 2f
            val effectiveLfeGain = LFE_GAIN * currentBassLevel * (1f / DEFAULT_BASS_LEVEL)

            val left = inputBuffer.short.toFloat() * INV_SHORT_MAX
            val right = inputBuffer.short.toFloat() * INV_SHORT_MAX

            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f
            val scaledSide = side * currentSurroundIntensity
            val rearMix = scaledSide + mid * REAR_MID_BLEED * currentSurroundIntensity

            val centerFiltered = cLpf.process(cHpf.process(mid)) * effectiveCenterGain
            val lfeFiltered = lfe.process(mid) * effectiveLfeGain
            val flDefault = left - centerFiltered * effectiveDivergence
            val frDefault = right - centerFiltered * effectiveDivergence

            val blRaw = sLpfs[0].process(sHpfs[0].process(rearMix))
            val blDefault = aPs2[0].process(aPs1[0].process(dls[0].process(blRaw))) * BACK_GAIN
            val brRaw = sLpfs[1].process(sHpfs[1].process(-rearMix))
            val brDefault = aPs2[1].process(aPs1[1].process(dls[1].process(brRaw))) * BACK_GAIN
            val slRaw = sLpfs[2].process(sHpfs[2].process(scaledSide))
            val slDefault = aPs2[2].process(aPs1[2].process(dls[2].process(slRaw))) * SIDE_SURROUND_GAIN
            val srRaw = sLpfs[3].process(sHpfs[3].process(-scaledSide))
            val srDefault = aPs2[3].process(aPs1[3].process(dls[3].process(srRaw))) * SIDE_SURROUND_GAIN

            val defaults = floatArrayOf(
                flDefault, frDefault, centerFiltered, lfeFiltered,
                blDefault, brDefault, slDefault, srDefault
            )

            val fl  = routeChannel(types[CH_FL],  defaults[CH_FL],  left, right, mid, side, lfeFiltered) * distGains[CH_FL]
            val fr  = routeChannel(types[CH_FR],  defaults[CH_FR],  left, right, mid, side, lfeFiltered) * distGains[CH_FR]
            val fc  = routeChannel(types[CH_FC],  defaults[CH_FC],  left, right, mid, side, lfeFiltered) * distGains[CH_FC]
            val lfeOutput = routeChannel(types[CH_LFE], defaults[CH_LFE], left, right, mid, side, lfeFiltered) * distGains[CH_LFE]
            val bl  = routeChannel(types[CH_BL],  defaults[CH_BL],  left, right, mid, side, lfeFiltered) * distGains[CH_BL]
            val br  = routeChannel(types[CH_BR],  defaults[CH_BR],  left, right, mid, side, lfeFiltered) * distGains[CH_BR]
            val sl  = routeChannel(types[CH_SL],  defaults[CH_SL],  left, right, mid, side, lfeFiltered) * distGains[CH_SL]
            val sr  = routeChannel(types[CH_SR],  defaults[CH_SR],  left, right, mid, side, lfeFiltered) * distGains[CH_SR]

            buffer.putShort(clampToShort(fl))
            buffer.putShort(clampToShort(fr))
            buffer.putShort(clampToShort(fc))
            buffer.putShort(clampToShort(lfeOutput))

            if (mode == UpmixMode.SURROUND_5_1) {
                buffer.putShort(clampToShort((bl + sl) * EQUAL_POWER_FOLD))
                buffer.putShort(clampToShort((br + sr) * EQUAL_POWER_FOLD))
            } else {
                buffer.putShort(clampToShort(bl))
                buffer.putShort(clampToShort(br))
                buffer.putShort(clampToShort(sl))
                buffer.putShort(clampToShort(sr))
            }
        }

        buffer.flip()
        outputBuffer = buffer
    }

    private fun updateCrossoversIfNeeded() {
        if (appliedLfeCutoff != lfeCutoff) {
            appliedLfeCutoff = lfeCutoff
            lfeLpf?.updateLowPass(sampleRate, appliedLfeCutoff)
        }
        if (appliedCenterHpfCutoff != centerHpfCutoff) {
            appliedCenterHpfCutoff = centerHpfCutoff
            centerHpf?.updateHighPass(sampleRate, appliedCenterHpfCutoff)
        }
        if (appliedCenterLpfCutoff != centerLpfCutoff) {
            appliedCenterLpfCutoff = centerLpfCutoff
            centerLpf?.updateLowPass(sampleRate, appliedCenterLpfCutoff)
        }
        if (appliedSurroundHpfCutoff != surroundHpfCutoff) {
            appliedSurroundHpfCutoff = surroundHpfCutoff
            surroundHpfs?.forEach { it.updateHighPass(sampleRate, appliedSurroundHpfCutoff) }
        }
        if (appliedSurroundLpfCutoff != surroundLpfCutoff) {
            appliedSurroundLpfCutoff = surroundLpfCutoff
            surroundLpfs?.forEach { it.updateLowPass(sampleRate, appliedSurroundLpfCutoff) }
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        buffer.clear()
        resetDsp()
    }

    override fun reset() {
        flush()
        buffer = EMPTY_BUFFER
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        sampleRate = 0
        clearDsp()
    }

    // ── DSP initialization ──────────────────────────────────────────────────

    private fun initializeDsp() {
        currentSurroundIntensity = surroundIntensity
        currentCenterFocus = centerFocus
        currentBassLevel = bassLevel

        appliedLfeCutoff = lfeCutoff
        appliedCenterHpfCutoff = centerHpfCutoff
        appliedCenterLpfCutoff = centerLpfCutoff
        appliedSurroundHpfCutoff = surroundHpfCutoff
        appliedSurroundLpfCutoff = surroundLpfCutoff

        centerHpf = MonoBiquad.highPass(sampleRate, appliedCenterHpfCutoff)
        centerLpf = MonoBiquad.lowPass(sampleRate, appliedCenterLpfCutoff)

        lfeLpf = MonoBiquad.lowPass(sampleRate, appliedLfeCutoff)

        surroundHpfs = arrayOf(
            MonoBiquad.highPass(sampleRate, appliedSurroundHpfCutoff),
            MonoBiquad.highPass(sampleRate, appliedSurroundHpfCutoff),
            MonoBiquad.highPass(sampleRate, appliedSurroundHpfCutoff),
            MonoBiquad.highPass(sampleRate, appliedSurroundHpfCutoff),
        )

        surroundLpfs = arrayOf(
            MonoBiquad.lowPass(sampleRate, appliedSurroundLpfCutoff),
            MonoBiquad.lowPass(sampleRate, appliedSurroundLpfCutoff),
            MonoBiquad.lowPass(sampleRate, appliedSurroundLpfCutoff),
            MonoBiquad.lowPass(sampleRate, appliedSurroundLpfCutoff),
        )

        allPassesStage1 = arrayOf(
            MonoBiquad.allPass(sampleRate, ALLPASS_1_FREQ_BL),
            MonoBiquad.allPass(sampleRate, ALLPASS_1_FREQ_BR),
            MonoBiquad.allPass(sampleRate, ALLPASS_1_FREQ_SL),
            MonoBiquad.allPass(sampleRate, ALLPASS_1_FREQ_SR),
        )
        allPassesStage2 = arrayOf(
            MonoBiquad.allPass(sampleRate, ALLPASS_2_FREQ_BL),
            MonoBiquad.allPass(sampleRate, ALLPASS_2_FREQ_BR),
            MonoBiquad.allPass(sampleRate, ALLPASS_2_FREQ_SL),
            MonoBiquad.allPass(sampleRate, ALLPASS_2_FREQ_SR),
        )

        fun msToSamples(ms: Float) = (ms * sampleRate / 1000f).toInt().coerceAtLeast(1)
        delayLines = arrayOf(
            DelayLine(msToSamples(DELAY_BL_MS)),
            DelayLine(msToSamples(DELAY_BR_MS)),
            DelayLine(msToSamples(DELAY_SL_MS)),
            DelayLine(msToSamples(DELAY_SR_MS)),
        )
    }

    private fun resetDsp() {
        centerHpf?.reset()
        centerLpf?.reset()
        lfeLpf?.reset()
        surroundHpfs?.forEach { it.reset() }
        surroundLpfs?.forEach { it.reset() }
        allPassesStage1?.forEach { it.reset() }
        allPassesStage2?.forEach { it.reset() }
        delayLines?.forEach { it.reset() }
    }

    private fun clearDsp() {
        centerHpf = null
        centerLpf = null
        lfeLpf = null
        surroundHpfs = null
        surroundLpfs = null
        allPassesStage1 = null
        allPassesStage2 = null
        delayLines = null
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun routeChannel(
        type: String,
        defaultValue: Float,
        left: Float,
        right: Float,
        mid: Float,
        side: Float,
        lfeFiltered: Float,
    ): Float = when (type) {
        CHANNEL_TYPE_FULL_MIX -> (left + right) * 0.5f
        CHANNEL_TYPE_AMBIENT -> side
        CHANNEL_TYPE_VOCAL -> mid
        CHANNEL_TYPE_BASS -> lfeFiltered
        CHANNEL_TYPE_SILENT -> 0f
        else -> defaultValue
    }

    private fun clampToShort(value: Float): Short =
        (value * SHORT_MAX).coerceIn(-SHORT_MAX, SHORT_MAX - 1f).toInt().toShort()

    // ── Internal DSP primitives ─────────────────────────────────────────────

    private class MonoBiquad(
        var b0: Float,
        var b1: Float,
        var b2: Float,
        var a1: Float,
        var a2: Float,
    ) {
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun process(input: Float): Float {
            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = output
            return output
        }

        fun reset() {
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }

        fun updateLowPass(sampleRate: Int, freq: Float, q: Float = BUTTERWORTH_Q) {
            val omega = 2f * F_PI * freq / sampleRate
            val sinO = sin(omega)
            val cosO = cos(omega)
            val alpha = sinO / (2f * q)
            val a0 = 1f + alpha
            b0 = (1f - cosO) / 2f / a0
            b1 = (1f - cosO) / a0
            b2 = (1f - cosO) / 2f / a0
            a1 = -2f * cosO / a0
            a2 = (1f - alpha) / a0
        }

        fun updateHighPass(sampleRate: Int, freq: Float, q: Float = BUTTERWORTH_Q) {
            val omega = 2f * F_PI * freq / sampleRate
            val sinO = sin(omega)
            val cosO = cos(omega)
            val alpha = sinO / (2f * q)
            val a0 = 1f + alpha
            b0 = (1f + cosO) / 2f / a0
            b1 = -(1f + cosO) / a0
            b2 = (1f + cosO) / 2f / a0
            a1 = -2f * cosO / a0
            a2 = (1f - alpha) / a0
        }

        companion object {
            val F_PI = PI.toFloat()

            fun lowPass(sampleRate: Int, freq: Float, q: Float = BUTTERWORTH_Q): MonoBiquad {
                val bq = MonoBiquad(0f, 0f, 0f, 0f, 0f)
                bq.updateLowPass(sampleRate, freq, q)
                return bq
            }

            fun highPass(sampleRate: Int, freq: Float, q: Float = BUTTERWORTH_Q): MonoBiquad {
                val bq = MonoBiquad(0f, 0f, 0f, 0f, 0f)
                bq.updateHighPass(sampleRate, freq, q)
                return bq
            }

            fun allPass(sampleRate: Int, freq: Float, q: Float = BUTTERWORTH_Q): MonoBiquad {
                val omega = 2f * F_PI * freq / sampleRate
                val sinO = sin(omega)
                val cosO = cos(omega)
                val alpha = sinO / (2f * q)
                val a0 = 1f + alpha
                return MonoBiquad(
                    b0 = (1f - alpha) / a0,
                    b1 = -2f * cosO / a0,
                    b2 = (1f + alpha) / a0,
                    a1 = -2f * cosO / a0,
                    a2 = (1f - alpha) / a0,
                )
            }
        }
    }

    private class DelayLine(size: Int) {
        private val ring = FloatArray(size)
        private var writePos = 0

        fun process(input: Float): Float {
            val output = ring[writePos]
            ring[writePos] = input
            writePos = (writePos + 1) % ring.size
            return output
        }

        fun reset() {
            ring.fill(0f)
            writePos = 0
        }
    }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        const val DEFAULT_SURROUND_INTENSITY = 0.7f
        const val DEFAULT_CENTER_FOCUS = 0.5f
        const val DEFAULT_BASS_LEVEL = 0.5f
        const val DEFAULT_CHANNEL_DISTANCE = 2.0f
        private const val REFERENCE_DISTANCE = 2.0f

        const val CHANNEL_TYPE_DEFAULT = "default"
        const val CHANNEL_TYPE_FULL_MIX = "full_mix"
        const val CHANNEL_TYPE_AMBIENT = "ambient"
        const val CHANNEL_TYPE_VOCAL = "vocal"
        const val CHANNEL_TYPE_BASS = "bass"
        const val CHANNEL_TYPE_SILENT = "silent"

        const val CH_FL = 0
        const val CH_FR = 1
        const val CH_FC = 2
        const val CH_LFE = 3
        const val CH_BL = 4
        const val CH_BR = 5
        const val CH_SL = 6
        const val CH_SR = 7

        private const val INPUT_CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val SHORT_MAX = 32768f
        private const val INV_SHORT_MAX = 1f / SHORT_MAX

        private const val EQUAL_POWER_FOLD = 0.7071f
        private const val BUTTERWORTH_Q = 0.707f

        // Anti-zipper smoothing factor (lower = slower response, smoother transition)
        private const val SMOOTHING_FACTOR = 0.005f

        private const val CENTER_GAIN = 0.707f
        private const val LFE_GAIN = 0.316f
        private const val BACK_GAIN = 0.596f
        private const val SIDE_SURROUND_GAIN = 0.707f
        private const val CENTER_DIVERGENCE = 0.35f
        private const val REAR_MID_BLEED = 0.25f

        // Default Crossover / filter frequencies
        const val DEFAULT_LFE_CUTOFF = 120f
        const val DEFAULT_SURROUND_HPF_CUTOFF = 150f
        const val DEFAULT_SURROUND_LPF_CUTOFF = 7000f
        const val DEFAULT_CENTER_HPF_CUTOFF = 100f
        const val DEFAULT_CENTER_LPF_CUTOFF = 7000f

        // Cascaded all-pass frequencies
        private const val ALLPASS_1_FREQ_BL = 600f
        private const val ALLPASS_1_FREQ_BR = 900f
        private const val ALLPASS_1_FREQ_SL = 400f
        private const val ALLPASS_1_FREQ_SR = 1100f
        private const val ALLPASS_2_FREQ_BL = 2200f
        private const val ALLPASS_2_FREQ_BR = 3100f
        private const val ALLPASS_2_FREQ_SL = 1800f
        private const val ALLPASS_2_FREQ_SR = 4500f

        private const val DELAY_BL_MS = 28f
        private const val DELAY_BR_MS = 32f
        private const val DELAY_SL_MS = 12f
        private const val DELAY_SR_MS = 16f
    }
}
