package se.healthbridge.app.healthconnect

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import se.healthbridge.app.withings.DailyActivity
import se.healthbridge.app.withings.MeasureGroup
import se.healthbridge.app.withings.MeasureType
import se.healthbridge.app.withings.SleepSegment
import se.healthbridge.app.withings.SleepSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Maps Withings data to Health Connect records.
 *
 * Every record gets a stable `clientRecordId` so re-syncing the same Withings data updates the
 * existing Health Connect record instead of creating duplicates. Values Health Connect would reject
 * (out of range) are dropped and reported in [Mapped.skipped].
 */
object WithingsRecordMapper {

    data class Mapped(val records: List<Record>, val skipped: Int)

    // Last part of `withings:meas:<grpid>:<suffix>`. Never change one: it identifies records already written.
    private val MEASURE_SUFFIXES: Map<KClass<out Record>, String> = mapOf(
        WeightRecord::class to "weight", HeightRecord::class to "height", BodyFatRecord::class to "fat",
        LeanBodyMassRecord::class to "lean", BoneMassRecord::class to "bone", BodyWaterMassRecord::class to "water",
        BasalMetabolicRateRecord::class to "bmr", BloodPressureRecord::class to "bp", HeartRateRecord::class to "hr",
        OxygenSaturationRecord::class to "spo2", BodyTemperatureRecord::class to "temp", Vo2MaxRecord::class to "vo2max",
    )

    fun measureRecordId(groupId: Long, type: KClass<out Record>): String =
        "withings:meas:$groupId:${MEASURE_SUFFIXES.getValue(type)}"

    /** Client record ids of everything the given measure groups can have produced, per record type. */
    fun measureRecordIds(groupIds: Collection<Long>): Map<KClass<out Record>, List<String>> =
        if (groupIds.isEmpty()) emptyMap()
        else MEASURE_SUFFIXES.keys.associateWith { type -> groupIds.map { measureRecordId(it, type) } }

