package com.metrolist.music.ui.screens.equalizer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
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
import com.metrolist.music.playback.audio.UpmixAudioProcessor
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.utils.rememberPreference
import kotlin.ranges.ClosedFloatingPointRange

@Composable
fun EqScreen(viewModel: EQViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showResetDialog by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .heightIn(max = 600.dp)
            .padding(vertical = 24.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.equalizer_header),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(
                            painter = painterResource(R.drawable.refresh),
                            contentDescription = stringResource(R.string.reset),
                        )
                    }
                }
            }

            item { HorizontalDivider() }

            item { AutoEqSection(viewModel = viewModel, state = state) }

            item { HorizontalDivider() }

            item {
                UpmixSection(
                    enabled = state.upmixEnabled,
                    intensity = state.upmixIntensity,
                    mode = state.upmixMode,
                    centerFocus = state.upmixCenterFocus,
                    bassLevel = state.upmixBassLevel,
                    lfeCutoff = state.upmixLfeCutoff,
                    centerHpf = state.upmixCenterHpf,
                    centerLpf = state.upmixCenterLpf,
                    surroundHpf = state.upmixSurroundHpf,
                    surroundLpf = state.upmixSurroundLpf,
                    onEnabledChanged = { viewModel.setUpmixEnabled(it) },
                    onIntensityChanged = { viewModel.setUpmixIntensity(it) },
                    onModeChanged = { viewModel.setUpmixMode(it) },
                    onCenterFocusChanged = { viewModel.setUpmixCenterFocus(it) },
                    onBassLevelChanged = { viewModel.setUpmixBassLevel(it) },
                    onLfeCutoffChanged = { viewModel.setUpmixLfeCutoff(it) },
                    onCenterHpfChanged = {
                        viewModel.setUpmixCenterCutoffs(it, state.upmixCenterLpf)
                    },
                    onCenterLpfChanged = {
                        viewModel.setUpmixCenterCutoffs(state.upmixCenterHpf, it)
                    },
                    onSurroundHpfChanged = {
                        viewModel.setUpmixSurroundCutoffs(it, state.upmixSurroundLpf)
                    },
                    onSurroundLpfChanged = {
                        viewModel.setUpmixSurroundCutoffs(state.upmixSurroundHpf, it)
                    },
                )
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset)) },
            text = { Text(stringResource(R.string.reset_eq_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAll()
                    showResetDialog = false
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (state.error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(state.error ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

// --- AUTOEQ SECTION ---

@Composable
private fun AutoEqSection(
    viewModel: EQViewModel,
    state: EQState,
) {
    var isSearchExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
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
    centerFocus: Float,
    bassLevel: Float,
    lfeCutoff: Float,
    centerHpf: Float,
    centerLpf: Float,
    surroundHpf: Float,
    surroundLpf: Float,
    onEnabledChanged: (Boolean) -> Unit,
    onIntensityChanged: (Float) -> Unit,
    onModeChanged: (UpmixAudioProcessor.UpmixMode) -> Unit,
    onCenterFocusChanged: (Float) -> Unit,
    onBassLevelChanged: (Float) -> Unit,
    onLfeCutoffChanged: (Float) -> Unit,
    onCenterHpfChanged: (Float) -> Unit,
    onCenterLpfChanged: (Float) -> Unit,
    onSurroundHpfChanged: (Float) -> Unit,
    onSurroundLpfChanged: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
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

        AnimatedVisibility(visible = enabled) {
            Column {
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
                Text(
                    text = stringResource(R.string.upmix_mode_change_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                SliderRow(
                    label = stringResource(R.string.upmix_intensity_label),
                    value = intensity,
                    range = 0f..1f,
                    onValueChange = onIntensityChanged,
                    formatValue = { "${(it * 100).toInt()}%" },
                )
                SliderRow(
                    label = stringResource(R.string.upmix_center_focus),
                    value = centerFocus,
                    range = 0f..1f,
                    onValueChange = onCenterFocusChanged,
                    formatValue = { "${(it * 100).toInt()}%" },
                )
                SliderRow(
                    label = stringResource(R.string.upmix_bass_level),
                    value = bassLevel,
                    range = 0f..1f,
                    onValueChange = onBassLevelChanged,
                    formatValue = { "${(it * 100).toInt()}%" },
                )
                SliderRow(
                    label = stringResource(R.string.upmix_lfe_crossover),
                    value = lfeCutoff,
                    range = 40f..250f,
                    onValueChange = onLfeCutoffChanged,
                    formatValue = { "${it.toInt()} Hz" },
                )
                SliderRow(
                    label = stringResource(R.string.upmix_center_hpf),
                    value = centerHpf,
                    range = 40f..300f,
                    onValueChange = onCenterHpfChanged,
                    formatValue = { "${it.toInt()} Hz" },
                )
                SliderRow(
                    label = stringResource(R.string.upmix_center_lpf),
                    value = centerLpf,
                    range = 1000f..15000f,
                    onValueChange = onCenterLpfChanged,
                    formatValue = { "${it.toInt()} Hz" },
                )
                SliderRow(
                    label = stringResource(R.string.upmix_surround_hpf),
                    value = surroundHpf,
                    range = 40f..500f,
                    onValueChange = onSurroundHpfChanged,
                    formatValue = { "${it.toInt()} Hz" },
                )
                SliderRow(
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
                    UpmixChannelConfiguration(mode = mode)
                }
            }
        }
    }
}

// --- CHANNEL CONFIGURATION ---

private data class ChannelDef(
    val nameRes: Int,
    val distanceKey: Preferences.Key<Float>,
    val typeKey: Preferences.Key<String>,
    val foldPartnerRes: Int? = null,
)

private val UPMIX_CHANNELS = listOf(
    ChannelDef(R.string.upmix_71_ch_fl, Upmix71DistanceFLKey, Upmix71TypeFLKey),
    ChannelDef(R.string.upmix_71_ch_fr, Upmix71DistanceFRKey, Upmix71TypeFRKey),
    ChannelDef(R.string.upmix_71_ch_fc, Upmix71DistanceFCKey, Upmix71TypeFCKey),
    ChannelDef(R.string.upmix_71_ch_lfe, Upmix71DistanceLFEKey, Upmix71TypeLFEKey),
    ChannelDef(R.string.upmix_71_ch_bl, Upmix71DistanceBLKey, Upmix71TypeBLKey, R.string.upmix_71_ch_sl),
    ChannelDef(R.string.upmix_71_ch_br, Upmix71DistanceBRKey, Upmix71TypeBRKey, R.string.upmix_71_ch_sr),
    ChannelDef(R.string.upmix_71_ch_sl, Upmix71DistanceSLKey, Upmix71TypeSLKey, R.string.upmix_71_ch_bl),
    ChannelDef(R.string.upmix_71_ch_sr, Upmix71DistanceSRKey, Upmix71TypeSRKey, R.string.upmix_71_ch_br),
)

@Composable
private fun UpmixChannelConfiguration(mode: UpmixAudioProcessor.UpmixMode) {
    val is51 = mode == UpmixAudioProcessor.UpmixMode.SURROUND_5_1
    Material3SettingsGroup(
        title = null,
        items = UPMIX_CHANNELS.map { ch ->
            val foldNote = if (is51 && ch.foldPartnerRes != null) {
                stringResource(R.string.upmix_51_fold_note, stringResource(ch.foldPartnerRes))
            } else {
                null
            }
            UpmixChannelSettingsItem(ch, foldNote)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpmixChannelSettingsItem(ch: ChannelDef, foldNote: String?): Material3SettingsItem {
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
                if (foldNote != null) {
                    Text(
                        text = foldNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
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
                ChannelTypeDropdown(
                    selected = typeStr,
                    onSelected = onTypeStrChange,
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelTypeDropdown(
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
    val currentLabel = typeEntries.firstOrNull { it.first == selected }?.second
        ?: R.string.upmix_71_type_default

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

// --- SHARED COMPONENTS ---

@Composable
private fun SliderRow(
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
