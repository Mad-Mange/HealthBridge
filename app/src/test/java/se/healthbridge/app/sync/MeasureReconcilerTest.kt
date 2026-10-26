package se.healthbridge.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import se.healthbridge.app.withings.Measure
import se.healthbridge.app.withings.MeasureGroup
import se.healthbridge.app.withings.MeasureType

class MeasureReconcilerTest {
    private val start = 1_000_000L
    private val end = 4_000_000L

    private fun group(id: Long, date: Long, attrib: Int = 0, category: Int = 1) = MeasureGroup(
        id = id, attrib = attrib, date = date, modified = date, category = category,
        deviceId = null, model = "Body+", timezone = null, measures = listOf(Measure(MeasureType.WEIGHT, 80.0)),
    )

    @Test
    fun `nothing changes when Withings has what was written`() {
        val written = mapOf(1L to 2_000_000L, 2L to 3_000_000L)
        val plan = MeasureReconciler.plan(written, listOf(group(1, 2_000_000), group(2, 3_000_000)), start, end)
        assertEquals(emptyList<MeasureGroup>(), plan.write)
        assertEquals(emptySet<Long>(), plan.remove)
        assertEquals(written, plan.written)
    }

    @Test
    fun `a measurement deleted in Withings is removed and forgotten`() {
        val plan = MeasureReconciler.plan(mapOf(1L to 2_000_000L, 2L to 3_000_000L), listOf(group(2, 3_000_000)), start, end)
        assertEquals(setOf(1L), plan.remove)
        assertEquals(mapOf(2L to 3_000_000L), plan.written)
    }

    @Test
    fun `a written measurement that became ambiguous or a goal is removed`() {
        val written = mapOf(1L to 2_000_000L, 2L to 3_000_000L)
        val recent = listOf(group(1, 2_000_000, attrib = 1), group(2, 3_000_000, category = 2))
        assertEquals(setOf(1L, 2L), MeasureReconciler.plan(written, recent, start, end).remove)
    }

    @Test
    fun `an ambiguous measurement is written once the user assigns it`() {
        val waiting = MeasureReconciler.plan(emptyMap(), listOf(group(5, 2_500_000, attrib = 1)), start, end)
        assertEquals(emptyList<MeasureGroup>(), waiting.write)
        assertEquals(emptyMap<Long, Long>(), waiting.written)

        val assigned = MeasureReconciler.plan(waiting.written, listOf(group(5, 2_500_000, attrib = 0)), start, end)
        assertEquals(listOf(5L), assigned.write.map { it.id })
        assertEquals(mapOf(5L to 2_500_000L), assigned.written)
    }

    @Test
    fun `measurements outside the window are neither removed nor kept`() {
        val written = mapOf(1L to start, 2L to start - 10, 3L to end + 10)
        val plan = MeasureReconciler.plan(written, emptyList(), start, end)
        assertEquals("only the window is compared", emptySet<Long>(), plan.remove)
        assertEquals("older ones are forgotten, later ones kept", mapOf(3L to end + 10), plan.written)
    }
}
