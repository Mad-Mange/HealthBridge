package se.healthbridge.app.ui

import android.app.Application
import android.net.Uri
import android.os.PowerManager
import android.webkit.CookieManager
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.healthbridge.app.appContainer
import se.healthbridge.app.data.AppSettings
import se.healthbridge.app.data.LogEntry
import se.healthbridge.app.data.SyncOptions
import se.healthbridge.app.healthconnect.HealthConnectRepository
import se.healthbridge.app.healthconnect.MetricSnapshot
import se.healthbridge.app.healthconnect.SourceFilter
import se.healthbridge.app.sync.SyncScheduler
import se.healthbridge.app.withings.WithingsCredentials
import se.healthbridge.app.withings.WithingsRedirect
import java.util.UUID

data class HealthConnectState(
    val sdkStatus: Int = HealthConnectClient.SDK_UNAVAILABLE,
    val granted: Set<String> = emptySet(),
    val checked: Boolean = false,
) {
    val available: Boolean get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE
    val requiredCount: Int get() = HealthConnectRepository.PERMISSIONS.size
    val grantedCount: Int get() = HealthConnectRepository.PERMISSIONS.count { it in granted }
    val allGranted: Boolean get() = available && grantedCount == requiredCount
}

/** An in-app Withings login in progress (shown as a WebView). */
data class LoginRequest(val url: String, val redirectUri: String)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer

    val settings: StateFlow<AppSettings?> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val log: StateFlow<List<LogEntry>> = container.syncLog.entries
    val syncRunning: StateFlow<Boolean> =
        SyncScheduler.isRunning(app).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val nextRun: StateFlow<Long?> =
        SyncScheduler.nextRun(app).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _healthConnect = MutableStateFlow(HealthConnectState())
    val healthConnect: StateFlow<HealthConnectState> = _healthConnect.asStateFlow()

    /** False while One UI may put the app to sleep and stop the background sync. */
    private val _batteryUnrestricted = MutableStateFlow(true)
    val batteryUnrestricted: StateFlow<Boolean> = _batteryUnrestricted.asStateFlow()

    private val _login = MutableStateFlow<LoginRequest?>(null)
    val login: StateFlow<LoginRequest?> = _login.asStateFlow()
    private var pendingState: String? = null

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _sourceFilter = MutableStateFlow(SourceFilter.ALL)
    val sourceFilter: StateFlow<SourceFilter> = _sourceFilter.asStateFlow()
    private val _snapshots = MutableStateFlow<List<MetricSnapshot>?>(null)
    val snapshots: StateFlow<List<MetricSnapshot>?> = _snapshots.asStateFlow()
    private val _dataBusy = MutableStateFlow(false)
    val dataBusy: StateFlow<Boolean> = _dataBusy.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        // Refresh the data screen when a sync finishes.
        viewModelScope.launch {
            syncRunning.drop(1).filter { running -> !running }.collect {
                if (_snapshots.value != null) loadData(_sourceFilter.value)
            }
        }
    }

    fun refreshHealthConnect() {
        viewModelScope.launch {
            val repo = container.healthConnect
            val status = repo.sdkStatus()
            val granted = runCatching { repo.grantedPermissions() }.getOrDefault(emptySet())
            _healthConnect.value = HealthConnectState(status, granted, checked = true)
        }
    }

    fun refreshBatteryStatus() {
        val power = app.getSystemService(PowerManager::class.java)
        _batteryUnrestricted.value = power.isIgnoringBatteryOptimizations(app.packageName)
    }

    fun onPermissionsResult(granted: Set<String>) {
        refreshHealthConnect()
        if (granted.isEmpty()) message("Inga behörigheter gavs")
    }

    // --- Withings ---------------------------------------------------------------------------

    fun saveCredentials(clientId: String, clientSecret: String, redirectUri: String) {
        viewModelScope.launch {
            container.settings.saveCredentials(WithingsCredentials(clientId, clientSecret, redirectUri))
            message("Withings-uppgifter sparade")
        }
    }

    /** Opens the in-app login. Returns false if credentials are missing. */
    fun startWithingsLogin(): Boolean {
        val url = newAuthorizeUrl() ?: return false
        _login.value = LoginRequest(url, settings.value!!.credentials.redirectUri)
        return true
    }

    /** URL for logging in through an external browser (the redirect URL is then pasted back). */
    fun externalLoginUrl(): String? = newAuthorizeUrl()

    fun cancelLogin() {
        _login.value = null
    }

    fun handleRedirect(url: String) {
        _login.value = null
        val redirect = WithingsRedirect.parse(url)
        when {
            redirect == null -> message("Kunde inte läsa adressen från Withings")
            redirect.error != null -> message("Withings: ${redirect.error}")
            pendingState != null && redirect.state != pendingState -> message("Svaret hör inte till senaste inloggningen – försök igen")
            redirect.code == null -> message("Ingen kod i adressen från Withings")
            else -> connect(redirect.code)
        }
    }

    fun disconnectWithings() {
        viewModelScope.launch {
            container.settings.clearTokens()
            container.settings.resetCursors()
            SyncScheduler.cancelPeriodic(app)
            CookieManager.getInstance().removeAllCookies(null)
            container.syncLog.info("Withings frånkopplat")
            message("Withings frånkopplat")
        }
    }

    private fun newAuthorizeUrl(): String? {
        val credentials = settings.value?.credentials
        if (credentials == null || !credentials.isComplete) {
            message("Fyll i Client ID, Client Secret och Callback-URL under Inställningar först")
            return null
        }
        val state = UUID.randomUUID().toString()
        pendingState = state
        return container.withingsApi.authorizeUrl(credentials, state)
    }

    private fun connect(code: String) {
        viewModelScope.launch {
            _connecting.value = true
            try {
                val current = container.settings.current()
                val tokens = container.withingsApi.exchangeCode(current.credentials, code)
                container.settings.saveTokens(tokens)
                // Logging in again as the same user resumes where the last good sync stopped.
                if (current.tokens?.userId != tokens.userId) container.settings.resetCursors()
                pendingState = null
                container.syncLog.info("Ansluten till Withings (användar-ID ${tokens.userId})")
                SyncScheduler.schedulePeriodic(app, current.options.intervalHours, replace = true)
                SyncScheduler.syncNow(app)
                message("Withings anslutet – första synken har startat")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.syncLog.error("Withings-inloggningen misslyckades: ${e.message}")
                message("Inloggningen misslyckades: ${e.message}")
            } finally {
                _connecting.value = false
            }
        }
    }

    // --- Sync -------------------------------------------------------------------------------

    fun syncNow() {
        if (settings.value?.withingsConnected != true) {
            message("Anslut Withings först")
            return
        }
        SyncScheduler.syncNow(app)
    }

    fun saveOptions(options: SyncOptions) {
        viewModelScope.launch {
            val before = container.settings.current()
            container.settings.saveOptions(options)
            if (before.withingsConnected && before.options.intervalHours != options.intervalHours) {
                SyncScheduler.schedulePeriodic(app, options.intervalHours, replace = true)
            }
        }
    }

    /** Forgets the sync cursors so the next run re-fetches the whole backfill window. */
    fun refetchHistory() {
        viewModelScope.launch {
            container.settings.resetCursors()
            container.syncLog.info("Synkstatus nollställd – hämtar om historiken")
            syncNow()
        }
    }

    fun clearLog() {
        viewModelScope.launch { container.syncLog.clear() }
    }

    // --- Data screen ------------------------------------------------------------------------

    fun loadData(filter: SourceFilter = _sourceFilter.value) {
        _sourceFilter.value = filter
        viewModelScope.launch {
            _dataBusy.value = true
            try {
                _snapshots.value = container.healthConnect.latestSnapshots(filter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snapshots.value = emptyList()
                message("Kunde inte läsa Health Connect: ${e.message}")
            } finally {
                _dataBusy.value = false
            }
        }
    }

    fun exportCsv(uri: Uri, days: Int) {
        viewModelScope.launch {
            _dataBusy.value = true
            try {
                val rows = app.contentResolver.openOutputStream(uri)?.use { out ->
                    container.healthConnect.exportCsv(out, days, _sourceFilter.value)
                } ?: error("kunde inte öppna filen")
                message("Exporterade $rows rader")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Exporten misslyckades: ${e.message}")
            } finally {
                _dataBusy.value = false
            }
        }
    }

    private fun message(text: String) {
        _messages.trySend(text)
    }
}
