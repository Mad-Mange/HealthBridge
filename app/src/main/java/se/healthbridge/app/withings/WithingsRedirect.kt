package se.healthbridge.app.withings

import java.net.URI
import java.net.URLDecoder

/** Result of the OAuth redirect: either a code or an error from Withings. */
data class WithingsRedirect(val code: String?, val state: String?, val error: String?) {

    companion object {
        /**
         * True if [url] points at the registered redirect URI (scheme, host and path must match;
         * trailing slashes and the query string are ignored).
         */
        fun matches(url: String, redirectUri: String): Boolean {
            val actual = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            val expected = runCatching { URI(redirectUri.trim()) }.getOrNull() ?: return false
            return actual.scheme.equals(expected.scheme, ignoreCase = true) &&
                actual.host.equals(expected.host, ignoreCase = true) &&
                actual.path.orEmpty().trimEnd('/') == expected.path.orEmpty().trimEnd('/')
        }

        /** Parses `...?code=X&state=Y` (or `?error=...`). Returns null if there is no query at all. */
        fun parse(url: String): WithingsRedirect? {
            val query = runCatching { URI(url.trim()).rawQuery }.getOrNull() ?: return null
            val params = query.split('&')
                .filter { it.isNotEmpty() }
                .associate { part ->
                    val key = part.substringBefore('=')
                    val value = part.substringAfter('=', "")
                    decode(key) to decode(value)
                }
            return WithingsRedirect(
                code = params["code"]?.takeIf { it.isNotBlank() },
                state = params["state"],
                error = params["error"]?.takeIf { it.isNotBlank() },
            )
        }

        private fun decode(s: String): String = URLDecoder.decode(s, Charsets.UTF_8.name())
    }
}
