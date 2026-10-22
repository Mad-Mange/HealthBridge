package se.healthbridge.app.withings

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/** Pure JSON -> model parsing so it can be unit tested without the network. */
object WithingsParser {

    /** Unwraps `{status, body}`; throws on a non-zero status. */
    fun unwrap(json: JSONObject): JSONObject {
        val status = json.optInt("status", -1)
        if (status != 0) {
            val error = json.optString("error").ifBlank { "status $status" }
            throw if (status in AUTH_ERROR_STATUSES) {
                WithingsAuthException("Withings: $error", status)
            } else {
                WithingsException("Withings: $error", status)
            }
        }
        return json.optJSONObject("body") ?: JSONObject()
    }

    fun parseTokens(body: JSONObject, now: Long): WithingsTokens {
        val accessToken = body.optString("access_token")
        val refreshToken = body.optString("refresh_token")
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            throw WithingsException("Withings returned no token")
        }
        return WithingsTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = now + body.optLong("expires_in", 10_800L),
            userId = body.opt("userid")?.toString().orEmpty(),
        )
    }

    fun parseMeasureGroups(body: JSONObject): Page<MeasureGroup> {
        val groups = body.optJSONArray("measuregrps").objects().map { grp ->
            MeasureGroup(
                id = grp.optLong("grpid"),
                attrib = grp.optInt("attrib"),
                date = grp.optLong("date"),
                modified = grp.optLong("modified", grp.optLong("date")),
                category = grp.optInt("category", 1),
                deviceId = grp.optStringOrNull("deviceid"),
                model = grp.optStringOrNull("model"),
                timezone = grp.optStringOrNull("timezone") ?: body.optStringOrNull("timezone"),
                measures = grp.optJSONArray("measures").objects().map { m ->
                    Measure(
                        type = m.optInt("type"),
                        value = m.optLong("value") * 10.0.pow(m.optInt("unit")),
                    )
                },
            )
        }
        return Page(groups, body.optMore(), body.optInt("offset"))
    }

    fun parseActivities(body: JSONObject): Page<DailyActivity> {
        val activities = body.optJSONArray("activities").objects().map { a ->
            DailyActivity(
                date = a.optString("date"),
                timezone = a.optStringOrNull("timezone"),
                steps = a.optLongOrNull("steps"),
                distanceMeters = a.optDoubleOrNull("distance"),
                activeCalories = a.optDoubleOrNull("calories"),
                totalCalories = a.optDoubleOrNull("totalcalories"),
                isTracker = a.optBoolean("is_tracker", false),
                deviceId = a.optStringOrNull("deviceid"),
                modified = a.optLongOrNull("modified"),
            )
        }
        return Page(activities, body.optMore(), body.optInt("offset"))
    }

    fun parseSleepSummaries(body: JSONObject): Page<SleepSummary> {
        val series = body.optJSONArray("series").objects().map { s ->
            val start = s.optLong("startdate")
            SleepSummary(
                id = s.optLongOrNull("id") ?: start,
                start = start,
                end = s.optLong("enddate"),
                timezone = s.optStringOrNull("timezone"),
                model = s.optLongOrNull("model")?.toInt(),
                modified = s.optLong("modified", s.optLong("enddate")),
            )
        }
        return Page(series, body.optMore(), body.optInt("offset"))
    }

    fun parseSleepSegments(body: JSONObject): List<SleepSegment> =
        body.optJSONArray("series").objects().map { s ->
            SleepSegment(start = s.optLong("startdate"), end = s.optLong("enddate"), state = s.optInt("state", 5))
        }

    // 100-102 and 200: invalid/expired token or signature, 283: token invalid or missing,
    // 343: access token invalid (newer docs), 401: missing/invalid auth header.
    private val AUTH_ERROR_STATUSES = setOf(100, 101, 102, 200, 283, 343, 401)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    // Withings returns `more` as a boolean on some endpoints and 0/1 on others.
    private fun JSONObject.optMore(): Boolean {
        val more = opt("more")
        return more == true || (more is Number && more.toInt() != 0)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else opt(key)?.toString()?.takeIf { it.isNotBlank() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key)) null else (opt(key) as? Number)?.toLong()

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else (opt(key) as? Number)?.toDouble()
}
