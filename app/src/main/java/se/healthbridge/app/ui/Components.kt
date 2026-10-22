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
import androidx.compose.ui.unit.dp
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

// The UI is Swedish, so month names are too, whatever the phone's language.
private val timeOnly = DateTimeFormatter.ofPattern("HH:mm")
private val dateTime = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("sv-SE"))

/** "idag 08:12", "igår 22:40" or "3 okt 07:15". */
fun formatTime(instant: Instant): String {
    val zone = ZoneId.systemDefault()
    val local = instant.atZone(zone)
    val today = LocalDate.now(zone)
    return when (local.toLocalDate()) {
        today -> "idag ${timeOnly.format(local)}"
        today.minusDays(1) -> "igår ${timeOnly.format(local)}"
        today.plusDays(1) -> "imorgon ${timeOnly.format(local)}"
        else -> dateTime.format(local)
    }
}

fun intervalText(hours: Int): String = when (hours) {
    1 -> "varje timme"
    24 -> "en gång per dygn"
    else -> "var $hours:e timme"
}

fun formatEpochSeconds(seconds: Long): String = formatTime(Instant.ofEpochSecond(seconds))

fun formatEpochMillis(millis: Long): String = formatTime(Instant.ofEpochMilli(millis))
