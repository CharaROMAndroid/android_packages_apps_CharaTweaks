/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: BaikalOS Project, Sergei Vasilev <svasiliev22@yandex.ru>
 * SPDX-License-Identifier: Apache-2.0
 *
 * UI design derived from BaikalOS (BaikalSettings) app privacy / app performance screens.
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.crdroid.settings.fragments.misc

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Per-app performance profile for the CURRENT user. Writes Settings.Secure
 * chararom_app_performance_profiles as {"<pkg>": "<mode>"}; "default" entries are omitted.
 * The engine applying the profile lives in the framework / power HAL.
 */
class AppPerformance : SettingsPreferenceFragment() {

    internal class PerfUiState {
        var loading by mutableStateOf(true)
        var query by mutableStateOf("")
        var showSystem by mutableStateOf(false)
        var picking by mutableStateOf<AppListEntry?>(null)
        var supported by mutableStateOf(listOf(AppPolicyStore.MODE_DEFAULT))
        val apps = mutableStateListOf<AppListEntry>()
        val modes = mutableStateMapOf<String, String>()
    }

    private val ui = PerfUiState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.app_performance_title)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = spoofComposeView(requireContext()).apply {
        val fragment = this@AppPerformance
        setContent {
            SettingsTheme { AppPerformanceContent(ui, fragment::setMode) }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.CRDROID_SETTINGS

    private fun load() {
        scope.launch {
            val ctx = requireContext()
            val pm = ctx.packageManager
            val (profiles, entries) = withContext(Dispatchers.IO) {
                val p = AppPolicyStore.parsePerformance(
                    Settings.Secure.getString(ctx.contentResolver, AppPolicyStore.PERFORMANCE_KEY))
                p to filterInstalledApps(pm = pm, showSystem = true, targeted = p.keys)
                    .map { app ->
                        AppListEntry(
                            packageName = app.packageName,
                            label = pm.getApplicationLabel(app).toString(),
                            icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                            isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        )
                    }
                    .sortedWith(compareBy({ it.packageName !in p },
                        { it.label.lowercase(Locale.getDefault()) }))
            }
            ui.supported = supportedModes(ctx)
            ui.modes.clear()
            ui.modes.putAll(profiles)
            ui.apps.clear()
            ui.apps.addAll(entries)
            ui.loading = false
        }
    }

    private fun setMode(packageName: String, mode: String) {
        if (mode == AppPolicyStore.MODE_DEFAULT) ui.modes.remove(packageName)
        else ui.modes[packageName] = mode
        Settings.Secure.putString(requireContext().contentResolver,
            AppPolicyStore.PERFORMANCE_KEY, AppPolicyStore.serializePerformance(ui.modes.toMap()))
    }

    companion object {
        /**
         * Modes this device can actually apply. No hardware backend capability is defined yet, so
         * only "default" is offered; the other contract modes are shown disabled. When a real
         * capability query exists, return its result here (always including "default").
         */
        internal fun supportedModes(@Suppress("UNUSED_PARAMETER") context: Context): List<String> =
            listOf(AppPolicyStore.MODE_DEFAULT)
    }
}

@Composable
private fun modeLabel(mode: String): String = stringResource(
    when (mode) {
        AppPolicyStore.MODE_POWER_SAVE -> R.string.app_performance_mode_power_save
        AppPolicyStore.MODE_BALANCED -> R.string.app_performance_mode_balanced
        AppPolicyStore.MODE_PERFORMANCE -> R.string.app_performance_mode_performance
        else -> R.string.app_performance_mode_default
    })

@Composable
private fun AppPerformanceContent(
    state: AppPerformance.PerfUiState,
    onSetMode: (String, String) -> Unit,
) {
    state.picking?.let { app ->
        val current = state.modes[app.packageName] ?: AppPolicyStore.MODE_DEFAULT
        SpoofDialog(
            onDismissRequest = { state.picking = null },
            title = app.label,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SpoofDimens.RowGap)) {
                    AppPolicyStore.ALL_MODES.forEachIndexed { index, mode ->
                        val ok = mode in state.supported
                        Surface(
                            onClick = { onSetMode(app.packageName, mode); state.picking = null },
                            enabled = ok,
                            shape = SpoofShapes.forIndex(index, AppPolicyStore.ALL_MODES.size),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth().alpha(if (ok) 1f else 0.5f),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    modeLabel(mode) + if (mode == current) "  ✓" else "",
                                    style = MaterialTheme.typography.bodyLarge)
                                if (!ok) Text(
                                    stringResource(R.string.app_performance_unsupported),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { state.picking = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    SpoofScreen {
        SpoofingHeaderCard(
            title = stringResource(R.string.app_performance_title),
            subtitle = stringResource(R.string.app_performance_count, state.modes.size),
        ) {
            Icon(Icons.Rounded.Speed, contentDescription = null,
                tint = spoofHeaderIconTint(state.modes.isNotEmpty()))
        }
        Spacer(Modifier.height(16.dp))
        AppPickerSearchField(query = state.query, onQueryChange = { state.query = it })
        Spacer(Modifier.height(8.dp))
        SpoofSwitchRow(
            title = stringResource(R.string.show_system_apps),
            checked = state.showSystem,
            onCheckedChange = { state.showSystem = it },
        )
        Spacer(Modifier.height(8.dp))
        if (state.loading) {
            SpoofingLoadingBox(Modifier.padding(vertical = 32.dp))
        } else {
            val q = state.query.trim().lowercase()
            val shown = remember(state.apps.toList(), q, state.showSystem, state.modes.keys.toSet()) {
                state.apps.filter { app ->
                    (state.showSystem || !app.isSystem || app.packageName in state.modes) &&
                        (q.isEmpty() || app.label.lowercase().contains(q) ||
                            app.packageName.lowercase().contains(q))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(SpoofDimens.RowGap)) {
                shown.forEachIndexed { index, app ->
                    val mode = state.modes[app.packageName] ?: AppPolicyStore.MODE_DEFAULT
                    Surface(
                        onClick = { state.picking = app },
                        shape = SpoofShapes.forIndex(index, shown.size),
                        color = MaterialTheme.colorScheme.surfaceBright,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIconOrPlaceholder(app.packageName, app.icon, APP_ICON_SIZE_CARD)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            SpoofStatusChip(
                                text = modeLabel(mode),
                                tone = if (mode == AppPolicyStore.MODE_DEFAULT) SpoofTone.Neutral
                                else SpoofTone.Positive,
                            )
                        }
                    }
                }
            }
        }
        SpoofFooter(stringResource(R.string.app_performance_footer))
    }
}
