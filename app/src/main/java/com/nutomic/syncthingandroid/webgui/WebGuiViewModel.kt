package com.nutomic.syncthingandroid.webgui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.ConfigXml
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.net.URL
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the web GUI screen shows over the WebView: the loading overlay, or why loading failed. */
internal data class WebGuiLoadState(val loading: Boolean = true, val error: String? = null)

/**
 * The web GUI's configuration, the certificate it is pinned to and the load state of its page.
 * Syncthing comes from [SyncthingConnection]. The WebView itself stays in the activity.
 */
@HiltViewModel
internal class WebGuiViewModel
@Inject
constructor(@ApplicationContext context: Context, connection: SyncthingConnection) : ViewModel() {

    val config: ConfigXml = loadConfiguration(context)

    /**
     * Web GUI URL pinned to the loopback interface. The WebView only ever talks to the local
     * Syncthing instance, so we connect on 127.0.0.1 regardless of the configured GUI listen
     * address (which is 0.0.0.0 when remote access is enabled). This also keeps the WebView within
     * the loopback domain-config in network_security_config.xml that trusts user-installed CAs.
     */
    val webGuiUrl: URL = toLoopback(config.webGuiUrl)

    /** The local instance's certificate; null if its file is missing. */
    val caCertificate: X509Certificate? = loadCaCertificate(context)

    /** Whether Syncthing runs, so the GUI can load. */
    val serviceActive: StateFlow<Boolean> =
        connection.snapshot
            .map { it.state == SyncthingService.State.ACTIVE }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val state = MutableStateFlow(WebGuiLoadState())
    val loadState: StateFlow<WebGuiLoadState> = state.asStateFlow()

    /** A new WebView starts loading from scratch. */
    fun onWebViewCreated() {
        state.value = WebGuiLoadState()
    }

    fun onPageFinished() {
        state.value = state.value.copy(loading = false)
    }

    /** Shows [error] while the page is still loading; later errors are ignored. */
    fun onLoadError(error: String) {
        if (state.value.loading) {
            state.value = state.value.copy(error = error)
        }
    }
}

private fun loadCaCertificate(context: Context): X509Certificate? {
    val httpsCertFile = Constants.getHttpsCertFile(context)
    if (!httpsCertFile.exists()) {
        return null
    }
    try {
        return FileInputStream(httpsCertFile).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
    } catch (e: FileNotFoundException) {
        throw IllegalArgumentException("Untrusted Certificate", e)
    } catch (e: CertificateException) {
        throw IllegalArgumentException("Untrusted Certificate", e)
    }
}
