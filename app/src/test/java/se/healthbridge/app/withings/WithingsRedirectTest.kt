package se.healthbridge.app.withings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WithingsRedirectTest {
    private val redirect = "https://example.com/healthbridge"

    @Test
    fun `matches ignores query and trailing slash`() {
        assertTrue(WithingsRedirect.matches("https://example.com/healthbridge?code=abc&state=x", redirect))
        assertTrue(WithingsRedirect.matches("https://EXAMPLE.com/healthbridge/?code=abc", redirect))
        assertFalse(WithingsRedirect.matches("https://example.com/other?code=abc", redirect))
        assertFalse(WithingsRedirect.matches("https://account.withings.com/oauth2_user/authorize2?redirect_uri=$redirect", redirect))
        assertFalse(WithingsRedirect.matches("not a url", redirect))
    }

    @Test
    fun `parses code and state`() {
        val r = WithingsRedirect.parse("https://example.com/healthbridge?code=a%2Bb&state=s1")!!
        assertEquals("a+b", r.code)
        assertEquals("s1", r.state)
        assertNull(r.error)
    }

    @Test
    fun `parses error`() {
        val r = WithingsRedirect.parse("https://example.com/healthbridge?error=access_denied&state=s1")!!
        assertNull(r.code)
        assertEquals("access_denied", r.error)
    }

    @Test
    fun `no query gives null`() {
        assertNull(WithingsRedirect.parse("https://example.com/healthbridge"))
    }
}
