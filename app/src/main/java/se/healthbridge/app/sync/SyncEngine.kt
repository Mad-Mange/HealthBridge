package se.healthbridge.app.sync

import android.content.Context
import androidx.annotation.StringRes
import androidx.health.connect.client.records.Record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.healthbridge.app.R
import se.healthbridge.app.data.AppSettings
import se.healthbridge.app.data.SettingsRepository
import se.healthbridge.app.data.SyncLog
import se.healthbridge.app.healthconnect.HealthConnectRepository
import se.healthbridge.app.healthconnect.WithingsRecordMapper
import se.healthbridge.app.withings.MeasureGroup
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
    private val context: Context,
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
            Result(Outcome.FAILED, text(R.string.sync_failed_login, e.message.orEmpty()), needsLogin = true)
        } catch (e: WithingsException) {
            Result(Outcome.RETRY, text(R.string.sync_retry_withings, e.message.orEmpty()))
        } catch (e: IOException) {
            Result(Outcome.RETRY, text(R.string.sync_retry_network, e.message.orEmpty()))
        } catch (e: SecurityException) {
            Result(Outcome.FAILED, text(R.string.sync_failed_hc, e.message.orEmpty()))
        } catch (e: Exception) {
            Result(Outcome.FAILED, text(R.string.sync_failed_other, e.message ?: e::class.simpleName.orEmpty()))
        }
        lastFinishedAtMillis = System.currentTimeMillis()

        when (result.outcome) {
            Outcome.SUCCESS -> log.info(result.summary)
            Outcome.SKIPPED -> log.warn(text(R.string.sync_skipped, result.summary))
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
        if (!healthConnect.isAvailable()) return skipped(R.string.skip_hc_unavailable)
        if (!current.credentials.isComplete) return skipped(R.string.skip_no_credentials)
        val tokens = current.tokens ?: return skipped(R.string.skip_not_connected)
        val options = current.options
        if (!options.measures && !options.sleep && !options.activity) return skipped(R.string.skip_nothing_selected)

        val session = Session(current.credentials, tokens)
        val backfillStart = startedAt - options.backfillDays * SECONDS_PER_DAY
        val tally = Tally()

        // Each category saves its cursor as soon as it's written, so a later failure doesn't fetch it again.
        if (options.measures) {
            val since = current.state.measuresCursor ?: backfillStart
            val groups = fetchAll { offset -> session.call { api.getMeasures(it, since, offset) } }
            writeMeasures(groups, tally)
            val written = current.state.writtenMeasureGroups + groups.filter { it.isSyncable }.associate { it.id to it.date }
            settings.saveCursors(measures = startedAt, sleep = null, activity = null)
            settings.saveWrittenMeasureGroups(written)

            // Never further back than the history the user asked for, so nothing older gets written.
            val start = startedAt - minOf(options.backfillDays.toLong(), RECHECK_DAYS) * SECONDS_PER_DAY
            val end = startedAt + SECONDS_PER_DAY // a device clock that runs ahead
            val recent = fetchAll { offset -> session.call { api.getMeasuresTakenBetween(it, start, end, offset) } }
            val plan = MeasureReconciler.plan(written, recent, start, end)
            healthConnect.deleteByClientIds(WithingsRecordMapper.measureRecordIds(plan.remove))
            writeMeasures(plan.write, tally)
            settings.saveWrittenMeasureGroups(plan.written)
            tally.removed += plan.remove.size
            tally.unassigned = recent.count { it.category == 1 && it.isAmbiguous }
        }

        if (options.sleep) {
            val since = current.state.sleepCursor ?: backfillStart
            val summaries = fetchAll { offset -> session.call { api.getSleepSummaries(it, since, offset) } }
            val records = summaries.mapIndexedNotNull { index, summary ->
                if (index > 0) delay(SLEEP_CALL_SPACING_MS) // stay under Withings' 120 requests/minute
                val segments = try {
                    session.call { api.getSleepSegments(it, summary.start, summary.end) }
                } catch (e: WithingsException) {
                    // A night Withings has no stages for is still written, just without them. Network,
                    // overload and login errors fail the run instead: the cursor stays put, so the
                    // night is fetched again with its stages on the next try.
                    if (e is WithingsAuthException || e.status == null || e.status in TRANSIENT_STATUSES) throw e
                    emptyList()
                }
                WithingsRecordMapper.sleep(summary, segments)
            }
            tally.add(records, summaries.size - records.size, healthConnect.insert(records))
            settings.saveCursors(measures = null, sleep = startedAt, activity = null)
        }

        if (options.activity) {
            val since = current.state.activityCursor ?: backfillStart
            val days = fetchAll { offset -> session.call { api.getActivity(it, since, offset) } }
            val mapped = WithingsRecordMapper.activities(days, Instant.ofEpochSecond(startedAt))
            tally.add(mapped.records, mapped.skipped, healthConnect.insert(mapped.records))
            settings.saveCursors(measures = null, sleep = null, activity = startedAt)
        }

        if (tally.denied.isNotEmpty()) {
            log.warn(text(R.string.sync_denied_warning, labels(tally.denied)))
        }
        if (tally.skipped > 0) {
            log.warn(context.resources.getQuantityString(R.plurals.sync_values_skipped, tally.skipped, tally.skipped))
        }
        return Result(Outcome.SUCCESS, tally.summary())
    }

    private suspend fun writeMeasures(groups: List<MeasureGroup>, tally: Tally) {
        val mapped = WithingsRecordMapper.measureGroups(groups)
        tally.add(mapped.records, mapped.skipped, healthConnect.insert(mapped.records))
    }

    private fun skipped(@StringRes reason: Int) = Result(Outcome.SKIPPED, text(reason))

    private fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun labels(types: Collection<KClass<out Record>>): String =
        types.joinToString { HealthConnectRepository.label(context, it) }

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

    private inner class Tally {
        val written = linkedMapOf<KClass<out Record>, Int>()
        val denied = linkedSetOf<KClass<out Record>>()
        var skipped = 0
        /** Measure groups removed because they're gone from Withings. */
        var removed = 0
        /** Measurements Withings is waiting for the user to assign. */
        var unassigned = 0

        fun add(records: List<Record>, skipped: Int, result: HealthConnectRepository.InsertResult) {
            this.skipped += skipped
            denied += result.deniedTypes
            records.filter { it::class !in result.deniedTypes }.forEach {
                written[it::class] = (written[it::class] ?: 0) + 1
            }
        }

        fun summary(): String {
            val total = written.values.sum()
            val wrote = when {
                total > 0 -> {
                    val parts = written.entries.joinToString { (type, n) -> "${HealthConnectRepository.label(context, type)} $n" }
                    context.resources.getQuantityString(R.plurals.sync_wrote, total, total, parts)
                }
                removed > 0 -> null
                else -> text(R.string.sync_nothing_new)
            }
            val summary = listOfNotNull(
                wrote,
                removed.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.sync_removed, it, it) },
                unassigned.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.sync_unassigned, it, it) },
            ).joinToString(". ")
            if (denied.isEmpty()) return summary
            return text(R.string.sync_no_permission_for, summary, labels(denied))
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
        /** How far back measurements deleted in Withings are also removed from Health Connect. */
        const val RECHECK_DAYS = 30L
        const val REFRESH_MARGIN_SECONDS = 300L
        const val SLEEP_CALL_SPACING_MS = 550L
        const val MAX_PAGES = 50
        const val MIN_RUN_SPACING_MS = 11_000L

        // 601 = too many requests, 2554-2556 = Withings-side errors; worth retrying later.
        val TRANSIENT_STATUSES = setOf(601, 2554, 2555, 2556)
    }
}
