package se.healthbridge.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import se.healthbridge.app.data.SettingsRepository
import se.healthbridge.app.data.SyncLog
import se.healthbridge.app.healthconnect.HealthConnectRepository
import se.healthbridge.app.sync.SyncEngine
import se.healthbridge.app.sync.SyncScheduler
import se.healthbridge.app.withings.WithingsApi
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency container; one instance per process. */
class AppContainer(context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val syncLog = SyncLog(context)
    val healthConnect = HealthConnectRepository(context)
    val withingsApi = WithingsApi(
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
    )
    val syncEngine = SyncEngine(context, settings, withingsApi, healthConnect, syncLog)
}

class HealthBridgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Make sure the background sync survives app updates / data restores.
        container.applicationScope.launch {
            val settings = container.settings.current()
            if (settings.withingsConnected) {
                SyncScheduler.schedulePeriodic(this@HealthBridgeApp, settings.options.intervalHours, replace = false)
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as HealthBridgeApp).container
