package com.metrolist.music.ui.screens.equalizer

import android.annotation.SuppressLint
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.session.PlaybackState
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlin.ranges.ClosedFloatingPointRange
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.datastore.preferences.core.Preferences
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
import com.metrolist.music.eq.data.AutoEqMatch
import com.metrolist.music.eq.data.SavedEQProfile
import com.metrolist.music.playback.audio.UpmixAudioProcessor
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.utils.rememberPreference
import timber.log.Timber

/**
 * EQ Screen - Manage and select EQ profiles
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun EqScreen(
    viewModel: EQViewModel = hiltViewModel(),
    playbackState: PlaybackState? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current

    var showError by remember { mutableStateOf<String?>(null) }

    // Activity result launcher for system equalizer
    val activityResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    // File picker for custom EQ import
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver

                // Extract file name from URI
                var fileName = "custom_eq.txt"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (displayNameIndex >= 0) {
                            val name = cursor.getString(displayNameIndex)
                            if (!name.isNullOrBlank()) {
                                fileName = name
                            }
                        }
                    }
                }

                val inputStream = contentResolver.openInputStream(uri)

                if (inputStream != null) {
                    viewModel.importCustomProfile(
                        fileName = fileName,
                        inputStream = inputStream,
                        onSuccess = {
                            Timber.d("Custom EQ profile imported successfully: $fileName")
                        },
                        onError = { error ->
                            Timber.d("Error: Unable to import Custom EQ profile: $fileName")
                            showError = context.getString(R.string.import_error_title) + ": " + error.message
                        })
                } else {
                    showError = context.getString(R.string.error_file_read)
                }
            } catch (e: Exception) {
                showError = context.getString(R.string.error_file_open, e.message)
            }
        }
    }

    EqScreenContent(
        viewModel = viewModel,
        state = state,
        profiles = state.profiles,
        activeProfileId = state.activeProfileId,
        upmixEnabled = state.upmixEnabled,
        upmixIntensity = state.upmixIntensity,
        upmixMode = state.upmixMode,
        upmixBassLevel = state.upmixBassLevel,
        upmixLfeCutoff = state.upmixLfeCutoff,
        upmixCenterHpf = state.upmixCenterHpf,
        upmixCenterLpf = state.upmixCenterLpf,
        upmixSurroundHpf = state.upmixSurroundHpf,
        upmixSurroundLpf = state.upmixSurroundLpf,
        onUpmixEnabledChanged = { viewModel.setUpmixEnabled(it) },
        onUpmixIntensityChanged = { viewModel.setUpmixIntensity(it) },
        onUpmixModeChanged = { viewModel.setUpmixMode(it) },
        onUpmixBassLevelChanged = { viewModel.setUpmixBassLevel(it) },
        onUpmixLfeCutoffChanged = { viewModel.setUpmixLfeCutoff(it) },
        onUpmixCenterHpfChanged = { viewModel.setUpmixCenterCutoffs(it, state.upmixCenterLpf) },
        onUpmixSurroundLpfChanged = { viewModel.setUpmixSurroundCutoffs(state.upmixSurroundHpf, it) },
        onProfileSelected = { viewModel.selectProfile(it) },
        onImportCustomEQ = {
            // Launch file picker for .txt files
            filePickerLauncher.launch("text/plain")
        },
        onOpenSystemEqualizer = {
            playerConnection?.let { connection ->
                val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                    putExtra(
                        AudioEffect.EXTRA_AUDIO_SESSION,
                        connection.player.audioSessionId
                    )
                    putExtra(
                        AudioEffect.EXTRA_PACKAGE_NAME,
                        context.packageName
                    )
                    putExtra(
                        AudioEffect.EXTRA_CONTENT_TYPE,
                        AudioEffect.CONTENT_TYPE_MUSIC
                    )
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    activityResultLauncher.launch(intent)
                }
            }
        },
        onDeleteProfile = { viewModel.deleteProfile(it) }
    )

    // Error dialog
    if (showError != null) {
        AlertDialog(
            onDismissRequest = { showError = null },
            title = {
                Text(stringResource(R.string.import_error_title))
            },
            text = {
                Text(showError ?: "")
            },
            confirmButton = {
                TextButton(onClick = { showError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    // Error dialog for apply failure
    if (state.error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = {
                Text(stringResource(R.string.error_title))
            },
            text = {
                Text(stringResource(R.string.error_eq_apply_failed, state.error ?: ""))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqScreenContent(
    viewModel: EQViewModel,
    state: EQState,
    profiles: List<SavedEQProfile>,
    activeProfileId: String?,
    upmixEnabled: Boolean,
    upmixIntensity: Float,
    upmixMode: UpmixAudioProcessor.UpmixMode,
    upmixBassLevel: Float = UpmixAudioProcessor.DEFAULT_BASS_LEVEL,
    upmixLfeCutoff: Float = UpmixAudioProcessor.DEFAULT_LFE_CUTOFF,
    upmixCenterHpf: Float = UpmixAudioProcessor.DEFAULT_CENTER_HPF_CUTOFF,
    upmixCenterLpf: Float = UpmixAudioProcessor.DEFAULT_CENTER_LPF_CUTOFF,
    upmixSurroundHpf: Float = UpmixAudioProcessor.DEFAULT_SURROUND_HPF_CUTOFF,
    upmixSurroundLpf: Float = UpmixAudioProcessor.DEFAULT_SURROUND_LPF_CUTOFF,
    onUpmixEnabledChanged: (Boolean) -> Unit,
    onUpmixIntensityChanged: (Float) -> Unit,
    onUpmixModeChanged: (UpmixAudioProcessor.UpmixMode) -> Unit,
    onUpmixBassLevelChanged: (Float) -> Unit = {},
    onUpmixLfeCutoffChanged: (Float) -> Unit = {},
    onUpmixCenterHpfChanged: (Float) -> Unit = {},
    onUpmixSurroundLpfChanged: (Float) -> Unit = {},
    onProfileSelected: (String?) -> Unit,
    onImportCustomEQ: () -> Unit,
    onOpenSystemEqualizer: () -> Unit,
    onDeleteProfile: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .heightIn(max = 600.dp)
            .padding(vertical = 24.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.equalizer_header),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = pluralStringResource(
                                id = R.plurals.profiles_count,
                                count = profiles.size,
                                profiles.size
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        IconButton(onClick = onImportCustomEQ) {
                            Icon(
                                painter = painterResource(R.drawable.add),
                                contentDescription = stringResource(R.string.import_profile)
                            )
                        }
                        IconButton(onClick = onOpenSystemEqualizer) {
                            Icon(
                                painter = painterResource(R.drawable.equalizer),
                                contentDescription = stringResource(R.string.system_equalizer)
                            )
                        }
                    }
                }
            }

            // AutoEq section
            item { HorizontalDivider() }
            item {
                AutoEqSection(viewModel = viewModel, state = state)
            }
            item { HorizontalDivider() }

            // Upmix surround section (includes 7.1 channel routing when expanded)
            item {
                UpmixSection(
                    enabled = upmixEnabled,
                    intensity = upmixIntensity,
                    mode = upmixMode,
                    bassLevel = upmixBassLevel,
                    lfeCutoff = upmixLfeCutoff,
                    centerHpf = upmixCenterHpf,
                    surroundLpf = upmixSurroundLpf,
                    onEnabledChanged = onUpmixEnabledChanged,
                    onIntensityChanged = onUpmixIntensityChanged,
                    onModeChanged = onUpmixModeChanged,
                    onBassLevelChanged = onUpmixBassLevelChanged,
                    onLfeCutoffChanged = onUpmixLfeCutoffChanged,
                    onCenterHpfChanged = onUpmixCenterHpfChanged,
                    onSurroundLpfChanged = onUpmixSurroundLpfChanged,
                )
            }
            item { HorizontalDivider() }

            // "No Equalization" option
            item {
                NoEqualizationItem(
                    isSelected = activeProfileId == null,
                    onSelected = { onProfileSelected(null) }
                )
            }

            // Custom profiles only
            val customProfiles = profiles.filter { it.isCustom }
            if (customProfiles.isNotEmpty()) {
                items(customProfiles) { profile ->
                    EQProfileItem(
                        profile = profile,
                        isSelected = activeProfileId == profile.id,
                        onSelected = { onProfileSelected(profile.id) },
                        onDelete = { onDeleteProfile(profile.id) }
                    )
                }
            }

            // Empty state when no custom profiles
            if (customProfiles.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.equalizer),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.no_profiles),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onImportCustomEQ) {
                                Text(stringResource(R.string.import_profile))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = onOpenSystemEqualizer) {
                                Text(stringResource(R.string.system_equalizer))
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- AUTOEQ SECTION ---

@Composable
private fun AutoEqSection(
    viewModel: EQViewModel,
    state: EQState
) {
    var isSearchExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.autoeq_hardware_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = state.autoEqName?.let {
                        stringResource(R.string.autoeq_active_profile, it)
                    } ?: stringResource(R.string.autoeq_no_profile_loaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Switch(
                checked = state.autoEqEnabled,
                onCheckedChange = { viewModel.toggleAutoEq(it) },
                enabled = state.autoEqName != null,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { isSearchExpanded = !isSearchExpanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.autoEqName == null) {
                    stringResource(R.string.autoeq_find_headphones)
                } else {
                    stringResource(R.string.autoeq_change_headphones)
                },
            )
        }

        AnimatedVisibility(visible = isSearchExpanded) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.autoEqSearchQuery,
                    onValueChange = { viewModel.onAutoEqQueryChanged(it) },
                    label = { Text(stringResource(R.string.autoeq_search_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                if (state.autoEqSearching) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }

                AnimatedVisibility(visible = state.autoEqSearchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .padding(top = 8.dp),
                    ) {
                        items(state.autoEqSearchResults) { match ->
                            ListItem(
                                headlineContent = { Text(match.name) },
                                modifier = Modifier.clickable {
                                    viewModel.downloadAndApplyAutoEq(match)
                                    isSearchExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- UPMIX SECTION ---

@Composable
private fun UpmixSection(
    enabled: Boolean,
    intensity: Float,
    mode: UpmixAudioProcessor.UpmixMode,
    bassLevel: Float = UpmixAudioProcessor.DEFAULT_BASS_LEVEL,
    lfeCutoff: Float = UpmixAudioProcessor.DEFAULT_LFE_CUTOFF,
    centerHpf: Float = UpmixAudioProcessor.DEFAULT_CENTER_HPF_CUTOFF,
    surroundLpf: Float = UpmixAudioProcessor.DEFAULT_SURROUND_LPF_CUTOFF,
    onEnabledChanged: (Boolean) -> Unit,
    onIntensityChanged: (Float) -> Unit,
    onModeChanged: (UpmixAudioProcessor.UpmixMode) -> Unit,
    onBassLevelChanged: (Float) -> Unit = {},
    onLfeCutoffChanged: (Float) -> Unit = {},
    onCenterHpfChanged: (Float) -> Unit = {},
    onSurroundLpfChanged: (Float) -> Unit = {},
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.upmix_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.upmix_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.upmix_mode_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == UpmixAudioProcessor.UpmixMode.SURROUND_5_1,
                    onClick = { onModeChanged(UpmixAudioProcessor.UpmixMode.SURROUND_5_1) },
                    label = { Text(stringResource(R.string.upmix_mode_5_1)) },
                )
                FilterChip(
                    selected = mode == UpmixAudioProcessor.UpmixMode.SURROUND_7_1,
                    onClick = { onModeChanged(UpmixAudioProcessor.UpmixMode.SURROUND_7_1) },
                    label = { Text(stringResource(R.string.upmix_mode_7_1)) },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            UpmixSliderRow(
                label = stringResource(R.string.upmix_intensity_label),
                value = intensity,
                range = 0f..1f,
                onValueChange = onIntensityChanged,
                formatValue = { "${(it * 100).toInt()}%" },
            )
            UpmixSliderRow(
                label = stringResource(R.string.upmix_bass_level),
                value = bassLevel,
                range = 0f..1f,
                onValueChange = onBassLevelChanged,
                formatValue = { "${(it * 100).toInt()}%" },
            )
            UpmixSliderRow(
                label = stringResource(R.string.upmix_lfe_crossover),
                value = lfeCutoff,
                range = 40f..250f,
                onValueChange = onLfeCutoffChanged,
                formatValue = { "${it.toInt()} Hz" },
            )
            UpmixSliderRow(
                label = stringResource(R.string.upmix_center_hpf),
                value = centerHpf,
                range = 40f..300f,
                onValueChange = onCenterHpfChanged,
                formatValue = { "${it.toInt()} Hz" },
            )
            UpmixSliderRow(
                label = stringResource(R.string.upmix_surround_lpf),
                value = surroundLpf,
                range = 2000f..15000f,
                onValueChange = onSurroundLpfChanged,
                formatValue = { "${it.toInt()} Hz" },
            )

            Spacer(modifier = Modifier.height(12.dp))
            var isChannelConfigExpanded by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { isChannelConfigExpanded = !isChannelConfigExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.upmix_71_channels_group))
            }
            AnimatedVisibility(visible = isChannelConfigExpanded) {
                UpmixChannelConfiguration()
            }
        }
    }
}

private data class ChannelDef(
    val nameRes: Int,
    val distanceKey: Preferences.Key<Float>,
    val typeKey: Preferences.Key<String>,
)

private val UPMIX_CHANNELS = listOf(
    ChannelDef(R.string.upmix_71_ch_fl, Upmix71DistanceFLKey, Upmix71TypeFLKey),
    ChannelDef(R.string.upmix_71_ch_fr, Upmix71DistanceFRKey, Upmix71TypeFRKey),
    ChannelDef(R.string.upmix_71_ch_fc, Upmix71DistanceFCKey, Upmix71TypeFCKey),
    ChannelDef(R.string.upmix_71_ch_lfe, Upmix71DistanceLFEKey, Upmix71TypeLFEKey),
    ChannelDef(R.string.upmix_71_ch_bl, Upmix71DistanceBLKey, Upmix71TypeBLKey),
    ChannelDef(R.string.upmix_71_ch_br, Upmix71DistanceBRKey, Upmix71TypeBRKey),
    ChannelDef(R.string.upmix_71_ch_sl, Upmix71DistanceSLKey, Upmix71TypeSLKey),
    ChannelDef(R.string.upmix_71_ch_sr, Upmix71DistanceSRKey, Upmix71TypeSRKey),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpmixChannelConfiguration() {
    Material3SettingsGroup(
        title = null,
        items = UPMIX_CHANNELS.map { ch -> UpmixChannelSettingsItem(ch) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpmixChannelSettingsItem(ch: ChannelDef): Material3SettingsItem {
    val (distance, onDistanceChange) = rememberPreference(
        ch.distanceKey,
        defaultValue = UpmixAudioProcessor.DEFAULT_CHANNEL_DISTANCE,
    )
    val (typeStr, onTypeStrChange) = rememberPreference(
        ch.typeKey,
        defaultValue = UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT,
    )
    return Material3SettingsItem(
        icon = painterResource(R.drawable.tune),
        title = { Text(stringResource(ch.nameRes)) },
        description = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.upmix_71_distance),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.upmix_71_distance_value, distance),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = distance,
                    onValueChange = onDistanceChange,
                    valueRange = 0.1f..10f,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.upmix_71_audio_type),
                    style = MaterialTheme.typography.bodySmall,
                )
                UpmixChannelTypeDropdown(
                    selected = typeStr,
                    onSelected = onTypeStrChange,
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpmixChannelTypeDropdown(
    selected: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val typeEntries = listOf(
        UpmixAudioProcessor.CHANNEL_TYPE_DEFAULT to R.string.upmix_71_type_default,
        UpmixAudioProcessor.CHANNEL_TYPE_FULL_MIX to R.string.upmix_71_type_full_mix,
        UpmixAudioProcessor.CHANNEL_TYPE_AMBIENT to R.string.upmix_71_type_ambient,
        UpmixAudioProcessor.CHANNEL_TYPE_VOCAL to R.string.upmix_71_type_vocal,
        UpmixAudioProcessor.CHANNEL_TYPE_BASS to R.string.upmix_71_type_bass,
        UpmixAudioProcessor.CHANNEL_TYPE_SILENT to R.string.upmix_71_type_silent,
    )
    val currentLabel = typeEntries.firstOrNull { it.first == selected }?.second ?: R.string.upmix_71_type_default
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(currentLabel),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            typeEntries.forEach { (value, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun UpmixSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    formatValue: (Float) -> String,
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatValue(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// --- HELPER COMPOSABLES ---

@Composable
private fun NoEqualizationItem(
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                stringResource(R.string.eq_disabled),
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        leadingContent = {
            RadioButton(
                selected = isSelected,
                onClick = onSelected
            )
        },
        modifier = Modifier
            .clickable(onClick = onSelected)
            .padding(horizontal = 8.dp) // align with design
    )
}

@Composable
private fun EQProfileItem(
    profile: SavedEQProfile,
    isSelected: Boolean,
    onSelected: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                text = profile.deviceModel,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        supportingContent = {
            Text(
                pluralStringResource(
                    id = R.plurals.band_count,
                    count = profile.bands.size,
                    profile.bands.size
                )
            )
        },
        leadingContent = {
            RadioButton(
                selected = isSelected,
                onClick = onSelected
            )
        },
        trailingContent = {
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.delete_profile_desc),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        modifier = Modifier
            .clickable(onClick = onSelected)
            .padding(horizontal = 8.dp)
    )

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_profile_desc)) },
            text = {
                Text(
                    stringResource(R.string.delete_profile_confirmation, profile.name)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}