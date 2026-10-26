package se.healthbridge.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import se.healthbridge.app.BuildConfig
import se.healthbridge.app.withings.WithingsApi
import se.healthbridge.app.withings.WithingsCredentials
import se.healthbridge.app.withings.WithingsTokens

private val Context.dataStore by preferencesDataStore(name = "settings")

/** What to sync and how often. */
data class SyncOptions(
    val intervalHours: Int = 1,
    val backfillDays: Int = 30,
    val measures: Boolean = true,
    val sleep: Boolean = true,
    val activity: Boolean = false,
)

/** Per data-category cursors (Withings `lastupdate`, epoch seconds) plus the last run's outcome. */
data class SyncState(
    val measuresCursor: Long? = null,
    val sleepCursor: Long? = null,
    val activityCursor: Long? = null,
    /** Withings measure groups written to Health Connect: group id -> measurement time (epoch seconds). */
    val writtenMeasureGroups: Map<Long, Long> = emptyMap(),
    val lastRunAt: Long? = null,
    val lastRunOk: Boolean = false,
    val lastRunSummary: String? = null,
    /** Withings rejected the refresh token: the user has to log in again. Cleared by a new login. */
    val needsLogin: Boolean = false,
)

data class AppSettings(
    val credentials: WithingsCredentials,
    val tokens: WithingsTokens?,
    val options: SyncOptions,
    val state: SyncState,
) {
    val withingsConnected: Boolean get() = tokens != null
}

class SettingsRepository(private val context: Context) {

