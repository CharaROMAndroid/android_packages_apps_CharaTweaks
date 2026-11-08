/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: BaikalOS Project, Sergei Vasilev <svasiliev22@yandex.ru>
 * SPDX-License-Identifier: Apache-2.0
 *
 * UI design derived from BaikalOS (BaikalSettings) app privacy / app performance screens.
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.crdroid.settings.fragments.misc

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
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
 * Per-app "hide other installed apps from this app" list for the CURRENT user only.
 *
 * Writes Settings.Secure chararom_app_list_hiding as {"<pkg>": true}. This restricts what the
 * selected app can see when it queries installed packages; it does not hide the app from the
 * launcher and does not hide the app globally. The enforcement engine lives in the framework.
 */
class AppListHiding : SettingsPreferenceFragment() {

    internal class HideUiState {
        var loading by mutableStateOf(true)
        var query by mutableStateOf("")
        var showSystem by mutableStateOf(false)
        val apps = mutableStateListOf<AppListEntry>()
    }

    private val ui = HideUiState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hidden = mutableSetOf<String>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.app_list_hiding_title)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = spoofComposeView(requireContext()).apply {
        val fragment = this@AppListHiding
        setContent {
            SettingsTheme {
                AppListHidingContent(
                    state = ui,
                    onToggle = fragment::setHidden,
                )
            }
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
            val (current, entries) = withContext(Dispatchers.IO) {
                val set = AppPolicyStore.parseHiding(
                    Settings.Secure.getString(ctx.contentResolver, AppPolicyStore.HIDING_KEY))
                set to filterInstalledApps(pm = pm, showSystem = true, targeted = set)
                    .map { app ->
                        AppListEntry(
                            packageName = app.packageName,
                            label = pm.getApplicationLabel(app).toString(),
                            icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                            isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                            isSelected = app.packageName in set,
                        )
                    }
                    .sortedWith(compareBy({ !it.isSelected },
                        { it.label.lowercase(Locale.getDefault()) }))
            }
            hidden = current.toMutableSet()
            ui.apps.clear()
            ui.apps.addAll(entries)
            ui.loading = false
        }
    }

    private fun setHidden(packageName: String, value: Boolean) {
        if (value) hidden.add(packageName) else hidden.remove(packageName)
        // null removes the key entirely, which is the "nothing hidden" default.
        Settings.Secure.putString(requireContext().contentResolver,
            AppPolicyStore.HIDING_KEY, AppPolicyStore.serializeHiding(hidden))
        val index = ui.apps.indexOfFirst { it.packageName == packageName }
        if (index >= 0) ui.apps[index] = ui.apps[index].copy(isSelected = value)
    }
}

@Composable
private fun AppListHidingContent(
    state: AppListHiding.HideUiState,
    onToggle: (String, Boolean) -> Unit,
) {
    SpoofScreen {
        SpoofingHeaderCard(
            title = stringResource(R.string.app_list_hiding_title),
            subtitle = stringResource(
                R.string.app_list_hiding_count, state.apps.count { it.isSelected }),
        ) {
            Icon(
                imageVector = Icons.Rounded.VisibilityOff,
                contentDescription = null,
                tint = spoofHeaderIconTint(state.apps.any { it.isSelected }),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.app_list_hiding_row_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
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
            val shown = remember(state.apps.toList(), q, state.showSystem) {
                state.apps.filter { app ->
                    (state.showSystem || !app.isSystem || app.isSelected) &&
                        (q.isEmpty() || app.label.lowercase().contains(q) ||
                            app.packageName.lowercase().contains(q))
                }
            }
            if (shown.isEmpty()) {
                SpoofingEmptyState(
                    icon = Icons.Rounded.VisibilityOff,
                    title = stringResource(R.string.gs_no_matching_apps),
                    description = "",
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(SpoofDimens.RowGap)) {
                shown.forEachIndexed { index, app ->
                    Surface(
                        onClick = { onToggle(app.packageName, !app.isSelected) },
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
                                Text(
                                    stringResource(R.string.app_list_hiding_switch_title),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            CheckCloseSwitch(
                                checked = app.isSelected,
                                onCheckedChange = { onToggle(app.packageName, it) },
                            )
                        }
                    }
                }
            }
        }
        SpoofFooter(stringResource(R.string.app_list_hiding_footer))
    }
}
