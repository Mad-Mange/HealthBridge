package se.healthbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.healthbridge.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Status { OK, WARNING, NEUTRAL }

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    icon: ImageVector,
    status: Status,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tint = when (status) {
        Status.OK -> MaterialTheme.colorScheme.primary
        Status.WARNING -> MaterialTheme.colorScheme.error
        Status.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val timeOnly = DateTimeFormatter.ofPattern("HH:mm")

/** "today 08:12", "yesterday 22:40" or "3 Oct 07:15", in the UI's language (not necessarily the phone's). */
@Composable
fun formatTime(instant: Instant): String {
    val zone = ZoneId.systemDefault()
    val local = instant.atZone(zone)
    val today = LocalDate.now(zone)
    val time = timeOnly.format(local)
    return when (local.toLocalDate()) {
        today -> stringResource(R.string.time_today, time)
        today.minusDays(1) -> stringResource(R.string.time_yesterday, time)
        today.plusDays(1) -> stringResource(R.string.time_tomorrow, time)
        else -> {
            val locale = Locale.forLanguageTag(stringResource(R.string.date_locale))
            DateTimeFormatter.ofPattern("d MMM HH:mm", locale).format(local)
        }
    }
}

@Composable
fun intervalText(hours: Int): String = when (hours) {
    1 -> stringResource(R.string.interval_hourly)
    24 -> stringResource(R.string.interval_daily)
    else -> stringResource(R.string.interval_hours, hours)
}

@Composable
fun formatEpochSeconds(seconds: Long): String = formatTime(Instant.ofEpochSecond(seconds))

@Composable
fun formatEpochMillis(millis: Long): String = formatTime(Instant.ofEpochMilli(millis))
