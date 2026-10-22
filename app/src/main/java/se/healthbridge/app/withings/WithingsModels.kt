package se.healthbridge.app.withings

import java.io.IOException

/** OAuth tokens for one Withings user. Times are epoch seconds. */
data class WithingsTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
) {
    fun expiresWithin(seconds: Long, now: Long): Boolean = expiresAt - now <= seconds
}

/** Developer app credentials from developer.withings.com. */
data class WithingsCredentials(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
) {
    val isComplete: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank() && redirectUri.isNotBlank()
}

/** One page of a paginated Withings list (`more` + `offset`). */
data class Page<T>(val items: List<T>, val more: Boolean, val offset: Int)

/** A single measure, already scaled (`value * 10^unit`). */
data class Measure(val type: Int, val value: Double)

/** A group of measures taken at the same moment by the same device (e.g. one weigh-in). */
data class MeasureGroup(
    val id: Long,
    val attrib: Int,
    val date: Long,
    val modified: Long,
    val category: Int,
    val deviceId: String?,
    val model: String?,
    val timezone: String?,
    val measures: List<Measure>,
) {
    fun value(type: Int): Double? = measures.firstOrNull { it.type == type }?.value

    /** Withings attrib 2 and 4 mean the user typed the value in by hand. */
    val isManualEntry: Boolean get() = attrib == 2 || attrib == 4
}

/** Daily activity aggregate (`date` is yyyy-MM-dd in `timezone`). */
data class DailyActivity(
    val date: String,
    val timezone: String?,
    val steps: Long?,
    val distanceMeters: Double?,
    val activeCalories: Double?,
    val totalCalories: Double?,
    val isTracker: Boolean,
    val deviceId: String?,
    val modified: Long?,
)

data class SleepSummary(
    val id: Long,
    val start: Long,
    val end: Long,
    val timezone: String?,
    val model: Int?,
    val modified: Long,
)

/** Withings sleep state: 0 awake, 1 light, 2 deep, 3 REM, 4 manual, 5 unspecified. */
data class SleepSegment(val start: Long, val end: Long, val state: Int)

/** Withings measure type ids (https://developer.withings.com/api-reference/#tag/measure). */
object MeasureType {
    const val WEIGHT = 1
    const val HEIGHT = 4
    const val FAT_FREE_MASS = 5
    const val FAT_RATIO = 6
    const val FAT_MASS = 8
    const val DIASTOLIC_BP = 9
    const val SYSTOLIC_BP = 10
    const val HEART_PULSE = 11
    const val TEMPERATURE = 12
    const val SPO2 = 54
    const val BODY_TEMPERATURE = 71
    const val MUSCLE_MASS = 76
    const val HYDRATION = 77
    const val BONE_MASS = 88
    const val VO2_MAX = 123
    const val BASAL_METABOLIC_RATE = 226

    /** Types we know how to map to Health Connect. */
    val SYNCED = listOf(
        WEIGHT, HEIGHT, FAT_FREE_MASS, FAT_RATIO, DIASTOLIC_BP, SYSTOLIC_BP, HEART_PULSE,
        TEMPERATURE, SPO2, BODY_TEMPERATURE, HYDRATION, BONE_MASS, VO2_MAX, BASAL_METABOLIC_RATE,
    )
}

open class WithingsException(message: String, val status: Int? = null) : IOException(message)

/** The token is invalid or revoked; the user has to log in again (or a refresh is needed). */
class WithingsAuthException(message: String, status: Int? = null) : WithingsException(message, status)
