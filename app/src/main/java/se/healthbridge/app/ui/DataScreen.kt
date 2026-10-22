package se.healthbridge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.healthbridge.app.healthconnect.HealthConnectRepository
import se.healthbridge.app.healthconnect.MetricSnapshot
import se.healthbridge.app.healthconnect.SourceFilter
import java.time.LocalDate

private const val EXPORT_DAYS = 30

@Composable
fun DataScreen(vm: MainViewModel) {
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val filter by vm.sourceFilter.collectAsStateWithLifecycle()
    val busy by vm.dataBusy.collectAsStateWithLifecycle()
    val hc by vm.healthConnect.collectAsStateWithLifecycle()
    val ownPackage = LocalContext.current.packageName

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri, EXPORT_DAYS)
    }

    LaunchedEffect(hc.grantedCount) {
        if (hc.available) vm.loadData()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenTitle("Data", "Senaste värdena i Health Connect (30 dagar)")
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { vm.loadData(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.loadData() }, enabled = !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("  Uppdatera")
                }
                OutlinedButton(
                    onClick = { exportLauncher.launch("healthbridge-${LocalDate.now()}.csv") },
                    enabled = !busy && hc.available,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text("  Exportera CSV")
                }
            }
        }
        if (busy) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        val list = snapshots
        when {
            !hc.available -> item { SupportingText("Health Connect är inte tillgängligt.") }
            hc.grantedCount == 0 -> item { SupportingText("Ge Health Connect-behörigheter på startsidan för att se data.") }
            list == null -> Unit
            list.isEmpty() -> item {
                SupportingText(
                    if (filter == SourceFilter.SAMSUNG) {
                        "Ingen data från Samsung Health hittades. Kontrollera att Samsung Health synkar till Health Connect " +
                            "(Samsung Health → Inställningar → Health Connect)."
                    } else {
                        "Ingen data hittades de senaste 30 dagarna."
                    },
                )
            }
            else -> items(list, key = { it.title }) { MetricCard(it, ownPackage) }
        }
        item {
            SupportingText("CSV-exporten innehåller alla läsbara typer de senaste $EXPORT_DAYS dagarna för vald källa.")
        }
    }
}

@Composable
private fun MetricCard(snapshot: MetricSnapshot, ownPackage: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(snapshot.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(snapshot.value, style = MaterialTheme.typography.titleLarge)
            val sources = snapshot.sources.joinToString { HealthConnectRepository.sourceName(it, ownPackage) }
            SupportingText(listOf(formatTime(snapshot.time), sources).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }
}
