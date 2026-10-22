package se.healthbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.healthbridge.app.R
import se.healthbridge.app.data.LogEntry

@Composable
fun LogScreen(vm: MainViewModel) {
    val entries by vm.log.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { ScreenTitle(stringResource(R.string.log_title), stringResource(R.string.log_subtitle)) }
                if (entries.isNotEmpty()) TextButton(onClick = { vm.clearLog() }) { Text(stringResource(R.string.log_clear)) }
            }
        }
        if (entries.isEmpty()) {
            item { SupportingText(stringResource(R.string.log_empty)) }
        }
        items(entries) { entry ->
            LogRow(entry)
            HorizontalDivider()
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val (icon, tint) = when (entry.level) {
        LogEntry.Level.INFO -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        LogEntry.Level.WARN -> Icons.Filled.Warning to MaterialTheme.colorScheme.tertiary
        LogEntry.Level.ERROR -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = entry.level.name, tint = tint, modifier = Modifier.size(20.dp))
        Column {
            Text(formatEpochMillis(entry.timestamp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
