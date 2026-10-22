package se.healthbridge.app.healthconnect

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.healthbridge.app.R
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.reflect.KClass

/** A "latest value" card on the data screen. */
data class MetricSnapshot(val title: String, val value: String, val time: Instant, val sources: List<String>)

enum class SourceFilter(@StringRes val label: Int) {
    ALL(R.string.source_all),
    SAMSUNG(R.string.source_samsung),
    WITHINGS(R.string.source_withings),
}

class HealthConnectRepository(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    suspend fun grantedPermissions(): Set<String> =
        if (isAvailable()) client.permissionController.getGrantedPermissions() else emptySet()

    /** Opens Health Connect's page for this app (permissions + the data we wrote). */
    fun manageDataIntent(): Intent = HealthConnectClient.getHealthConnectManageDataIntent(context)

    data class InsertResult(val written: Int, val deniedTypes: Set<KClass<out Record>>)

    /** Inserts records the user has granted write access for; the rest are reported back, not thrown. */
    suspend fun insert(records: List<Record>): InsertResult {
        if (records.isEmpty()) return InsertResult(0, emptySet())
        val granted = grantedPermissions()
        val (allowed, denied) = records.partition { HealthPermission.getWritePermission(it::class) in granted }
        allowed.chunked(INSERT_CHUNK).forEach { client.insertRecords(it) }
        return InsertResult(allowed.size, denied.map { it::class }.toSet())
    }

    /** Latest values per data type, optionally only from one source app. Must run in the foreground. */
    suspend fun latestSnapshots(filter: SourceFilter): List<MetricSnapshot> {
        val granted = grantedPermissions()
        val origins = originsFor(filter)
        val now = Instant.now()
        val recent = TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now)
        val today = TimeRangeFilter.between(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant(), now)
        val out = mutableListOf<MetricSnapshot>()

        suspend fun <T : Record> latest(type: KClass<T>): T? {
            if (HealthPermission.getReadPermission(type) !in granted) return null
            return runCatching {
                client.readRecords(ReadRecordsRequest(type, recent, origins, ascendingOrder = false, pageSize = 1))
                    .records.firstOrNull()
            }.getOrNull()
        }
        fun Record.source() = listOf(metadata.dataOrigin.packageName)

        if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
            runCatching {
                val result = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL),
                        timeRangeFilter = today,
                        dataOriginFilter = origins,
                    ),
                )
                val steps = result[StepsRecord.COUNT_TOTAL]
                if (steps != null) {
                    val km = result[DistanceRecord.DISTANCE_TOTAL]?.inKilometers
                    val value = if (km != null) "$steps  ·  ${fmt(km, 1)} km" else "$steps"
                    out += MetricSnapshot(text(R.string.metric_steps_today), value, now, result.dataOrigins.map { it.packageName })
                }
            }
        }
        latest(SleepSessionRecord::class)?.let {
            val d = Duration.between(it.startTime, it.endTime)
            val value = text(R.string.duration_hours_minutes, d.toHours(), d.toMinutes() % 60)
            out += MetricSnapshot(text(R.string.metric_sleep), value, it.endTime, it.source())
        }
        latest(WeightRecord::class)?.let {
            out += MetricSnapshot(text(R.string.metric_weight), "${fmt(it.weight.inKilograms, 1)} kg", it.time, it.source())
        }
        latest(BodyFatRecord::class)?.let {
            out += MetricSnapshot(text(R.string.metric_body_fat), "${fmt(it.percentage.value, 1)} %", it.time, it.source())
        }
        latest(BloodPressureRecord::class)?.let {
            val value = "${it.systolic.inMillimetersOfMercury.toInt()}/${it.diastolic.inMillimetersOfMercury.toInt()} mmHg"
            out += MetricSnapshot(text(R.string.metric_blood_pressure), value, it.time, it.source())
        }
        latest(HeartRateRecord::class)?.let { rec ->
            rec.samples.maxByOrNull { it.time }?.let {
                out += MetricSnapshot(text(R.string.metric_heart_rate), "${it.beatsPerMinute} bpm", it.time, rec.source())
            }
        }
        latest(RestingHeartRateRecord::class)?.let {
            out += MetricSnapshot(text(R.string.metric_resting_heart_rate), "${it.beatsPerMinute} bpm", it.time, it.source())
        }
        latest(OxygenSaturationRecord::class)?.let {
            out += MetricSnapshot(text(R.string.metric_spo2), "${fmt(it.percentage.value, 0)} %", it.time, it.source())
        }
        latest(ExerciseSessionRecord::class)?.let {
            val d = Duration.between(it.startTime, it.endTime)
            val title = it.title?.takeIf { t -> t.isNotBlank() } ?: text(R.string.exercise_default_title)
            out += MetricSnapshot(text(R.string.metric_exercise), "$title · ${d.toMinutes()} min", it.endTime, it.source())
        }
        latest(BodyTemperatureRecord::class)?.let {
            out += MetricSnapshot(text(R.string.metric_body_temperature), "${fmt(it.temperature.inCelsius, 1)} °C", it.time, it.source())
        }
        latest(Vo2MaxRecord::class)?.let {
            out += MetricSnapshot(text(R.string.metric_vo2max), "${fmt(it.vo2MillilitersPerMinuteKilogram, 1)} ml/kg/min", it.time, it.source())
        }
        return out
    }

    /** Writes all readable data from the last [days] days as CSV. Returns the number of rows. */
    suspend fun exportCsv(out: OutputStream, days: Int, filter: SourceFilter): Int {
        val granted = grantedPermissions()
        val now = Instant.now()
        val range = TimeRangeFilter.between(now.minus(Duration.ofDays(days.toLong())), now)
        val origins = originsFor(filter)
        var rows = 0
        withContext(Dispatchers.IO) {
            val writer = out.bufferedWriter(Charsets.UTF_8)
            writer.write("type,start,end,value,unit,source\n")
            fun row(type: String, start: Instant, end: Instant?, value: Any, unit: String, source: String) {
                writer.write(listOf(type, start.toString(), end?.toString().orEmpty(), value.toString(), unit, source).joinToString(",") { csv(it) })
                writer.write("\n")
                rows++
            }
            for (exporter in exporters()) {
                if (HealthPermission.getReadPermission(exporter.type) !in granted) continue
                var pageToken: String? = null
                do {
                    val response = client.readRecords(
                        ReadRecordsRequest(exporter.type, range, origins, ascendingOrder = true, pageSize = 1000, pageToken = pageToken),
                    )
                    response.records.forEach { exporter.write(it, ::row) }
                    pageToken = response.pageToken
                } while (pageToken != null)
            }
            writer.flush()
        }
        return rows
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun originsFor(filter: SourceFilter): Set<DataOrigin> = when (filter) {
        SourceFilter.ALL -> emptySet()
        SourceFilter.SAMSUNG -> setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE))
        SourceFilter.WITHINGS -> setOf(DataOrigin(context.packageName), DataOrigin(WITHINGS_PACKAGE))
    }

    private class Exporter<T : Record>(
        val type: KClass<T>,
        private val rows: (T, row: (String, Instant, Instant?, Any, String, String) -> Unit) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        fun write(record: Record, row: (String, Instant, Instant?, Any, String, String) -> Unit) = rows(record as T, row)
    }

    private fun exporters(): List<Exporter<*>> = listOf(
        Exporter(StepsRecord::class) { r, row -> row("steps", r.startTime, r.endTime, r.count, "count", r.src()) },
        Exporter(DistanceRecord::class) { r, row -> row("distance", r.startTime, r.endTime, fmt(r.distance.inMeters, 1), "m", r.src()) },
        Exporter(ActiveCaloriesBurnedRecord::class) { r, row -> row("active_calories", r.startTime, r.endTime, fmt(r.energy.inKilocalories, 1), "kcal", r.src()) },
        Exporter(TotalCaloriesBurnedRecord::class) { r, row -> row("total_calories", r.startTime, r.endTime, fmt(r.energy.inKilocalories, 1), "kcal", r.src()) },
        Exporter(WeightRecord::class) { r, row -> row("weight", r.time, null, fmt(r.weight.inKilograms, 2), "kg", r.src()) },
        Exporter(BodyFatRecord::class) { r, row -> row("body_fat", r.time, null, fmt(r.percentage.value, 1), "%", r.src()) },
        Exporter(LeanBodyMassRecord::class) { r, row -> row("lean_body_mass", r.time, null, fmt(r.mass.inKilograms, 2), "kg", r.src()) },
        Exporter(BoneMassRecord::class) { r, row -> row("bone_mass", r.time, null, fmt(r.mass.inKilograms, 2), "kg", r.src()) },
        Exporter(BodyWaterMassRecord::class) { r, row -> row("body_water_mass", r.time, null, fmt(r.mass.inKilograms, 2), "kg", r.src()) },
        Exporter(HeightRecord::class) { r, row -> row("height", r.time, null, fmt(r.height.inMeters, 2), "m", r.src()) },
        Exporter(BasalMetabolicRateRecord::class) { r, row -> row("bmr", r.time, null, fmt(r.basalMetabolicRate.inKilocaloriesPerDay, 0), "kcal/day", r.src()) },
        Exporter(BloodPressureRecord::class) { r, row ->
            row("blood_pressure", r.time, null, "${fmt(r.systolic.inMillimetersOfMercury, 0)}/${fmt(r.diastolic.inMillimetersOfMercury, 0)}", "mmHg", r.src())
        },
        Exporter(HeartRateRecord::class) { r, row -> r.samples.forEach { s -> row("heart_rate", s.time, null, s.beatsPerMinute, "bpm", r.src()) } },
        Exporter(RestingHeartRateRecord::class) { r, row -> row("resting_heart_rate", r.time, null, r.beatsPerMinute, "bpm", r.src()) },
        Exporter(OxygenSaturationRecord::class) { r, row -> row("oxygen_saturation", r.time, null, fmt(r.percentage.value, 1), "%", r.src()) },
        Exporter(BodyTemperatureRecord::class) { r, row -> row("body_temperature", r.time, null, fmt(r.temperature.inCelsius, 2), "C", r.src()) },
        Exporter(Vo2MaxRecord::class) { r, row -> row("vo2_max", r.time, null, fmt(r.vo2MillilitersPerMinuteKilogram, 1), "ml/kg/min", r.src()) },
        Exporter(SleepSessionRecord::class) { r, row ->
            row("sleep_session", r.startTime, r.endTime, Duration.between(r.startTime, r.endTime).toMinutes(), "min", r.src())
            r.stages.forEach { s -> row("sleep_stage", s.startTime, s.endTime, s.stage, "stage", r.src()) }
        },
        Exporter(ExerciseSessionRecord::class) { r, row ->
            row("exercise", r.startTime, r.endTime, r.title ?: r.exerciseType.toString(), "type", r.src())
        },
    )

    private fun Record.src() = metadata.dataOrigin.packageName

    companion object {
        const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
        const val WITHINGS_PACKAGE = "com.withings.wiscale2"
        private const val INSERT_CHUNK = 500

        /** Types the Withings sync writes. */
        val WRITE_TYPES: List<KClass<out Record>> = listOf(
            WeightRecord::class, HeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class,
            BoneMassRecord::class, BodyWaterMassRecord::class, BasalMetabolicRateRecord::class,
            BloodPressureRecord::class, HeartRateRecord::class, OxygenSaturationRecord::class,
            BodyTemperatureRecord::class, Vo2MaxRecord::class, StepsRecord::class, DistanceRecord::class,
            ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class, SleepSessionRecord::class,
        )

        /** Types shown on the data screen / exported (includes Samsung Health-only types). */
        val READ_TYPES: List<KClass<out Record>> =
            WRITE_TYPES + listOf(RestingHeartRateRecord::class, ExerciseSessionRecord::class)

        val PERMISSIONS: Set<String> =
            WRITE_TYPES.map { HealthPermission.getWritePermission(it) }.toSet() +
                READ_TYPES.map { HealthPermission.getReadPermission(it) }

        fun sourceName(context: Context, packageName: String): String = when (packageName) {
            SAMSUNG_HEALTH_PACKAGE -> context.getString(R.string.source_samsung)
            context.packageName -> context.getString(R.string.source_name_self)
            WITHINGS_PACKAGE -> context.getString(R.string.source_name_withings_app)
            "nl.appyhapps.healthsync" -> "Health Sync"
            "com.google.android.apps.fitness" -> "Google Fit"
            "com.google.android.apps.healthdata", "com.android.healthconnect.controller" -> "Health Connect"
            else -> packageName
        }

        /** Short lower-case label for a record type, used in sync summaries ("weight 3, sleep 1"). */
        fun label(context: Context, type: KClass<out Record>): String = when (type) {
            WeightRecord::class -> context.getString(R.string.type_weight)
            HeightRecord::class -> context.getString(R.string.type_height)
            BodyFatRecord::class -> context.getString(R.string.type_body_fat)
            LeanBodyMassRecord::class -> context.getString(R.string.type_lean_mass)
            BoneMassRecord::class -> context.getString(R.string.type_bone_mass)
            BodyWaterMassRecord::class -> context.getString(R.string.type_body_water)
            BasalMetabolicRateRecord::class -> context.getString(R.string.type_bmr)
            BloodPressureRecord::class -> context.getString(R.string.type_blood_pressure)
            HeartRateRecord::class -> context.getString(R.string.type_heart_rate)
            OxygenSaturationRecord::class -> context.getString(R.string.type_spo2)
            BodyTemperatureRecord::class -> context.getString(R.string.type_temperature)
            Vo2MaxRecord::class -> context.getString(R.string.type_vo2max)
            StepsRecord::class -> context.getString(R.string.type_steps)
            DistanceRecord::class -> context.getString(R.string.type_distance)
            ActiveCaloriesBurnedRecord::class -> context.getString(R.string.type_active_kcal)
            TotalCaloriesBurnedRecord::class -> context.getString(R.string.type_total_kcal)
            SleepSessionRecord::class -> context.getString(R.string.type_sleep)
            else -> type.simpleName.orEmpty()
        }

        private fun fmt(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

        private fun csv(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