    /** Only [MeasureGroup.isSyncable] groups are mapped: goals and unassigned measurements are left out. */
    fun measureGroups(groups: List<MeasureGroup>, defaultZone: ZoneId = ZoneId.systemDefault()): Mapped {
        val records = mutableListOf<Record>()
        var skipped = 0
        fun add(build: () -> Record) {
            runCatching(build).onSuccess { records += it }.onFailure { skipped++ }
        }

        for (group in groups) {
            if (!group.isSyncable) continue
            val time = Instant.ofEpochSecond(group.date)
            val offset = zoneOf(group.timezone, defaultZone).rules.getOffset(time)
            val device = deviceFor(group)
            fun meta(type: KClass<out Record>) = metadataFor(group, device, measureRecordId(group.id, type))

            group.value(MeasureType.WEIGHT)?.let { v ->
                add { WeightRecord(time = time, zoneOffset = offset, weight = Mass.kilograms(v), metadata = meta(WeightRecord::class)) }
            }
            group.value(MeasureType.HEIGHT)?.let { v ->
                add { HeightRecord(time = time, zoneOffset = offset, height = Length.meters(v), metadata = meta(HeightRecord::class)) }
            }
            group.value(MeasureType.FAT_RATIO)?.let { v ->
                add { BodyFatRecord(time = time, zoneOffset = offset, percentage = Percentage(v), metadata = meta(BodyFatRecord::class)) }
            }
            group.value(MeasureType.FAT_FREE_MASS)?.let { v ->
                add { LeanBodyMassRecord(time = time, zoneOffset = offset, mass = Mass.kilograms(v), metadata = meta(LeanBodyMassRecord::class)) }
            }
            group.value(MeasureType.BONE_MASS)?.let { v ->
                add { BoneMassRecord(time = time, zoneOffset = offset, mass = Mass.kilograms(v), metadata = meta(BoneMassRecord::class)) }
            }
            group.value(MeasureType.HYDRATION)?.let { v ->
                add { BodyWaterMassRecord(time = time, zoneOffset = offset, mass = Mass.kilograms(v), metadata = meta(BodyWaterMassRecord::class)) }
            }
            group.value(MeasureType.BASAL_METABOLIC_RATE)?.let { v ->
                add {
                    BasalMetabolicRateRecord(
                        time = time, zoneOffset = offset,
                        basalMetabolicRate = Power.kilocaloriesPerDay(v), metadata = meta(BasalMetabolicRateRecord::class),
                    )
                }
            }
            val systolic = group.value(MeasureType.SYSTOLIC_BP)
            val diastolic = group.value(MeasureType.DIASTOLIC_BP)
            if (systolic != null && diastolic != null) {
                add {
                    BloodPressureRecord(
                        time = time, zoneOffset = offset, metadata = meta(BloodPressureRecord::class),
                        systolic = Pressure.millimetersOfMercury(systolic),
                        diastolic = Pressure.millimetersOfMercury(diastolic),
                    )
                }
            }
            group.value(MeasureType.HEART_PULSE)?.let { v ->
                add {
                    // A spot measurement; Health Connect wants an interval, so use one second.
                    HeartRateRecord(
                        startTime = time, startZoneOffset = offset,
                        endTime = time.plusSeconds(1), endZoneOffset = offset,
                        samples = listOf(HeartRateRecord.Sample(time = time, beatsPerMinute = Math.round(v))),
                        metadata = meta(HeartRateRecord::class),
                    )
                }
            }
            group.value(MeasureType.SPO2)?.let { v ->
                add { OxygenSaturationRecord(time = time, zoneOffset = offset, percentage = Percentage(v), metadata = meta(OxygenSaturationRecord::class)) }
            }
            (group.value(MeasureType.BODY_TEMPERATURE) ?: group.value(MeasureType.TEMPERATURE))?.let { v ->
                add {
                    BodyTemperatureRecord(
                        time = time, zoneOffset = offset, metadata = meta(BodyTemperatureRecord::class),
                        temperature = Temperature.celsius(v),
                        measurementLocation = BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_UNKNOWN,
                    )
                }
            }
            group.value(MeasureType.VO2_MAX)?.let { v ->
                add {
                    Vo2MaxRecord(
                        time = time, zoneOffset = offset, metadata = meta(Vo2MaxRecord::class),
                        vo2MillilitersPerMinuteKilogram = v,
                        measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER,
                    )
                }
            }
        }
        return Mapped(records, skipped)
    }

    /**
     * Daily totals become one record per day spanning local midnight to midnight (or "now" for today).
     * Only data from a Withings tracker is mapped: phone-collected steps in Withings may originate
     * from Health Connect itself and would otherwise be written back as duplicates.
     */
    fun activities(
        days: List<DailyActivity>,
        now: Instant,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): Mapped {
        val records = mutableListOf<Record>()
        var skipped = 0
        fun add(build: () -> Record) {
            runCatching(build).onSuccess { records += it }.onFailure { skipped++ }
        }

        for (day in days) {
            if (!day.isTracker) continue
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: continue
            val zone = zoneOf(day.timezone, defaultZone)
            val start = date.atStartOfDay(zone).toInstant()
            val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), now)
            if (!end.isAfter(start)) continue
            val startOffset = zone.rules.getOffset(start)
            val endOffset = zone.rules.getOffset(end)
            val device = Device(type = Device.TYPE_WATCH, manufacturer = "Withings", model = null)
            // Today's totals keep growing, so the version is the sync time to always overwrite.
            fun meta(suffix: String) = Metadata.autoRecorded(
                device = device,
                clientRecordId = "withings:act:${day.date}:$suffix",
                clientRecordVersion = now.epochSecond,
            )

