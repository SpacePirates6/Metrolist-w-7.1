package com.metrolist.music.ui.screens.equalizer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.AutoEqDataKey
import com.metrolist.music.constants.AutoEqEnabledKey
import com.metrolist.music.constants.AutoEqNameKey
import com.metrolist.music.constants.Upmix71DistanceBLKey
import com.metrolist.music.constants.Upmix71DistanceBRKey
import com.metrolist.music.constants.Upmix71DistanceFCKey
import com.metrolist.music.constants.Upmix71DistanceFLKey
import com.metrolist.music.constants.Upmix71DistanceFRKey
import com.metrolist.music.constants.Upmix71DistanceLFEKey
import com.metrolist.music.constants.Upmix71DistanceSLKey
import com.metrolist.music.constants.Upmix71DistanceSRKey
import com.metrolist.music.constants.Upmix71TypeBLKey
import com.metrolist.music.constants.Upmix71TypeBRKey
import com.metrolist.music.constants.Upmix71TypeFCKey
import com.metrolist.music.constants.Upmix71TypeFLKey
import com.metrolist.music.constants.Upmix71TypeFRKey
import com.metrolist.music.constants.Upmix71TypeLFEKey
import com.metrolist.music.constants.Upmix71TypeSLKey
import com.metrolist.music.constants.Upmix71TypeSRKey
import com.metrolist.music.constants.UpmixBassLevelKey
import com.metrolist.music.constants.UpmixCenterHpfKey
import com.metrolist.music.constants.UpmixCenterLpfKey
import com.metrolist.music.constants.UpmixEnabledKey
import com.metrolist.music.constants.UpmixIntensityKey
import com.metrolist.music.constants.UpmixLfeCutoffKey
import com.metrolist.music.constants.UpmixModeKey
import com.metrolist.music.constants.UpmixSurroundHpfKey
import com.metrolist.music.constants.UpmixSurroundLpfKey
import com.metrolist.music.eq.EqualizerService
import com.metrolist.music.eq.data.AutoEqMatch
import com.metrolist.music.eq.data.AutoEqRepository
import com.metrolist.music.eq.data.EQProfileRepository
import com.metrolist.music.eq.data.ParametricEQ
import com.metrolist.music.eq.data.ParametricEQParser
import com.metrolist.music.playback.audio.UpmixAudioProcessor
import com.metrolist.music.playback.audio.UpmixService
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

/**
 * ViewModel for EQ Screen
 * Manages EQ profiles and applies them to the EqualizerService.
 * Also exposes upmix settings backed by DataStore.
 */
