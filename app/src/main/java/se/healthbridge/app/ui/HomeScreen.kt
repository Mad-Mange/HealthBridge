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
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
        ScreenTitle("HealthBridge", "Withings → Health Connect → Samsung Health")

        // Health Connect
        val hcStatus = when {
            !hc.checked -> Status.NEUTRAL
            hc.allGranted -> Status.OK
            else -> Status.WARNING
        }
        StatusCard("Health Connect", Icons.Filled.Favorite, hcStatus) {
            when {
                !hc.checked -> Text("Kontrollerar…")
                !hc.available -> {
                    val update = hc.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
                    Text(if (update) "Health Connect behöver uppdateras." else "Health Connect finns inte på den här enheten.")
                    if (update) {
                        Button(onClick = {
                            val uri = "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding".toUri()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        }) { Text("Uppdatera i Play Butik") }
                    }
                }
                hc.allGranted -> Text("Alla ${hc.requiredCount} behörigheter är beviljade.")
                else -> {
                    Text("${hc.grantedCount} av ${hc.requiredCount} behörigheter beviljade. Appen behöver skriva Withings-data och läsa data för översikten.")
                    Button(onClick = onRequestPermissions) { Text("Ge behörigheter") }
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
                current == null -> Text("Laddar…")
                connecting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Ansluter…")
                }
                current.withingsConnected && current.state.needsLogin -> {
                    Text("Withings godtar inte längre inloggningen, så synken står still. Logga in igen så fortsätter den där den slutade.")
                    Button(onClick = { vm.startWithingsLogin() }) { Text("Logga in igen") }
                }
                current.withingsConnected -> Text("Ansluten (användar-ID ${current.tokens?.userId.orEmpty()}).")
                !current.credentials.isComplete -> {
                    Text("Skapa en app på developer.withings.com och fyll i Client ID, Client Secret och Callback-URL.")
                    Button(onClick = onOpenSettings) { Text("Till inställningar") }
                }
                else -> {
                    Text("Inte ansluten.")
                    Button(onClick = { vm.startWithingsLogin() }) { Text("Logga in på Withings") }
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
        StatusCard("Synkronisering", Icons.Filled.Refresh, syncStatus) {
            if (state?.lastRunAt != null) {
                Text("Senast ${formatEpochSeconds(state.lastRunAt)}", style = MaterialTheme.typography.labelLarge)
                state.lastRunSummary?.let { Text(it) }
            } else {
                Text("Ingen synk har körts än.")
            }
            if (current?.withingsConnected == true) {
                SupportingText(
                    "Automatiskt ${intervalText(current.options.intervalHours)}" +
                        (nextRun?.let { " · nästa ${formatEpochMillis(it)}" } ?: ""),
                )
            }
            FilledTonalButton(
                onClick = { vm.syncNow() },
                enabled = !running && current?.withingsConnected == true,
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Synkar…")
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Synka nu")
                }
            }
        }

        if (current?.withingsConnected == true && !batteryUnrestricted) {
            StatusCard("Bakgrundskörning", Icons.Filled.Warning, Status.WARNING) {
                Text(
                    "Samsung söver appar som körs i bakgrunden för att spara batteri, och då stannar synken. " +
                        "Tillåt HealthBridge att alltid köras i bakgrunden (obegränsad batterianvändning).",
                )
                Button(onClick = {
                    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                    try {
                        context.startActivity(ask)
                    } catch (_: ActivityNotFoundException) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                }) { Text("Tillåt") }
            }
        }

        StatusCard("Samsung Health", Icons.Filled.Info, Status.NEUTRAL) {
            Text(
                "Samsung Health läser Withings-värdena via Health Connect. Öppna Samsung Health → " +
                    "Inställningar → Health Connect och se till att vikt, blodtryck, sömn m.m. får läsas.",
            )
            Button(onClick = {
                val intent = context.packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth")
                try {
                    if (intent != null) context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
            }) { Text("Öppna Samsung Health") }
        }
    }
}
