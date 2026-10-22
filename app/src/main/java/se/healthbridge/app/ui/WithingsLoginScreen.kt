package se.healthbridge.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import se.healthbridge.app.withings.WithingsRedirect

/**
 * Withings' login page in a WebView. Navigation to the registered callback URL is intercepted
 * before it loads, so the callback doesn't have to be a page anyone actually hosts.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithingsLoginScreen(login: LoginRequest, onRedirect: (String) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    var loading by remember { mutableStateOf(true) }
    val currentOnRedirect by rememberUpdatedState(onRedirect)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logga in på Withings") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Avbryt") }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        // Without MATCH_PARENT the WebView treats its height as wrap_content and
                        // pages styled with `height: 100%` (like Withings' login) collapse to 0.
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        var handled = false
                        fun intercept(url: String): Boolean {
                            if (!WithingsRedirect.matches(url, login.redirectUri)) return false
                            if (!handled) {
                                handled = true
                                currentOnRedirect(url)
                            }
                            return true
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                intercept(request.url.toString())

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (intercept(url)) view.stopLoading() else loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                            }
                        }
                        loadUrl(login.url)
                    }
                },
                onRelease = { it.destroy() },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
