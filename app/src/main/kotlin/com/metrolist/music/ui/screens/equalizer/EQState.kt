package com.metrolist.music.ui.screens.equalizer

import com.metrolist.music.eq.data.AutoEqMatch
import com.metrolist.music.eq.data.SavedEQProfile
import com.metrolist.music.playback.audio.UpmixAudioProcessor

/**
 * UI State for EQ Screen
 */
data class EQState(
    val profiles: List<SavedEQProfile> = emptyList(),
    val activeProfileId: String? = null,
    val importStatus: String? = null,
    val error: String? = null,
    val autoEqEnabled: Boolean = false,
    val autoEqName: String? = null,
    val autoEqSearchQuery: String = "",
    val autoEqSearching: Boolean = false,
    val autoEqSearchResults: List<AutoEqMatch> = emptyList(),
    val upmixEnabled: Boolean = false,
    val upmixIntensity: Float = UpmixAudioProcessor.DEFAULT_SURROUND_INTENSITY,
    val upmixMode: UpmixAudioProcessor.UpmixMode = UpmixAudioProcessor.UpmixMode.SURROUND_7_1,
    val upmixCenterFocus: Float = UpmixAudioProcessor.DEFAULT_CENTER_FOCUS,
    val upmixBassLevel: Float = UpmixAudioProcessor.DEFAULT_BASS_LEVEL,
    val upmixLfeCutoff: Float = UpmixAudioProcessor.DEFAULT_LFE_CUTOFF,
    val upmixCenterHpf: Float = UpmixAudioProcessor.DEFAULT_CENTER_HPF_CUTOFF,
    val upmixCenterLpf: Float = UpmixAudioProcessor.DEFAULT_CENTER_LPF_CUTOFF,
    val upmixSurroundHpf: Float = UpmixAudioProcessor.DEFAULT_SURROUND_HPF_CUTOFF,
    val upmixSurroundLpf: Float = UpmixAudioProcessor.DEFAULT_SURROUND_LPF_CUTOFF,
)