@HiltViewModel
class EQViewModel @Inject constructor(
    private val eqProfileRepository: EQProfileRepository,
    private val equalizerService: EqualizerService,
    private val upmixService: UpmixService,
    private val autoEqRepository: AutoEqRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(EQState())
    val state: StateFlow<EQState> = _state.asStateFlow()

    init {
        loadProfiles()
        loadUpmixSettings()
        loadAutoEqSettings()
        viewModelScope.launch {
            autoEqRepository.initializeIndex()
        }
    }

    /**
     * Load all saved EQ profiles (sorted: AutoEQ first, then custom)
     */
    private fun loadProfiles() {
        // Observe profiles changes
        viewModelScope.launch {
            eqProfileRepository.profiles.collect { _ ->
                val sortedProfiles = eqProfileRepository.getSortedProfiles()
                _state.update {
                    it.copy(profiles = sortedProfiles)
                }
            }
        }

        // Observe active profile changes separately
        viewModelScope.launch {
            eqProfileRepository.activeProfile.collect { activeProfile ->
                _state.update {
                    it.copy(activeProfileId = activeProfile?.id)
                }
            }
        }
    }

    /**
     * Select and apply an EQ profile
     * Pass null to disable EQ
     */
    fun selectProfile(profileId: String?) {
        viewModelScope.launch {
            if (profileId == null) {
                // Disable EQ
                equalizerService.disable()
                eqProfileRepository.setActiveProfile(null)
            } else {
                // Apply the selected profile
                val profile = _state.value.profiles.find { it.id == profileId }
                if (profile != null) {
                    val result = equalizerService.applyProfile(profile)
                    result.onSuccess {
                        eqProfileRepository.setActiveProfile(profileId)
                    }.onFailure { e ->
                        _state.update { it.copy(error = e.message ?: "Unknown error") }
                    }
                }
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Update the AutoEq search query and run local search as the user types.
     */
    fun onAutoEqQueryChanged(query: String) {
        val results = autoEqRepository.search(query)
        _state.update {
            it.copy(autoEqSearchQuery = query, autoEqSearchResults = results)
        }
    }

    /**
     * Observe AutoEq keys in DataStore and push them to EqualizerService.
     */
    private fun loadAutoEqSettings() {
        viewModelScope.launch {
            context.dataStore.data.collect { prefs ->
                val autoEqEnabled = prefs[AutoEqEnabledKey] ?: false
                val autoEqName = prefs[AutoEqNameKey]
                val autoEqData = prefs[AutoEqDataKey]

                val autoEqProfile = autoEqData?.let { ParametricEQParser.parseText(it) }
                equalizerService.setAutoEqEnabled(autoEqEnabled)
                equalizerService.setAutoEqProfile(autoEqProfile)

                _state.update {
                    it.copy(
                        autoEqEnabled = autoEqEnabled,
                        autoEqName = autoEqName,
                    )
                }
            }
        }
    }

    /**
     * Toggle the hardware AutoEq layer on/off.
     */
    fun toggleAutoEq(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[AutoEqEnabledKey] = enabled }
        }
    }

    /**
     * Download the selected AutoEq profile and save to DataStore (does not create a custom profile).
     */
    fun downloadAndApplyAutoEq(match: AutoEqMatch) {
        viewModelScope.launch {
            _state.update { it.copy(autoEqSearching = true, error = null) }
            autoEqRepository.downloadProfile(match.downloadUrl)
                .onSuccess { content ->
                    try {
                        val parametricEQ = ParametricEQParser.parseText(content)
                        val validationErrors = ParametricEQParser.validate(parametricEQ)
                        if (validationErrors.isNotEmpty()) {
                            _state.update {
                                it.copy(
                                    autoEqSearching = false,
                                    error = "Invalid EQ profile: ${validationErrors.first()}",
                                )
                            }
                            return@launch
                        }

                        context.dataStore.edit {
                            it[AutoEqNameKey] = match.name
                            it[AutoEqDataKey] = content
                            it[AutoEqEnabledKey] = true
                        }
                        _state.update {
                            it.copy(
                                autoEqSearching = false,
                                autoEqSearchQuery = "",
                                autoEqSearchResults = emptyList(),
                            )
                        }
                    } catch (e: Exception) {
                        _state.update {
                            it.copy(
                                autoEqSearching = false,
                                error = "Failed to parse EQ profile",
                            )
                        }
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            autoEqSearching = false,
                            error = "Download failed",
                        )
                    }
                }
        }
    }

    /**
     * Delete an EQ profile
     */
    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            eqProfileRepository.deleteProfile(profileId)
        }
    }

    /**
     * Reset all EQ and upmix settings to factory defaults.
     */
    fun resetAll() {
        viewModelScope.launch {
            equalizerService.disable()
            eqProfileRepository.setActiveProfile(null)

            context.dataStore.edit { prefs ->
                prefs.remove(AutoEqEnabledKey)
                prefs.remove(AutoEqNameKey)
                prefs.remove(AutoEqDataKey)

                prefs.remove(UpmixEnabledKey)
                prefs.remove(UpmixIntensityKey)
                prefs.remove(UpmixModeKey)
                prefs.remove(UpmixBassLevelKey)
                prefs.remove(UpmixLfeCutoffKey)
                prefs.remove(UpmixCenterHpfKey)
                prefs.remove(UpmixCenterLpfKey)
                prefs.remove(UpmixSurroundHpfKey)
                prefs.remove(UpmixSurroundLpfKey)

                prefs.remove(Upmix71DistanceFLKey)
                prefs.remove(Upmix71DistanceFRKey)
                prefs.remove(Upmix71DistanceFCKey)
                prefs.remove(Upmix71DistanceLFEKey)
                prefs.remove(Upmix71DistanceBLKey)
                prefs.remove(Upmix71DistanceBRKey)
                prefs.remove(Upmix71DistanceSLKey)
                prefs.remove(Upmix71DistanceSRKey)
                prefs.remove(Upmix71TypeFLKey)
                prefs.remove(Upmix71TypeFRKey)
                prefs.remove(Upmix71TypeFCKey)
                prefs.remove(Upmix71TypeLFEKey)
                prefs.remove(Upmix71TypeBLKey)
                prefs.remove(Upmix71TypeBRKey)
                prefs.remove(Upmix71TypeSLKey)
                prefs.remove(Upmix71TypeSRKey)
            }
        }
    }

    /**
     * Observe upmix keys in DataStore and push them into [_state] and [upmixService].
     */
    private fun loadUpmixSettings() {
        viewModelScope.launch {
            context.dataStore.data.collect { prefs ->
                val enabled = prefs[UpmixEnabledKey] ?: false
                val intensity = prefs[UpmixIntensityKey] ?: UpmixAudioProcessor.DEFAULT_SURROUND_INTENSITY
                val mode = prefs[UpmixModeKey]?.let {
                    runCatching { UpmixAudioProcessor.UpmixMode.valueOf(it) }.getOrNull()
                } ?: UpmixAudioProcessor.UpmixMode.SURROUND_7_1

                val bassLevel = prefs[UpmixBassLevelKey] ?: UpmixAudioProcessor.DEFAULT_BASS_LEVEL
                val lfeCutoff = prefs[UpmixLfeCutoffKey] ?: UpmixAudioProcessor.DEFAULT_LFE_CUTOFF
                val centerHpf = prefs[UpmixCenterHpfKey] ?: UpmixAudioProcessor.DEFAULT_CENTER_HPF_CUTOFF
                val centerLpf = prefs[UpmixCenterLpfKey] ?: UpmixAudioProcessor.DEFAULT_CENTER_LPF_CUTOFF
                val surroundHpf = prefs[UpmixSurroundHpfKey] ?: UpmixAudioProcessor.DEFAULT_SURROUND_HPF_CUTOFF
                val surroundLpf = prefs[UpmixSurroundLpfKey] ?: UpmixAudioProcessor.DEFAULT_SURROUND_LPF_CUTOFF

                val distances = floatArrayOf(
                    prefs[Upmix71DistanceFLKey] ?: UpmixAudioProcessor.DEFAULT_CHANNEL_DISTANCE,
                    prefs[Upmix71DistanceFRKey] ?: UpmixAudioProcessor.DEFAULT_CHANNEL_DISTANCE,
                    prefs[Upmix71DistanceFCKey] ?: UpmixAudioProcessor.DEFAULT_CHANNEL_DISTANCE,
                    prefs[Upmix71DistanceLFEKey] ?: UpmixAudioProcessor.DEFAULT_CHANNEL_DISTANCE,
                    prefs[Upmix71DistanceBLKey] ?: UpmixAudioProcessor.DEFAULT_CHANNEL_DISTANCE,
                    prefs[Upmix71DistanceBRKey] ?: UpmixAudioProcessor.DEFAULT_CHANNEL_DISTANCE,
                    prefs[Upmix71DistanceSLKey] ?: UpmixAudioProcessor.DEFAULT_CHANNEL_DISTANCE,
                    prefs[Upmix71DistanceSRKey] ?: UpmixAudioProcessor.DEFAULT_CHANNEL_DISTANCE,
                )
                val types = arrayOf(
                    prefs[Upmix71TypeFLKey] ?: UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT,
                    prefs[Upmix71TypeFRKey] ?: UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT,
                    prefs[Upmix71TypeFCKey] ?: UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT,
                    prefs[Upmix71TypeLFEKey] ?: UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT,
                    prefs[Upmix71TypeBLKey] ?: UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT,
                    prefs[Upmix71TypeBRKey] ?: UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT,
                    prefs[Upmix71TypeSLKey] ?: UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT,
                    prefs[Upmix71TypeSRKey] ?: UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT,
                )

                upmixService.setEnabled(enabled)
                upmixService.setSurroundIntensity(intensity)
                upmixService.setOutputMode(mode)
                upmixService.setBassLevel(bassLevel)
                upmixService.setLfeCutoff(lfeCutoff)
                upmixService.setCenterCutoffs(centerHpf, centerLpf)
                upmixService.setSurroundCutoffs(surroundHpf, surroundLpf)
                upmixService.setChannelDistances(distances)
                upmixService.setChannelTypes(types)

                _state.update {
                    it.copy(
                        upmixEnabled = enabled,
                        upmixIntensity = intensity,
                        upmixMode = mode,
                        upmixBassLevel = bassLevel,
                        upmixLfeCutoff = lfeCutoff,
                        upmixCenterHpf = centerHpf,
                        upmixCenterLpf = centerLpf,
                        upmixSurroundHpf = surroundHpf,
                        upmixSurroundLpf = surroundLpf,
                    )
                }
            }
        }
    }

    /**
     * Toggle 7.1 upmix on/off and persist the preference.
     */
    fun setUpmixEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[UpmixEnabledKey] = enabled }
        }
    }

    /**
     * Adjust surround intensity (0.0–1.0) and persist the preference.
     */
    fun setUpmixIntensity(intensity: Float) {
        viewModelScope.launch {
            context.dataStore.edit { it[UpmixIntensityKey] = intensity.coerceIn(0f, 1f) }
        }
    }

    /**
     * Select surround output format (5.1 or 7.1) and persist the preference.
     */
    fun setUpmixMode(mode: UpmixAudioProcessor.UpmixMode) {
        viewModelScope.launch {
            context.dataStore.edit { it[UpmixModeKey] = mode.name }
        }
    }

    fun setUpmixBassLevel(level: Float) {
        viewModelScope.launch { context.dataStore.edit { it[UpmixBassLevelKey] = level.coerceIn(0f, 1f) } }
    }

    fun setUpmixLfeCutoff(freq: Float) {
        viewModelScope.launch { context.dataStore.edit { it[UpmixLfeCutoffKey] = freq.coerceIn(40f, 250f) } }
    }

    fun setUpmixCenterCutoffs(hpf: Float, lpf: Float) {
        viewModelScope.launch {
            context.dataStore.edit {
                it[UpmixCenterHpfKey] = hpf.coerceIn(40f, 1000f)
                it[UpmixCenterLpfKey] = lpf.coerceIn(1000f, 20000f)
            }
        }
    }

    fun setUpmixSurroundCutoffs(hpf: Float, lpf: Float) {
        viewModelScope.launch {
            context.dataStore.edit {
                it[UpmixSurroundHpfKey] = hpf.coerceIn(40f, 1000f)
                it[UpmixSurroundLpfKey] = lpf.coerceIn(1000f, 20000f)
            }
        }
    }

    /**
     * Import a custom EQ profile from a file
     */
    fun importCustomProfile(
        fileName: String,
        inputStream: InputStream,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                // Read the file content
                val content = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()

                // Parse the ParametricEQ format
                val parametricEQ = ParametricEQParser.parseText(content)

                // Validate the parsed EQ
                val validationErrors = ParametricEQParser.validate(parametricEQ)
                if (validationErrors.isNotEmpty()) {
                    onError(Exception("Invalid EQ file: ${validationErrors.first()}"))
                    return@launch
                }

                // Extract profile name from file name (remove .txt extension)
                val profileName = fileName.removeSuffix(".txt")

                // Import the profile
                eqProfileRepository.importCustomProfile(profileName, parametricEQ)

                _state.update { it.copy(importStatus = "Successfully imported $profileName") }
                onSuccess()
            } catch (e: Exception) {
                onError(Exception("Failed to import EQ profile: ${e.message}"))
            }
        }
    }
}