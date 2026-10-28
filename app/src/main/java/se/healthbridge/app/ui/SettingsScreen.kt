package se.healthbridge.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.healthbridge.app.BuildConfig
import se.healthbridge.app.R
import se.healthbridge.app.appContainer
import se.healthbridge.app.withings.WithingsApi

private val INTERVALS = listOf(1, 3, 6, 12, 24)
private val BACKFILL = listOf(7, 30, 90, 365)

@Composable
fun SettingsScreen(vm: MainViewModel, onRequestPermissions: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current = settings ?: return
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenTitle(stringResource(R.string.settings_title))

        // --- Withings developer app ---
        SectionHeader(stringResource(R.string.settings_withings_app))
        SupportingText(stringResource(R.string.settings_withings_help))
        val creds = current.credentials
        var clientId by rememberSaveable(creds.clientId) { mutableStateOf(creds.clientId) }
        var clientSecret by rememberSaveable(creds.clientSecret) { mutableStateOf(creds.clientSecret) }
        var redirectUri by rememberSaveable(creds.redirectUri) { mutableStateOf(creds.redirectUri) }
        var showSecret by rememberSaveable { mutableStateOf(false) }

        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text(stringResource(R.string.client_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = { Text(stringResource(R.string.client_secret)) },
            singleLine = true,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showSecret = !showSecret }) { Text(stringResource(if (showSecret) R.string.hide else R.string.show)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = redirectUri,
            onValueChange = { redirectUri = it },
            label = { Text(stringResource(R.string.callback_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.saveCredentials(clientId, clientSecret, redirectUri) },
                enabled = clientId != creds.clientId || clientSecret != creds.clientSecret || redirectUri != creds.redirectUri,
            ) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = { openUrl(context, WithingsApi.DEVELOPER_DASHBOARD_URL) }) {
                Text(stringResource(R.string.open_withings_developer))
            }
        }

        // --- Connection ---
        SectionHeader(stringResource(R.string.settings_connection))
        if (current.withingsConnected) {
            Text(stringResource(R.string.settings_connected_as, current.tokens?.userId.orEmpty()))
            OutlinedButton(onClick = { vm.disconnectWithings() }) { Text(stringResource(R.string.disconnect_withings)) }
        } else {
            Button(onClick = { vm.startWithingsLogin() }, enabled = creds.isComplete) { Text(stringResource(R.string.withings_log_in)) }
            ExternalLogin(vm)
        }

        // --- What to sync ---
        SectionHeader(stringResource(R.string.settings_what))
        val options = current.options
        SwitchRow(
            title = stringResource(R.string.opt_measures),
            subtitle = stringResource(R.string.opt_measures_desc),
            checked = options.measures,
            onChange = { vm.saveOptions(options.copy(measures = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.opt_sleep),
            subtitle = stringResource(R.string.opt_sleep_desc),
            checked = options.sleep,
            onChange = { vm.saveOptions(options.copy(sleep = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.opt_activity),
            subtitle = stringResource(R.string.opt_activity_desc),
            checked = options.activity,
            onChange = { vm.saveOptions(options.copy(activity = it)) },
        )

        SectionHeader(stringResource(R.string.settings_how_often))
        ChipRow(INTERVALS, options.intervalHours, label = {
            if (it == 24) stringResource(R.string.interval_chip_day) else stringResource(R.string.interval_chip_hours, it)
        }) {
            vm.saveOptions(options.copy(intervalHours = it))
        }
        SupportingText(stringResource(R.string.settings_battery_note))

        SectionHeader(stringResource(R.string.settings_history))
        ChipRow(BACKFILL, options.backfillDays, label = { stringResource(R.string.days_chip, it) }) {
            vm.saveOptions(options.copy(backfillDays = it))
        }
        SupportingText(stringResource(R.string.settings_history_note))
        OutlinedButton(onClick = { vm.refetchHistory() }, enabled = current.withingsConnected) {
            Text(stringResource(R.string.refetch_history))
        }

        // --- Health Connect ---
        SectionHeader("Health Connect")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRequestPermissions) { Text(stringResource(R.string.hc_permissions)) }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(context.appContainer.healthConnect.manageDataIntent()) }
            }) { Text(stringResource(R.string.hc_manage_data)) }
        }

        SupportingText(stringResource(R.string.version_label, BuildConfig.VERSION_NAME))
    }
}

/** Fallback when the in-app login can't be used (e.g. a Withings account that signs in with Google). */
@Composable
private fun ExternalLogin(vm: MainViewModel) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.ext_login_hide else R.string.ext_login_show))
    }
    if (!expanded) return
    SupportingText(stringResource(R.string.ext_login_steps))
    OutlinedButton(onClick = {
        vm.externalLoginUrl()?.let { url ->
            runCatching { CustomTabsIntent.Builder().build().launchUrl(context, url.toUri()) }
                .onFailure { openUrl(context, url) }
        }
    }) { Text(stringResource(R.string.ext_open_browser)) }
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        label = { Text(stringResource(R.string.ext_paste_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { vm.handleRedirect(pasted); pasted = "" }, enabled = pasted.contains("code=")) {
        Text(stringResource(R.string.ext_finish))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ChipRow(values: List<Int>, selected: Int, label: @Composable (Int) -> String, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label(value)) })
        }
    }
}

internal fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
    }
}
