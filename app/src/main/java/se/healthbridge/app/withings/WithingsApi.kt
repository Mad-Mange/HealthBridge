package se.healthbridge.app.withings

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * Minimal client for the Withings public API (OAuth 2.0).
 * Docs: https://developer.withings.com/api-reference
 */
class WithingsApi(
    private val http: OkHttpClient,
    private val apiBase: String = API_BASE,
) {

    fun authorizeUrl(credentials: WithingsCredentials, state: String): String =
        AUTHORIZE_URL.toUri().buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", credentials.clientId)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("redirect_uri", credentials.redirectUri)
            .appendQueryParameter("state", state)
            .build()
            .toString()

    /** Authorization codes are only valid for ~30 seconds, so call this right after the redirect. */
    suspend fun exchangeCode(credentials: WithingsCredentials, code: String): WithingsTokens =
        requestToken(
            credentials,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to credentials.redirectUri,
            ),
        )

    /** Withings rotates refresh tokens: always store the returned refresh token. */
    suspend fun refresh(credentials: WithingsCredentials, refreshToken: String): WithingsTokens =
        requestToken(
            credentials,
            mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
        )

    /** Measures added or changed since [lastUpdate]. Deleted measures are simply missing. */
    suspend fun getMeasures(accessToken: String, lastUpdate: Long, offset: Int): Page<MeasureGroup> =
        measures(accessToken, mapOf("lastupdate" to lastUpdate.toString()), offset)

    /** Measures taken between [start] and [end], to find the ones that were deleted in Withings. */
    suspend fun getMeasuresTakenBetween(accessToken: String, start: Long, end: Long, offset: Int): Page<MeasureGroup> =
        measures(accessToken, mapOf("startdate" to start.toString(), "enddate" to end.toString()), offset)

    private suspend fun measures(accessToken: String, range: Map<String, String>, offset: Int): Page<MeasureGroup> {
        val params = mutableMapOf(
            "action" to "getmeas",
            "meastypes" to MeasureType.SYNCED.joinToString(","),
            "category" to "1",
        )
        params += range
        if (offset > 0) params["offset"] = offset.toString()
        return WithingsParser.parseMeasureGroups(post("/measure", params, accessToken))
    }

    suspend fun getActivity(accessToken: String, lastUpdate: Long, offset: Int): Page<DailyActivity> {
        val params = mutableMapOf(
            "action" to "getactivity",
            "lastupdate" to lastUpdate.toString(),
            "data_fields" to "steps,distance,calories,totalcalories",
        )
        if (offset > 0) params["offset"] = offset.toString()
        return WithingsParser.parseActivities(post("/v2/measure", params, accessToken))
    }

    suspend fun getSleepSummaries(accessToken: String, lastUpdate: Long, offset: Int): Page<SleepSummary> {
        val params = mutableMapOf(
            "action" to "getsummary",
            "lastupdate" to lastUpdate.toString(),
            "data_fields" to "total_sleep_time",
        )
        if (offset > 0) params["offset"] = offset.toString()
        return WithingsParser.parseSleepSummaries(post("/v2/sleep", params, accessToken))
    }

    /** Sleep stages for one night. Withings returns at most 24 h per call. */
    suspend fun getSleepSegments(accessToken: String, start: Long, end: Long): List<SleepSegment> {
        val params = mapOf(
            "action" to "get",
            "startdate" to start.toString(),
            "enddate" to end.toString(),
        )
        return WithingsParser.parseSleepSegments(post("/v2/sleep", params, accessToken))
    }

    private suspend fun requestToken(
        credentials: WithingsCredentials,
        params: Map<String, String>,
    ): WithingsTokens {
        val body = post(
            "/v2/oauth2",
            params + mapOf(
                "action" to "requesttoken",
                "client_id" to credentials.clientId,
                "client_secret" to credentials.clientSecret,
            ),
            accessToken = null,
        )
        return WithingsParser.parseTokens(body, System.currentTimeMillis() / 1000)
    }

    private suspend fun post(path: String, params: Map<String, String>, accessToken: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder()
                .url(apiBase + path)
                .post(form)
                .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw WithingsException("HTTP ${response.code} from Withings")
                }
                val json = try {
                    JSONObject(text)
                } catch (e: JSONException) {
                    throw WithingsException("Unexpected response from Withings: ${text.take(120)}")
                }
                WithingsParser.unwrap(json)
            }
        }

    companion object {
        const val AUTHORIZE_URL = "https://account.withings.com/oauth2_user/authorize2"
        const val API_BASE = "https://wbsapi.withings.net"
        const val SCOPES = "user.info,user.metrics,user.activity"
        // Withings may check that the callback answers 200; example.com's root does, sub-paths are 404.
        const val DEFAULT_REDIRECT_URI = "https://example.com/"
        const val DEVELOPER_DASHBOARD_URL = "https://developer.withings.com/dashboard/"
    }
}
