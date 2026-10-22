package se.healthbridge.app.ui

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.healthbridge.app.R
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
    val context = LocalContext.current

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
            ScreenTitle(stringResource(R.string.data_title), stringResource(R.string.data_subtitle))
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
                        label = { Text(stringResource(option.label)) },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.loadData() }, enabled = !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("  " + stringResource(R.string.data_refresh))
                }
                OutlinedButton(
                    onClick = { exportLauncher.launch("healthbridge-${LocalDate.now()}.csv") },
                    enabled = !busy && hc.available,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text("  " + stringResource(R.string.data_export))
                }
            }
        }
        if (busy) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        val list = snapshots
        when {
            !hc.available -> item { SupportingText(stringResource(R.string.data_hc_unavailable)) }
            hc.grantedCount == 0 -> item { SupportingText(stringResource(R.string.data_no_permissions)) }
            list == null -> Unit
            list.isEmpty() -> item {
                SupportingText(stringResource(if (filter == SourceFilter.SAMSUNG) R.string.data_no_samsung else R.string.data_empty))
            }
            else -> items(list, key = { it.title }) { MetricCard(it, context) }
        }
        item {
            SupportingText(stringResource(R.string.data_export_note, EXPORT_DAYS))
        }
    }
}

@Composable
private fun MetricCard(snapshot: MetricSnapshot, context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(snapshot.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(snapshot.value, style = MaterialTheme.typography.titleLarge)
            val sources = snapshot.sources.joinToString { HealthConnectRepository.sourceName(context, it) }
            SupportingText(listOf(formatTime(snapshot.time), sources).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }
}
