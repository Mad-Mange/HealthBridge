package se.healthbridge.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.healthbridge.app.R

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hc by vm.healthConnect.collectAsStateWithLifecycle()
    val running by vm.syncRunning.collectAsStateWithLifecycle()
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    val nextRun by vm.nextRun.collectAsStateWithLifecycle()
    val batteryUnrestricted by vm.batteryUnrestricted.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle(stringResource(R.string.app_name), stringResource(R.string.home_subtitle))

        // Health Connect
        val hcStatus = when {
            !hc.checked -> Status.NEUTRAL
            hc.allGranted -> Status.OK
            else -> Status.WARNING
        }
        StatusCard("Health Connect", Icons.Filled.Favorite, hcStatus) {
            when {
                !hc.checked -> Text(stringResource(R.string.hc_checking))
                !hc.available -> {
                    val update = hc.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
                    Text(stringResource(if (update) R.string.hc_update_required else R.string.hc_missing))
                    if (update) {
                        Button(onClick = {
                            val uri = "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding".toUri()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        }) { Text(stringResource(R.string.hc_update_button)) }
                    }
                }
                hc.allGranted -> Text(stringResource(R.string.hc_all_granted, hc.requiredCount))
                else -> {
                    Text(stringResource(R.string.hc_some_granted, hc.grantedCount, hc.requiredCount))
                    Button(onClick = onRequestPermissions) { Text(stringResource(R.string.hc_grant_button)) }
                }
            }
        }

        // Withings
        val current = settings
        val withingsStatus = when {
            current == null -> Status.NEUTRAL
            current.withingsConnected && !current.state.needsLogin -> Status.OK
            else -> Status.WARNING
        }
        StatusCard("Withings", Icons.Filled.AccountCircle, withingsStatus) {
            when {
                current == null -> Text(stringResource(R.string.loading))
                connecting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.withings_connecting))
                }
                current.withingsConnected && current.state.needsLogin -> {
                    Text(stringResource(R.string.withings_needs_login))
                    Button(onClick = { vm.startWithingsLogin() }) { Text(stringResource(R.string.withings_log_in_again)) }
                }
                current.withingsConnected -> Text(stringResource(R.string.withings_connected, current.tokens?.userId.orEmpty()))
                !current.credentials.isComplete -> {
                    Text(stringResource(R.string.withings_setup_needed))
                    Button(onClick = onOpenSettings) { Text(stringResource(R.string.go_to_settings)) }
                }
                else -> {
                    Text(stringResource(R.string.withings_not_connected))
                    Button(onClick = { vm.startWithingsLogin() }) { Text(stringResource(R.string.withings_log_in)) }
                }
            }
        }

        // Sync
        val state = current?.state
        val syncStatus = when {
            state?.lastRunAt == null -> Status.NEUTRAL
            state.lastRunOk -> Status.OK
            else -> Status.WARNING
        }
        StatusCard(stringResource(R.string.sync_title), Icons.Filled.Refresh, syncStatus) {
            if (state?.lastRunAt != null) {
                Text(stringResource(R.string.sync_last, formatEpochSeconds(state.lastRunAt)), style = MaterialTheme.typography.labelLarge)
                state.lastRunSummary?.let { Text(it) }
            } else {
                Text(stringResource(R.string.sync_never))
            }
            if (current?.withingsConnected == true) {
                val interval = intervalText(current.options.intervalHours)
                val next = nextRun
                SupportingText(
                    if (next != null) {
                        stringResource(R.string.sync_automatic_next, interval, formatEpochMillis(next))
                    } else {
                        stringResource(R.string.sync_automatic, interval)
                    },
                )
            }
            FilledTonalButton(
                onClick = { vm.syncNow() },
                enabled = !running && current?.withingsConnected == true,
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  " + stringResource(R.string.sync_running))
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  " + stringResource(R.string.sync_now))
                }
            }
        }

        if (current?.withingsConnected == true && !batteryUnrestricted) {
            StatusCard(stringResource(R.string.battery_title), Icons.Filled.Warning, Status.WARNING) {
                Text(stringResource(R.string.battery_text))
                Button(onClick = {
                    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                    try {
                        context.startActivity(ask)
                    } catch (_: ActivityNotFoundException) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                }) { Text(stringResource(R.string.battery_allow)) }
            }
        }

        StatusCard("Samsung Health", Icons.Filled.Info, Status.NEUTRAL) {
            Text(stringResource(R.string.samsung_text))
            Button(onClick = {
                val intent = context.packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth")
                try {
                    if (intent != null) context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
            }) { Text(stringResource(R.string.samsung_open)) }
        }
    }
}
