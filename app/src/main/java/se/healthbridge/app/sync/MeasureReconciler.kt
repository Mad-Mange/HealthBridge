package se.healthbridge.app.sync

import se.healthbridge.app.withings.MeasureGroup

/**
 * Keeps recent measurements in Health Connect in step with Withings. The incremental fetch only
 * returns measurements that were added or changed, never ones that were deleted in Withings or
 * assigned to another user, so each run compares the recent ones with the groups written earlier.
 */
object MeasureReconciler {

    /** [written]: measure group id -> measurement time (epoch seconds), to store for the next run. */
    data class Plan(val write: List<MeasureGroup>, val remove: Set<Long>, val written: Map<Long, Long>)

    /**
     * [recent] is everything Withings has for measurements taken between [start] and [end]. Groups in
     * [written] from that period that are missing from it, or no longer syncable, are removed.
     * Syncable groups that weren't written yet (e.g. a weigh-in the user has just assigned to
     * themselves) are written. Groups from before [start] are forgotten.
     */
    fun plan(written: Map<Long, Long>, recent: List<MeasureGroup>, start: Long, end: Long): Plan {
        val syncable = recent.filter { it.isSyncable }
        val present = syncable.mapTo(HashSet()) { it.id }
        val remove = written.filterTo(LinkedHashMap()) { (id, time) -> time > start && time <= end && id !in present }.keys
        val write = syncable.filter { it.id !in written }
        val kept = written.filter { (id, time) -> time > start && id !in remove }
        return Plan(write, remove, kept + write.associate { it.id to it.date })
    }
}
