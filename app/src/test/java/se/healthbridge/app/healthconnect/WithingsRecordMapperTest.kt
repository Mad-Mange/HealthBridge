package se.healthbridge.app.healthconnect

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.healthbridge.app.withings.DailyActivity
import se.healthbridge.app.withings.Measure
import se.healthbridge.app.withings.MeasureGroup
import se.healthbridge.app.withings.MeasureType
import se.healthbridge.app.withings.SleepSegment
import se.healthbridge.app.withings.SleepSummary
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class WithingsRecordMapperTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")

    private fun group(vararg measures: Pair<Int, Double>, attrib: Int = 0, id: Long = 1) = MeasureGroup(
        id = id, attrib = attrib, date = 1_760_000_000, modified = 1_760_000_100, category = 1,
        deviceId = "d", model = "Body+", timezone = "Europe/Stockholm",
        measures = measures.map { (type, value) -> Measure(type, value) },
    )

    @Test
    fun `weigh-in becomes weight and body fat with stable ids`() {
        val mapped = WithingsRecordMapper.measureGroups(
            listOf(group(MeasureType.WEIGHT to 80.25, MeasureType.FAT_RATIO to 18.0, id = 7)),
            stockholm,
        )
        assertEquals(0, mapped.skipped)
        val weight = mapped.records.filterIsInstance<WeightRecord>().single()
        assertEquals(80.25, weight.weight.inKilograms, 1e-9)
        assertEquals(ZoneOffset.ofHours(2), weight.zoneOffset) // CEST on 2025-10-09
        assertEquals("withings:meas:7:weight", weight.metadata.clientRecordId)
        assertEquals(1_760_000_100L, weight.metadata.clientRecordVersion)
        assertEquals(Device.TYPE_SCALE, weight.metadata.device?.type)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, weight.metadata.recordingMethod)
        assertEquals(18.0, mapped.records.filterIsInstance<BodyFatRecord>().single().percentage.value, 1e-9)
    }

    @Test
    fun `manual entries are marked as manual`() {
        val mapped = WithingsRecordMapper.measureGroups(listOf(group(MeasureType.WEIGHT to 80.0, attrib = 2)), stockholm)
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, mapped.records.single().metadata.recordingMethod)
    }

    @Test
    fun `blood pressure needs both values and keeps pulse`() {
        val mapped = WithingsRecordMapper.measureGroups(
            listOf(group(MeasureType.SYSTOLIC_BP to 121.0, MeasureType.DIASTOLIC_BP to 79.0, MeasureType.HEART_PULSE to 61.0)),
            stockholm,
        )
        val bp = mapped.records.filterIsInstance<BloodPressureRecord>().single()
        assertEquals(121.0, bp.systolic.inMillimetersOfMercury, 1e-9)
        assertEquals(79.0, bp.diastolic.inMillimetersOfMercury, 1e-9)
        assertEquals(61L, mapped.records.filterIsInstance<HeartRateRecord>().single().samples.single().beatsPerMinute)

        val onlySystolic = WithingsRecordMapper.measureGroups(listOf(group(MeasureType.SYSTOLIC_BP to 121.0)), stockholm)
        assertTrue(onlySystolic.records.isEmpty())
    }

    @Test
    fun `out of range values are skipped instead of failing the batch`() {
        val mapped = WithingsRecordMapper.measureGroups(
            listOf(group(MeasureType.WEIGHT to 80.0, MeasureType.FAT_RATIO to 250.0)),
            stockholm,
        )
        assertEquals(1, mapped.skipped)
        assertEquals(1, mapped.records.size)
    }

    @Test
    fun `only tracker activity is mapped and today ends now`() {
        val now = Instant.parse("2026-10-21T10:00:00Z")
        val days = listOf(
            DailyActivity("2026-10-20", "Europe/Stockholm", 9000, 7000.0, 300.0, 2200.0, isTracker = true, deviceId = null, modified = null),
            DailyActivity("2026-10-21", "Europe/Stockholm", 2000, null, null, null, isTracker = true, deviceId = null, modified = null),
            DailyActivity("2026-10-21", "Europe/Stockholm", 5000, null, null, null, isTracker = false, deviceId = null, modified = null),
        )
        val mapped = WithingsRecordMapper.activities(days, now, stockholm)
        val steps = mapped.records.filterIsInstance<StepsRecord>()
        assertEquals(listOf(9000L, 2000L), steps.map { it.count })
        assertEquals(Instant.parse("2026-10-19T22:00:00Z"), steps[0].startTime)
        assertEquals(Instant.parse("2026-10-20T22:00:00Z"), steps[0].endTime)
        assertEquals(now, steps[1].endTime)
        assertEquals(4 + 1, mapped.records.size)
    }

    @Test
    fun `sleep stages are clamped, sorted and de-overlapped`() {
        val summary = SleepSummary(id = 5, start = 1000, end = 5000, timezone = "Europe/Stockholm", model = 32, modified = 6000)
        val segments = listOf(
            SleepSegment(3000, 5500, 3), // ends after the session
            SleepSegment(500, 2000, 0), // starts before the session
            SleepSegment(1800, 3200, 2), // overlaps both neighbours
        )
        val record = WithingsRecordMapper.sleep(summary, segments, stockholm) as SleepSessionRecord
        assertNotNull(record)
        val stages = record.stages
        assertEquals(
            listOf(
                Triple(1000L, 2000L, SleepSessionRecord.STAGE_TYPE_AWAKE),
                Triple(2000L, 3200L, SleepSessionRecord.STAGE_TYPE_DEEP),
                Triple(3200L, 5000L, SleepSessionRecord.STAGE_TYPE_REM),
            ),
            stages.map { Triple(it.startTime.epochSecond, it.endTime.epochSecond, it.stage) },
        )
        assertEquals("withings:sleep:5", record.metadata.clientRecordId)
    }
}