            day.steps?.takeIf { it > 0 }?.let { v ->
                add { StepsRecord(startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset, count = v, metadata = meta("steps")) }
            }
            day.distanceMeters?.takeIf { it > 0 }?.let { v ->
                add { DistanceRecord(startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset, distance = Length.meters(v), metadata = meta("distance")) }
            }
            day.activeCalories?.takeIf { it > 0 }?.let { v ->
                add { ActiveCaloriesBurnedRecord(startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset, energy = Energy.kilocalories(v), metadata = meta("active_kcal")) }
            }
            day.totalCalories?.takeIf { it > 0 }?.let { v ->
                add { TotalCaloriesBurnedRecord(startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset, energy = Energy.kilocalories(v), metadata = meta("total_kcal")) }
            }
        }
        return Mapped(records, skipped)
    }

    fun sleep(
        summary: SleepSummary,
        segments: List<SleepSegment>,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): Record? {
        if (summary.end <= summary.start) return null
        val start = Instant.ofEpochSecond(summary.start)
        val end = Instant.ofEpochSecond(summary.end)
        val zone = zoneOf(summary.timezone, defaultZone)
        val device = Device(
            // Model 16 = activity tracker / watch; 32 = Sleep Analyzer (under-mattress).
            type = if (summary.model == 16) Device.TYPE_WATCH else Device.TYPE_UNKNOWN,
            manufacturer = "Withings",
            model = if (summary.model == 32) "Sleep Analyzer" else null,
        )
        return runCatching {
            SleepSessionRecord(
                startTime = start,
                startZoneOffset = zone.rules.getOffset(start),
                endTime = end,
                endZoneOffset = zone.rules.getOffset(end),
                title = "Withings",
                stages = sleepStages(start, end, segments),
                metadata = Metadata.autoRecorded(
                    device = device,
                    clientRecordId = "withings:sleep:${summary.id}",
                    clientRecordVersion = summary.modified,
                ),
            )
        }.getOrNull()
    }

    /** Clamps stages to the session, sorts them and trims overlaps (Health Connect rejects both). */
    fun sleepStages(start: Instant, end: Instant, segments: List<SleepSegment>): List<SleepSessionRecord.Stage> {
        val stages = mutableListOf<SleepSessionRecord.Stage>()
        var cursor = start
        for (segment in segments.sortedBy { it.start }) {
            val s = maxOf(Instant.ofEpochSecond(segment.start), cursor)
            val e = minOf(Instant.ofEpochSecond(segment.end), end)
            if (!e.isAfter(s)) continue
            stages += SleepSessionRecord.Stage(startTime = s, endTime = e, stage = stageFor(segment.state))
            cursor = e
        }
        return stages
    }

    fun stageFor(withingsState: Int): Int = when (withingsState) {
        0 -> SleepSessionRecord.STAGE_TYPE_AWAKE
        1 -> SleepSessionRecord.STAGE_TYPE_LIGHT
        2 -> SleepSessionRecord.STAGE_TYPE_DEEP
        3 -> SleepSessionRecord.STAGE_TYPE_REM
        else -> SleepSessionRecord.STAGE_TYPE_SLEEPING
    }

    private fun deviceFor(group: MeasureGroup): Device {
        val type = when {
            group.value(MeasureType.WEIGHT) != null || group.value(MeasureType.FAT_RATIO) != null -> Device.TYPE_SCALE
            group.value(MeasureType.VO2_MAX) != null || group.value(MeasureType.SPO2) != null -> Device.TYPE_WATCH
            else -> Device.TYPE_UNKNOWN
        }
        return Device(type = type, manufacturer = "Withings", model = group.model)
    }

    private fun metadataFor(group: MeasureGroup, device: Device, clientRecordId: String): Metadata =
        if (group.isManualEntry) {
            Metadata.manualEntry(clientRecordId = clientRecordId, clientRecordVersion = group.modified, device = device)
        } else {
            Metadata.autoRecorded(device = device, clientRecordId = clientRecordId, clientRecordVersion = group.modified)
        }

    private fun zoneOf(timezone: String?, fallback: ZoneId): ZoneId =
        timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallback
}