    // Decrypting secrets touches the Keystore, so keep it off the main thread.
    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }.flowOn(Dispatchers.Default)

    suspend fun current(): AppSettings = settings.first()

    suspend fun saveCredentials(credentials: WithingsCredentials) {
        context.dataStore.edit {
            it[CLIENT_ID] = credentials.clientId.trim()
            it[CLIENT_SECRET] = SecretBox.encrypt(credentials.clientSecret.trim())
            it[REDIRECT_URI] = credentials.redirectUri.trim()
        }
    }

    suspend fun saveTokens(tokens: WithingsTokens) {
        context.dataStore.edit {
            it[ACCESS_TOKEN] = SecretBox.encrypt(tokens.accessToken)
            it[REFRESH_TOKEN] = SecretBox.encrypt(tokens.refreshToken)
            it[TOKEN_EXPIRES_AT] = tokens.expiresAt
            it[USER_ID] = tokens.userId
            it.remove(NEEDS_LOGIN)
        }
    }

    suspend fun clearTokens() {
        context.dataStore.edit {
            it.remove(ACCESS_TOKEN)
            it.remove(REFRESH_TOKEN)
            it.remove(TOKEN_EXPIRES_AT)
            it.remove(USER_ID)
            it.remove(NEEDS_LOGIN)
        }
    }

    suspend fun saveOptions(options: SyncOptions) {
        context.dataStore.edit {
            it[INTERVAL_HOURS] = options.intervalHours
            it[BACKFILL_DAYS] = options.backfillDays
            it[SYNC_MEASURES] = options.measures
            it[SYNC_SLEEP] = options.sleep
            it[SYNC_ACTIVITY] = options.activity
        }
    }

    suspend fun saveCursors(measures: Long?, sleep: Long?, activity: Long?) {
        context.dataStore.edit {
            measures?.let { v -> it[MEASURES_CURSOR] = v }
            sleep?.let { v -> it[SLEEP_CURSOR] = v }
            activity?.let { v -> it[ACTIVITY_CURSOR] = v }
        }
    }

    /** Also forgets which measurements were written: a refetch writes them again, a new user has others. */
    suspend fun resetCursors() {
        context.dataStore.edit {
            it.remove(MEASURES_CURSOR)
            it.remove(SLEEP_CURSOR)
            it.remove(ACTIVITY_CURSOR)
            it.remove(WRITTEN_MEASURE_GROUPS)
        }
    }

    suspend fun saveWrittenMeasureGroups(groups: Map<Long, Long>) {
        context.dataStore.edit {
            it[WRITTEN_MEASURE_GROUPS] = groups.entries.joinToString(",") { (id, time) -> "$id:$time" }
        }
    }

    /** [needsLogin] null leaves the flag as it was (e.g. a network error says nothing about the login). */
    suspend fun saveRunResult(at: Long, ok: Boolean, summary: String, needsLogin: Boolean?) {
        context.dataStore.edit {
            it[LAST_RUN_AT] = at
            it[LAST_RUN_OK] = ok
            it[LAST_RUN_SUMMARY] = summary
            needsLogin?.let { v -> it[NEEDS_LOGIN] = v }
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val credentials = WithingsCredentials(
            clientId = this[CLIENT_ID] ?: BuildConfig.WITHINGS_CLIENT_ID,
            clientSecret = this[CLIENT_SECRET]?.let(SecretBox::decrypt) ?: BuildConfig.WITHINGS_CLIENT_SECRET,
            redirectUri = this[REDIRECT_URI]
                ?: BuildConfig.WITHINGS_REDIRECT_URI.ifBlank { WithingsApi.DEFAULT_REDIRECT_URI },
        )
        val access = this[ACCESS_TOKEN]?.let(SecretBox::decrypt)
        val refresh = this[REFRESH_TOKEN]?.let(SecretBox::decrypt)
        val tokens = if (access != null && refresh != null) {
            WithingsTokens(access, refresh, this[TOKEN_EXPIRES_AT] ?: 0L, this[USER_ID].orEmpty())
        } else {
            null
        }
        val defaults = SyncOptions()
        return AppSettings(
            credentials = credentials,
            tokens = tokens,
            options = SyncOptions(
                intervalHours = this[INTERVAL_HOURS] ?: defaults.intervalHours,
                backfillDays = this[BACKFILL_DAYS] ?: defaults.backfillDays,
                measures = this[SYNC_MEASURES] ?: defaults.measures,
                sleep = this[SYNC_SLEEP] ?: defaults.sleep,
                activity = this[SYNC_ACTIVITY] ?: defaults.activity,
            ),
            state = SyncState(
                measuresCursor = this[MEASURES_CURSOR],
                sleepCursor = this[SLEEP_CURSOR],
                activityCursor = this[ACTIVITY_CURSOR],
                writtenMeasureGroups = parseGroups(this[WRITTEN_MEASURE_GROUPS]),
                lastRunAt = this[LAST_RUN_AT],
                lastRunOk = this[LAST_RUN_OK] ?: false,
                lastRunSummary = this[LAST_RUN_SUMMARY],
                needsLogin = this[NEEDS_LOGIN] ?: false,
            ),
        )
    }

    private fun parseGroups(value: String?): Map<Long, Long> =
        value.orEmpty().split(',').mapNotNull { entry ->
            val id = entry.substringBefore(':').toLongOrNull()
            val time = entry.substringAfter(':', "").toLongOrNull()
            if (id != null && time != null) id to time else null
        }.toMap()

    private companion object {
        val CLIENT_ID = stringPreferencesKey("withings_client_id")
        val CLIENT_SECRET = stringPreferencesKey("withings_client_secret_enc")
        val REDIRECT_URI = stringPreferencesKey("withings_redirect_uri")
        val ACCESS_TOKEN = stringPreferencesKey("withings_access_token_enc")
        val REFRESH_TOKEN = stringPreferencesKey("withings_refresh_token_enc")
        val TOKEN_EXPIRES_AT = longPreferencesKey("withings_token_expires_at")
        val USER_ID = stringPreferencesKey("withings_user_id")
        val INTERVAL_HOURS = intPreferencesKey("sync_interval_hours")
        val BACKFILL_DAYS = intPreferencesKey("sync_backfill_days")
        val SYNC_MEASURES = booleanPreferencesKey("sync_measures")
        val SYNC_SLEEP = booleanPreferencesKey("sync_sleep")
        val SYNC_ACTIVITY = booleanPreferencesKey("sync_activity")
        val MEASURES_CURSOR = longPreferencesKey("cursor_measures")
        val SLEEP_CURSOR = longPreferencesKey("cursor_sleep")
        val ACTIVITY_CURSOR = longPreferencesKey("cursor_activity")
        val WRITTEN_MEASURE_GROUPS = stringPreferencesKey("written_measure_groups")
        val LAST_RUN_AT = longPreferencesKey("last_run_at")
        val LAST_RUN_OK = booleanPreferencesKey("last_run_ok")
        val LAST_RUN_SUMMARY = stringPreferencesKey("last_run_summary")
        val NEEDS_LOGIN = booleanPreferencesKey("withings_needs_login")
    }
}
