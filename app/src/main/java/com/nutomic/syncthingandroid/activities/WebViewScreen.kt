package com.nutomic.syncthingandroid.activities

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nutomic.syncthingandroid.R

/**
 * A web page in a WebView, behind a loading message until it has loaded, with the certificate
 * notice when the page's certificate is invalid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebViewScreen(
    title: String,
    url: String,
    loading: Boolean,
    showSslNotice: Boolean,
    canOpenInBrowser: Boolean,
    onSslNoticeResult: (Boolean) -> Unit,
    onOpenInBrowser: () -> Unit,
    onNavigateBack: () -> Unit,
    webViewFactory: (Context) -> WebView,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (canOpenInBrowser) {
                        IconButton(onClick = onOpenInBrowser) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.open_in_browser),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = webViewFactory,
                modifier = Modifier.fillMaxSize().testTag("webview"),
            )
            if (loading) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.web_page_loading, url),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
    if (showSslNotice) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.security_notice)) },
            text = { Text(stringResource(R.string.ssl_cert_invalid_text, url)) },
            confirmButton = {
                TextButton(onClick = { onSslNoticeResult(true) }) {
                    Text(stringResource(R.string.cont))
                }
            },
            dismissButton = {
                TextButton(onClick = { onSslNoticeResult(false) }) {
                    Text(stringResource(R.string.cancel_title))
                }
            },
        )
    }
}
