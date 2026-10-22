package se.healthbridge.app.withings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WithingsParserTest {

    @Test
    fun `measure values are scaled by unit`() {
        val body = WithingsParser.unwrap(
            JSONObject(
                """
                {"status":0,"body":{"updatetime":1700000000,"timezone":"Europe/Stockholm","more":0,"offset":0,
                 "measuregrps":[{"grpid":42,"attrib":0,"date":1699990000,"created":1699990010,"modified":1699990020,
                   "category":1,"deviceid":"abc","model":"Body+","measures":[
                     {"value":72345,"type":1,"unit":-3},
                     {"value":215,"type":6,"unit":-1},
                     {"value":64,"type":11,"unit":0}]}]}}
                """.trimIndent(),
            ),
        )
        val page = WithingsParser.parseMeasureGroups(body)
        assertFalse(page.more)
        val group = page.items.single()
        assertEquals(42L, group.id)
        assertEquals(1699990020L, group.modified)
        assertEquals("Europe/Stockholm", group.timezone)
        assertEquals("Body+", group.model)
        assertEquals(72.345, group.value(MeasureType.WEIGHT)!!, 1e-9)
        assertEquals(21.5, group.value(MeasureType.FAT_RATIO)!!, 1e-9)
        assertEquals(64.0, group.value(MeasureType.HEART_PULSE)!!, 1e-9)
        assertNull(group.value(MeasureType.SYSTOLIC_BP))
        assertFalse(group.isManualEntry)
    }

    @Test
    fun `more flag accepts both boolean and number`() {
        val asNumber = WithingsParser.parseActivities(JSONObject("""{"activities":[],"more":1,"offset":10}"""))
        val asBool = WithingsParser.parseActivities(JSONObject("""{"activities":[],"more":true,"offset":10}"""))
        assertTrue(asNumber.more)
        assertTrue(asBool.more)
        assertEquals(10, asNumber.offset)
    }

    @Test
    fun `activities parse optional fields`() {
        val page = WithingsParser.parseActivities(
            JSONObject(
                """
                {"more":false,"offset":0,"activities":[
                  {"date":"2026-10-20","timezone":"Europe/Stockholm","deviceid":null,"is_tracker":true,
                   "steps":8123,"distance":6012.5,"calories":312.4,"totalcalories":2210.0,"modified":1760990000},
                  {"date":"2026-10-21","timezone":"Europe/Stockholm","is_tracker":false,"steps":100}]}
                """.trimIndent(),
            ),
        )
        val (tracker, phone) = page.items
        assertEquals(8123L, tracker.steps)
        assertEquals(6012.5, tracker.distanceMeters!!, 1e-9)
        assertNull(tracker.deviceId)
        assertTrue(tracker.isTracker)
        assertFalse(phone.isTracker)
        assertNull(phone.totalCalories)
    }

    @Test
    fun `token response computes expiry`() {
        val body = WithingsParser.unwrap(
            JSONObject(
                """{"status":0,"body":{"userid":"123","access_token":"a","refresh_token":"r","expires_in":10800,"scope":"user.metrics","token_type":"Bearer"}}""",
            ),
        )
        val tokens = WithingsParser.parseTokens(body, now = 1_000)
        assertEquals("a", tokens.accessToken)
        assertEquals("r", tokens.refreshToken)
        assertEquals(11_800L, tokens.expiresAt)
        assertEquals("123", tokens.userId)
        assertTrue(tokens.expiresWithin(300, now = 11_600))
        assertFalse(tokens.expiresWithin(300, now = 10_000))
    }

    @Test(expected = WithingsAuthException::class)
    fun `invalid token status is an auth error`() {
        WithingsParser.unwrap(JSONObject("""{"status":401,"error":"invalid_token"}"""))
    }

    @Test
    fun `other non-zero status is a plain error`() {
        try {
            WithingsParser.unwrap(JSONObject("""{"status":601,"error":"Too many requests"}"""))
        } catch (e: WithingsException) {
            assertFalse(e is WithingsAuthException)
            assertEquals(601, e.status)
            return
        }
        throw AssertionError("expected WithingsException")
    }

    @Test
    fun `sleep series parse`() {
        val summaries = WithingsParser.parseSleepSummaries(
            JSONObject("""{"series":[{"id":9,"startdate":100,"enddate":200,"timezone":"Europe/Stockholm","model":32,"modified":300}],"more":false,"offset":0}"""),
        )
        val s = summaries.items.single()
        assertEquals(9L, s.id)
        assertEquals(32, s.model)
        val segments = WithingsParser.parseSleepSegments(
            JSONObject("""{"series":[{"startdate":100,"enddate":150,"state":1},{"startdate":150,"enddate":200,"state":3}]}"""),
        )
        assertEquals(listOf(1, 3), segments.map { it.state })
    }
}
