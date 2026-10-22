package se.healthbridge.app.sync

import androidx.health.connect.client.records.Record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.healthbridge.app.data.AppSettings
import se.healthbridge.app.data.SettingsRepository
import se.healthbridge.app.data.SyncLog
import se.healthbridge.app.healthconnect.HealthConnectRepository
import se.healthbridge.app.healthconnect.WithingsRecordMapper
import se.healthbridge.app.withings.Page
import se.healthbridge.app.withings.WithingsApi
import se.healthbridge.app.withings.WithingsAuthException
import se.healthbridge.app.withings.WithingsCredentials
import se.healthbridge.app.withings.WithingsException
import se.healthbridge.app.withings.WithingsTokens
import java.io.IOException
import java.time.Instant
import kotlin.reflect.KClass

/**
 * One sync run: Withings -> Health Connect for the enabled categories.
 * Runs are serialized, so the periodic worker and "sync now" never overlap.
 */
class SyncEngine(
    private val settings: SettingsRepository,
    private val api: WithingsApi,
    private val healthConnect: HealthConnectRepository,
    private val log: SyncLog,
) {
    enum class Outcome { SUCCESS, SKIPPED, RETRY, FAILED }

    /** [needsLogin]: Withings rejected the login, so retrying won't help until the user logs in again. */
    data class Result(val outcome: Outcome, val summary: String, val needsLogin: Boolean = false)

    private val mutex = Mutex()
    private var lastFinishedAtMillis = 0L

    suspend fun sync(): Result = mutex.withLock {
        // Withings rejects identical requests less than 10 s apart ("Same arguments in less than
        // 10 seconds"), which happens when the periodic job and "sync now" run back to back.
        val wait = lastFinishedAtMillis + MIN_RUN_SPACING_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)

        val startedAt = Instant.now().epochSecond
        val result = try {
            run(settings.current(), startedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WithingsAuthException) {
            Result(Outcome.FAILED, "Withings-inloggningen gäller inte längre – logga in igen (${e.message})", needsLogin = true)
        } catch (e: WithingsException) {
            Result(Outcome.RETRY, "Tillfälligt fel, försöker igen senare (${e.message})")
        } catch (e: IOException) {
            Result(Outcome.RETRY, "Nätverksfel: ${e.message}")
        } catch (e: SecurityException) {
            Result(Outcome.FAILED, "Health Connect nekade åtkomst: ${e.message}")
        } catch (e: Exception) {
            Result(Outcome.FAILED, "Fel: ${e.message ?: e::class.simpleName}")
        }
        lastFinishedAtMillis = System.currentTimeMillis()

        when (result.outcome) {
            Outcome.SUCCESS -> log.info(result.summary)
            Outcome.SKIPPED -> log.warn("Synk hoppades över: ${result.summary}")
            Outcome.RETRY, Outcome.FAILED -> log.error(result.summary)
        }
        if (result.outcome != Outcome.SKIPPED) {
            val needsLogin = when {
                result.needsLogin -> true
                result.outcome == Outcome.SUCCESS -> false
                else -> null
            }
            settings.saveRunResult(startedAt, result.outcome == Outcome.SUCCESS, result.summary, needsLogin)
        }
        result
    }

    private suspend fun run(current: AppSettings, startedAt: Long): Result {
        if (!healthConnect.isAvailable()) return skipped("Health Connect är inte tillgängligt")
        if (!current.credentials.isComplete) return skipped("Withings Client ID/Secret saknas")
        val tokens = current.tokens ?: return skipped("Withings är inte anslutet")
        val options = current.options
        if (!options.measures && !options.sleep && !options.activity) return skipped("Inga datatyper valda")

        val session = Session(current.credentials, tokens)
        val backfillStart = startedAt - options.backfillDays * SECONDS_PER_DAY
        val tally = Tally()
        var measuresCursor: Long? = null
        var sleepCursor: Long? = null
        var activityCursor: Long? = null

        if (options.measures) {
            val since = current.state.measuresCursor ?: backfillStart
            val groups = fetchAll { offset -> session.call { api.getMeasures(it, since, offset) } }
            val mapped = WithingsRecordMapper.measureGroups(groups)
            tally.add(mapped.records, mapped.skipped, healthConnect.insert(mapped.records))
            measuresCursor = startedAt
        }

        if (options.sleep) {
            val since = current.state.sleepCursor ?: backfillStart
            val summaries = fetchAll { offset -> session.call { api.getSleepSummaries(it, since, offset) } }
            val records = summaries.mapIndexedNotNull { index, summary ->
                if (index > 0) delay(SLEEP_CALL_SPACING_MS) // stay under Withings' 120 requests/minute
                val segments = try {
                    session.call { api.getSleepSegments(it, summary.start, summary.end) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList() // still write the session, just without stages
                }
                WithingsRecordMapper.sleep(summary, segments)
            }
            tally.add(records, summaries.size - records.size, healthConnect.insert(records))
            sleepCursor = startedAt
        }

        if (options.activity) {
            val since = current.state.activityCursor ?: backfillStart
            val days = fetchAll { offset -> session.call { api.getActivity(it, since, offset) } }
            val mapped = WithingsRecordMapper.activities(days, Instant.ofEpochSecond(startedAt))
            tally.add(mapped.records, mapped.skipped, healthConnect.insert(mapped.records))
            activityCursor = startedAt
        }

        settings.saveCursors(measuresCursor, sleepCursor, activityCursor)

        if (tally.denied.isNotEmpty()) {
            log.warn(
                "Saknar skrivbehörighet i Health Connect för: " +
                    tally.denied.joinToString { HealthConnectRepository.label(it) } +
                    ". Ge behörighet och välj \"Hämta om historik\" i Inställningar.",
            )
        }
        if (tally.skipped > 0) {
            log.warn("${tally.skipped} värden hoppades över (utanför Health Connects tillåtna intervall).")
        }
        return Result(Outcome.SUCCESS, tally.summary())
    }

    private fun skipped(reason: String) = Result(Outcome.SKIPPED, reason)

    /** Holds the current tokens and refreshes them before expiry or after an auth error. */
    private inner class Session(private val credentials: WithingsCredentials, private var tokens: WithingsTokens) {
        suspend fun <T> call(block: suspend (accessToken: String) -> T): T {
            if (tokens.expiresWithin(REFRESH_MARGIN_SECONDS, Instant.now().epochSecond)) refresh()
            return try {
                block(tokens.accessToken)
            } catch (e: WithingsAuthException) {
                refresh()
                block(tokens.accessToken)
            }
        }

        private suspend fun refresh() {
            tokens = try {
                api.refresh(credentials, tokens.refreshToken)
            } catch (e: WithingsException) {
                // Withings answers a revoked/expired refresh token with a generic status (e.g. 503
                // "Invalid Params"), so treat every API-level rejection except overload as "log in again".
                val status = e.status
                if (e is WithingsAuthException || status == null || status in TRANSIENT_STATUSES) throw e
                throw WithingsAuthException(e.message.orEmpty(), status)
            }
            settings.saveTokens(tokens) // Withings rotates the refresh token: persist immediately.
        }
    }

    private class Tally {
        val written = linkedMapOf<KClass<out Record>, Int>()
        val denied = linkedSetOf<KClass<out Record>>()
        var skipped = 0

        fun add(records: List<Record>, skipped: Int, result: HealthConnectRepository.InsertResult) {
            this.skipped += skipped
            denied += result.deniedTypes
            records.filter { it::class !in result.deniedTypes }.forEach {
                written[it::class] = (written[it::class] ?: 0) + 1
            }
        }

        fun summary(): String {
            val total = written.values.sum()
            val text = if (total == 0) {
                "Inget nytt från Withings"
            } else {
                "Skrev $total poster: " + written.entries.joinToString { (type, n) -> "${HealthConnectRepository.label(type)} $n" }
            }
            if (denied.isEmpty()) return text
            return "$text (saknar behörighet för ${denied.joinToString { HealthConnectRepository.label(it) }})"
        }
    }

    private suspend fun <T> fetchAll(fetch: suspend (offset: Int) -> Page<T>): List<T> {
        val items = mutableListOf<T>()
        var offset = 0
        repeat(MAX_PAGES) {
            val page = fetch(offset)
            items += page.items
            if (!page.more || page.offset <= offset) return items
            offset = page.offset
        }
        return items
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
        const val REFRESH_MARGIN_SECONDS = 300L
        const val SLEEP_CALL_SPACING_MS = 550L
        const val MAX_PAGES = 50
        const val MIN_RUN_SPACING_MS = 11_000L

        // 601 = too many requests, 2554-2556 = Withings-side errors; worth retrying later.
        val TRANSIENT_STATUSES = setOf(601, 2554, 2555, 2556)
    }
}
