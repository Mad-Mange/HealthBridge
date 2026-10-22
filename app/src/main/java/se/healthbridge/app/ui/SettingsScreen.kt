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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.healthbridge.app.BuildConfig
import se.healthbridge.app.appContainer

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
        ScreenTitle("Inställningar")

        // --- Withings developer app ---
        SectionHeader("Withings-utvecklarapp")
        SupportingText(
            "Skapa en app (Public API) på developer.withings.com. Ange samma Callback-URL där som här – " +
                "vilken https-adress som helst fungerar, HealthBridge fångar omdirigeringen innan sidan laddas.",
        )
        val creds = current.credentials
        var clientId by rememberSaveable(creds.clientId) { mutableStateOf(creds.clientId) }
        var clientSecret by rememberSaveable(creds.clientSecret) { mutableStateOf(creds.clientSecret) }
        var redirectUri by rememberSaveable(creds.redirectUri) { mutableStateOf(creds.redirectUri) }
        var showSecret by rememberSaveable { mutableStateOf(false) }

        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = { Text("Client Secret") },
            singleLine = true,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Dölj" else "Visa") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = redirectUri,
            onValueChange = { redirectUri = it },
            label = { Text("Callback-URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.saveCredentials(clientId, clientSecret, redirectUri) },
                enabled = clientId != creds.clientId || clientSecret != creds.clientSecret || redirectUri != creds.redirectUri,
            ) { Text("Spara") }
            OutlinedButton(onClick = { openUrl(context, "https://developer.withings.com/dashboard/") }) {
                Text("Öppna Withings Developer")
            }
        }

        // --- Connection ---
        SectionHeader("Anslutning")
        if (current.withingsConnected) {
            Text("Ansluten som användar-ID ${current.tokens?.userId.orEmpty()}")
            OutlinedButton(onClick = { vm.disconnectWithings() }) { Text("Koppla från Withings") }
        } else {
            Button(onClick = { vm.startWithingsLogin() }, enabled = creds.isComplete) { Text("Logga in på Withings") }
            ExternalLogin(vm)
        }

        // --- What to sync ---
        SectionHeader("Vad ska synkas till Health Connect")
        val options = current.options
        SwitchRow(
            title = "Mätningar",
            subtitle = "Vikt, fett%, fettfri massa, benmassa, vatten, BMR, blodtryck, puls, SpO₂, temperatur, VO₂ max",
            checked = options.measures,
            onChange = { vm.saveOptions(options.copy(measures = it)) },
        )
        SwitchRow(
            title = "Sömn",
            subtitle = "Sömnpass med faser (Sleep Analyzer / klocka)",
            checked = options.sleep,
            onChange = { vm.saveOptions(options.copy(sleep = it)) },
        )
        SwitchRow(
            title = "Aktivitet",
            subtitle = "Steg, distans och kalorier från en Withings-klocka. Lämna av om telefonen/Galaxy Watch redan räknar steg – annars dubbelräknas de.",
            checked = options.activity,
            onChange = { vm.saveOptions(options.copy(activity = it)) },
        )

        SectionHeader("Hur ofta")
        ChipRow(INTERVALS, options.intervalHours, label = { if (it == 24) "1 dygn" else "$it h" }) {
            vm.saveOptions(options.copy(intervalHours = it))
        }
        SupportingText("Android kan skjuta upp bakgrundsjobb för att spara batteri.")

        SectionHeader("Historik att hämta")
        ChipRow(BACKFILL, options.backfillDays, label = { "$it dagar" }) {
            vm.saveOptions(options.copy(backfillDays = it))
        }
        SupportingText("Gäller första synken och \"Hämta om historik\". Redan synkade värden dubbleras inte.")
        OutlinedButton(onClick = { vm.refetchHistory() }, enabled = current.withingsConnected) {
            Text("Hämta om historik")
        }

        // --- Health Connect ---
        SectionHeader("Health Connect")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRequestPermissions) { Text("Behörigheter") }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(context.appContainer.healthConnect.manageDataIntent()) }
            }) { Text("Hantera data") }
        }

        SupportingText("HealthBridge ${BuildConfig.VERSION_NAME}")
    }
}

/** Fallback when the in-app login can't be used (e.g. a Withings account that signs in with Google). */
@Composable
private fun ExternalLogin(vm: MainViewModel) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Dölj alternativ inloggning" else "Fungerar inte inloggningen? Logga in via webbläsaren")
    }
    if (!expanded) return
    SupportingText(
        "1. Tryck \"Öppna webbläsare\" och logga in. 2. När du hamnar på Callback-sidan (den kan visa ett fel), " +
            "kopiera hela adressen. 3. Klistra in den här och tryck \"Slutför\" inom 30 sekunder.",
    )
    OutlinedButton(onClick = {
        vm.externalLoginUrl()?.let { url ->
            runCatching { CustomTabsIntent.Builder().build().launchUrl(context, url.toUri()) }
                .onFailure { openUrl(context, url) }
        }
    }) { Text("Öppna webbläsare") }
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        label = { Text("Adress med ?code=…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { vm.handleRedirect(pasted); pasted = "" }, enabled = pasted.contains("code=")) {
        Text("Slutför")
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
private fun ChipRow(values: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label(value)) })
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
    }
}
