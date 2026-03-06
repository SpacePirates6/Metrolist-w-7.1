/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpmixService @Inject constructor() {

    @OptIn(UnstableApi::class)
    private val processors = CopyOnWriteArrayList<UpmixAudioProcessor>()

    private var pendingEnabled: Boolean = false
    private var pendingSurroundIntensity: Float = UpmixAudioProcessor.DEFAULT_SURROUND_INTENSITY
    private var pendingCenterFocus: Float = UpmixAudioProcessor.DEFAULT_CENTER_FOCUS
    private var pendingBassLevel: Float = UpmixAudioProcessor.DEFAULT_BASS_LEVEL
    private var pendingOutputMode: UpmixAudioProcessor.UpmixMode = UpmixAudioProcessor.UpmixMode.SURROUND_7_1

    // Pending Crossovers
    private var pendingLfeCutoff: Float = UpmixAudioProcessor.DEFAULT_LFE_CUTOFF
    private var pendingCenterHpfCutoff: Float = UpmixAudioProcessor.DEFAULT_CENTER_HPF_CUTOFF
    private var pendingCenterLpfCutoff: Float = UpmixAudioProcessor.DEFAULT_CENTER_LPF_CUTOFF
    private var pendingSurroundHpfCutoff: Float = UpmixAudioProcessor.DEFAULT_SURROUND_HPF_CUTOFF
    private var pendingSurroundLpfCutoff: Float = UpmixAudioProcessor.DEFAULT_SURROUND_LPF_CUTOFF

    private var pendingChannelDistances: FloatArray = FloatArray(8) { UpmixAudioProcessor.DEFAULT_CHANNEL_DISTANCE }
    private var pendingChannelTypes: Array<String> = Array(8) { UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT }

    companion object {
        private const val TAG = "UpmixService"
    }

    @OptIn(UnstableApi::class)
    fun addProcessor(processor: UpmixAudioProcessor) {
        processors.add(processor)
        processor.enabled = pendingEnabled
        processor.surroundIntensity = pendingSurroundIntensity
        processor.centerFocus = pendingCenterFocus
        processor.bassLevel = pendingBassLevel
        processor.outputMode = pendingOutputMode

        processor.lfeCutoff = pendingLfeCutoff
        processor.centerHpfCutoff = pendingCenterHpfCutoff
        processor.centerLpfCutoff = pendingCenterLpfCutoff
        processor.surroundHpfCutoff = pendingSurroundHpfCutoff
        processor.surroundLpfCutoff = pendingSurroundLpfCutoff

        processor.channelDistances = pendingChannelDistances.copyOf()
        processor.channelTypes = pendingChannelTypes.copyOf()

        Timber.tag(TAG).d("Processor added. enabled=$pendingEnabled")
    }

    @OptIn(UnstableApi::class)
    fun removeProcessor(processor: UpmixAudioProcessor) {
        processors.remove(processor)
    }

    fun release() {
        processors.clear()
    }

    @OptIn(UnstableApi::class)
    fun setEnabled(enabled: Boolean) {
        pendingEnabled = enabled
        processors.forEach { it.enabled = enabled }
    }

    @OptIn(UnstableApi::class)
    fun setSurroundIntensity(intensity: Float) {
        pendingSurroundIntensity = intensity
        processors.forEach { it.surroundIntensity = intensity }
    }

    @OptIn(UnstableApi::class)
    fun setCenterFocus(focus: Float) {
        pendingCenterFocus = focus
        processors.forEach { it.centerFocus = focus }
    }

    @OptIn(UnstableApi::class)
    fun setBassLevel(level: Float) {
        pendingBassLevel = level
        processors.forEach { it.bassLevel = level }
    }

    @OptIn(UnstableApi::class)
    fun setOutputMode(mode: UpmixAudioProcessor.UpmixMode) {
        pendingOutputMode = mode
        processors.forEach { it.outputMode = mode }
    }

    // Crossover Setters
    @OptIn(UnstableApi::class)
    fun setLfeCutoff(freq: Float) {
        pendingLfeCutoff = freq
        processors.forEach { it.lfeCutoff = freq }
    }

    @OptIn(UnstableApi::class)
    fun setCenterCutoffs(hpf: Float, lpf: Float) {
        pendingCenterHpfCutoff = hpf
        pendingCenterLpfCutoff = lpf
        processors.forEach {
            it.centerHpfCutoff = hpf
            it.centerLpfCutoff = lpf
        }
    }

    @OptIn(UnstableApi::class)
    fun setSurroundCutoffs(hpf: Float, lpf: Float) {
        pendingSurroundHpfCutoff = hpf
        pendingSurroundLpfCutoff = lpf
        processors.forEach {
            it.surroundHpfCutoff = hpf
            it.surroundLpfCutoff = lpf
        }
    }

    @OptIn(UnstableApi::class)
    fun setChannelDistances(distances: FloatArray) {
        if (distances.size >= 8) {
            pendingChannelDistances = distances.copyOf()
            processors.forEach { it.channelDistances = distances.copyOf() }
        }
    }

    @OptIn(UnstableApi::class)
    fun setChannelTypes(types: Array<String>) {
        if (types.size >= 8) {
            pendingChannelTypes = types.copyOf()
            processors.forEach { it.channelTypes = types.copyOf() }
        }
    }

    fun isEnabled(): Boolean = pendingEnabled

    fun getSurroundIntensity(): Float = pendingSurroundIntensity

    fun getOutputMode(): UpmixAudioProcessor.UpmixMode = pendingOutputMode
}
