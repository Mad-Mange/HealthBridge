package se.healthbridge.app.sync

import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.healthbridge.app.R
import se.healthbridge.app.data.SettingsRepository
import se.healthbridge.app.data.SyncLog
import se.healthbridge.app.data.SyncOptions
import se.healthbridge.app.healthconnect.HealthConnectRepository
import se.healthbridge.app.healthconnect.SourceFilter
import se.healthbridge.app.withings.WithingsApi
import se.healthbridge.app.withings.WithingsCredentials
import se.healthbridge.app.withings.WithingsTokens
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

/**
 * Runs a real sync against a fake Withings server and the device's real Health Connect.
 * Emulator only: it overwrites the app's Withings settings. Grant all permissions in the app first.
 */
@RunWith(AndroidJUnit4::class)
class SyncEngineInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val healthConnect = HealthConnectRepository(context)
    private val settings = SettingsRepository(context)
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()

    private val now = Instant.now().epochSecond
    private val weighInAt = now - 2 * 3600
    private val bpAt = now - 3 * 3600
    private val sleepStart = now - 9 * 3600
    private val sleepEnd = now - 3600

    @Volatile private var weightGrams = 80_450
    @Volatile private var weightModified = weighInAt

    @Before
    fun setUp() = runBlocking {
        assumeTrue("Emulator only (overwrites settings)", Build.HARDWARE == "ranchu")
        assumeTrue("Health Connect missing", healthConnect.isAvailable())
        assumeTrue(
            "Grant all Health Connect permissions in the app first",
            healthConnect.grantedPermissions().containsAll(HealthConnectRepository.PERMISSIONS),
        )
        deleteTestRecords()
        server.dispatcher = FakeWithings()
        server.start()
        settings.saveCredentials(WithingsCredentials("cid", "secret", "https://example.com/healthbridge"))
        settings.saveTokens(WithingsTokens("expired-access", "refresh-1", expiresAt = now - 10, userId = "42"))
        settings.saveOptions(SyncOptions(intervalHours = 1, backfillDays = 7, measures = true, sleep = true, activity = false))
        settings.resetCursors()
    }

    @After
    fun tearDown() = runBlocking {
        server.close()
        if (Build.HARDWARE == "ranchu") {
            deleteTestRecords()
            settings.clearTokens()
            settings.resetCursors()
        }
    }

    @Test
    fun syncWritesWithingsDataOnceAndUpdatesInPlace() = runBlocking {
        val api = WithingsApi(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        val engine = SyncEngine(context, settings, api, healthConnect, SyncLog(context))

        val first = engine.sync()
        assertEquals(first.summary, SyncEngine.Outcome.SUCCESS, first.outcome)
        assertEquals("expired token must be refreshed first", "/v2/oauth2 requesttoken", requests.first())
        val tokens = settings.current().tokens!!
        assertEquals("access-2", tokens.accessToken)
        assertEquals("rotated refresh token must be stored", "refresh-2", tokens.refreshToken)
        assertNotNull(settings.current().state.measuresCursor)
        val weight = context.getString(R.string.type_weight)
        val sleepLabel = context.getString(R.string.type_sleep)
        assertTrue(first.summary, first.summary.contains("$weight 1") && first.summary.contains("$sleepLabel 1"))

        // Re-fetch everything with a corrected weight: same records must be updated, not duplicated.
        weightGrams = 79_900
        weightModified = weighInAt + 100
        settings.resetCursors()
        val second = engine.sync()
        assertEquals(second.summary, SyncEngine.Outcome.SUCCESS, second.outcome)

        val weights = readTestRecords(WeightRecord::class)
        assertEquals(1, weights.size)
        assertEquals(79.9, weights.single().weight.inKilograms, 1e-6)
        assertEquals(1, readTestRecords(BodyFatRecord::class).size)
        val bp = readTestRecords(BloodPressureRecord::class).single()
        assertEquals(124.0, bp.systolic.inMillimetersOfMercury, 1e-6)
        assertEquals(1, readTestRecords(HeartRateRecord::class).size)
        val sleep = readTestRecords(SleepSessionRecord::class).single()
        assertEquals(
            listOf(SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM),
            sleep.stages.map { it.stage },
        )

        // The data screen and CSV export see the same data.
        val snapshots = healthConnect.latestSnapshots(SourceFilter.WITHINGS)
        val weightTitle = context.getString(R.string.metric_weight)
        val bpTitle = context.getString(R.string.metric_blood_pressure)
        assertTrue(snapshots.toString(), snapshots.any { it.title == weightTitle && it.value == "79.9 kg" })
        assertTrue(snapshots.toString(), snapshots.any { it.title == bpTitle && it.value == "124/81 mmHg" })
        val csv = ByteArrayOutputStream()
        healthConnect.exportCsv(csv, days = 1, filter = SourceFilter.WITHINGS)
        assertTrue(csv.toString().contains("weight,"))
    }

    @Test
    fun rejectedRefreshTokenAsksForNewLogin() = runBlocking {
        settings.saveTokens(WithingsTokens("expired-access", "revoked", expiresAt = now - 10, userId = "42"))
        val api = WithingsApi(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        val result = SyncEngine(context, settings, api, healthConnect, SyncLog(context)).sync()

        // The fake answers an unknown refresh token like Withings does: status 503 "Invalid Params".
        assertEquals(result.summary, SyncEngine.Outcome.FAILED, result.outcome)
        assertTrue(result.needsLogin)
        assertTrue(settings.current().state.needsLogin)
        assertEquals("no data calls with a dead login", listOf("/v2/oauth2 requesttoken"), requests.toList())

        settings.saveTokens(WithingsTokens("access-2", "refresh-2", expiresAt = now + 3600, userId = "42"))
        assertFalse("a new login clears the flag", settings.current().state.needsLogin)
    }

    private suspend fun <T : Record> readTestRecords(type: KClass<T>): List<T> {
        val client = HealthConnectClient.getOrCreate(context)
        val range = TimeRangeFilter.between(Instant.now().minus(Duration.ofDays(1)), Instant.now().plusSeconds(60))
        return client.readRecords(ReadRecordsRequest(type, range, setOf(DataOrigin(context.packageName))))
            .records.filter { it.metadata.clientRecordId.orEmpty() in TEST_IDS.getValue(type) }
    }

    private suspend fun deleteTestRecords() {
        val client = HealthConnectClient.getOrCreate(context)
        TEST_IDS.forEach { (type, ids) -> client.deleteRecords(type, emptyList(), ids) }
    }

    private inner class FakeWithings : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val form = request.body?.utf8().orEmpty().split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val path = request.url.encodedPath
            requests += "$path ${form["action"]}"
            val body = when {
                path == "/v2/oauth2" && form["refresh_token"] == "refresh-1" && form["client_secret"] == "secret" ->
                    """{"status":0,"body":{"userid":"42","access_token":"access-2","refresh_token":"refresh-2","expires_in":10800}}"""
                path == "/v2/oauth2" -> """{"status":503,"error":"Invalid Params"}"""
                request.headers["Authorization"] != "Bearer access-2" -> """{"status":401,"error":"invalid_token"}"""
                path == "/measure" -> """
                    {"status":0,"body":{"timezone":"Europe/Stockholm","more":0,"offset":0,"measuregrps":[
                     {"grpid":900001,"attrib":0,"date":$weighInAt,"modified":$weightModified,"category":1,"model":"Body+",
                      "measures":[{"value":$weightGrams,"type":1,"unit":-3},{"value":205,"type":6,"unit":-1}]},
                     {"grpid":900002,"attrib":0,"date":$bpAt,"modified":$bpAt,"category":1,"model":"BPM Connect",
                      "measures":[{"value":124,"type":10,"unit":0},{"value":81,"type":9,"unit":0},{"value":63,"type":11,"unit":0}]}]}}
                """.trimIndent()
                path == "/v2/sleep" && form["action"] == "getsummary" -> """
                    {"status":0,"body":{"more":false,"offset":0,"series":[
                     {"id":900003,"startdate":$sleepStart,"enddate":$sleepEnd,"timezone":"Europe/Stockholm","model":32,"modified":$sleepEnd}]}}
                """.trimIndent()
                path == "/v2/sleep" && form["action"] == "get" -> """
                    {"status":0,"body":{"series":[
                     {"startdate":$sleepStart,"enddate":${sleepStart + 3600},"state":1},
                     {"startdate":${sleepStart + 3600},"enddate":${sleepStart + 7200},"state":2},
                     {"startdate":${sleepStart + 7200},"enddate":$sleepEnd,"state":3}]}}
                """.trimIndent()
                else -> """{"status":2555,"error":"unexpected $path"}"""
            }
            return MockResponse.Builder().code(200).body(body).build()
        }
    }

    private companion object {
        val TEST_IDS: Map<KClass<out Record>, List<String>> = mapOf(
            WeightRecord::class to listOf("withings:meas:900001:weight"),
            BodyFatRecord::class to listOf("withings:meas:900001:fat"),
            BloodPressureRecord::class to listOf("withings:meas:900002:bp"),
            HeartRateRecord::class to listOf("withings:meas:900002:hr"),
            SleepSessionRecord::class to listOf("withings:sleep:900003"),
        )
    }
}
