package com.nutomic.syncthingandroid.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.nutomic.syncthingandroid.IoDispatcher
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingService.HttpsCertReplaceResult
import com.nutomic.syncthingandroid.util.CertificateValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Upper bound on a picked certificate or key file. Generous for PEM — a full chain is a few KiB —
 * while keeping both files small enough for the saved state, which travels over a Binder
 * transaction with a hard size limit shared process-wide.
 */
private const val MAX_PEM_BYTES = 64 * 1024

private const val CERT_NAME = "cert_name"
private const val CERT_BYTES = "cert_bytes"
private const val KEY_NAME = "key_name"
private const val KEY_BYTES = "key_bytes"
private const val FAILURE_OUTCOME = "failure_outcome"
private const val FAILURE_DETAIL = "failure_detail"

/**
 * A failed apply, kept on screen rather than shown as a toast. The reason matters here — "Syncthing
 * generated its own certificate instead" tells the user something actionable that a disappearing
 * "Could not apply the certificate" does not — and a toast cannot carry the help link.
 */
data class ApplyFailure(val outcome: HttpsCertReplaceResult, val detail: String?)

/** What the custom certificate screen shows. */
data class CustomCertificateUiState(
    val certName: String? = null,
    val keyName: String? = null,
    /** The checks on the picked pair, once both files are picked. */
    val validation: CertificateValidator.ValidationResult? = null,
    /** The certificate Syncthing uses now. */
    val currentInfo: CertificateValidator.CertInfo? = null,
    /** An apply or reset in flight. */
    val applying: Boolean = false,
    val applyFailure: ApplyFailure? = null,
)

/**
 * The custom certificate screen: the picked files and the last failure are saved, the validation
 * and the current certificate are worked out again, and an apply in flight survives a rotation.
 */
@HiltViewModel
class CustomCertificateViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    connection: SyncthingConnection,
    private val savedState: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SettingsViewModel(connection) {

    private val state =
        MutableStateFlow(
            CustomCertificateUiState(
                certName = savedState[CERT_NAME],
                keyName = savedState[KEY_NAME],
                applyFailure = restoreFailure(),
            )
        )
    val uiState: StateFlow<CustomCertificateUiState> = state.asStateFlow()

    private var validation: Job? = null

    init {
        viewModelScope.launch {
            val info = readCurrentCertificate()
            state.update { it.copy(currentInfo = info) }
        }
        validate()
    }

    fun onCertPicked(uri: Uri?) {
        val (name, bytes) = readPicked(uri) ?: return
        savedState[CERT_NAME] = name
        savedState[CERT_BYTES] = bytes
        state.update { it.copy(certName = name) }
        setFailure(null)
        validate()
    }

    fun onKeyPicked(uri: Uri?) {
        val (name, bytes) = readPicked(uri) ?: return
        savedState[KEY_NAME] = name
        savedState[KEY_BYTES] = bytes
        state.update { it.copy(keyName = name) }
        setFailure(null)
        validate()
    }

    /** Installs the picked pair. Success leaves the screen; failure stays on it. */
    fun apply() {
        val result = state.value.validation
        val service = snapshot.value.service
        if (result == null || !result.canApply || service == null || state.value.applying) {
            return
        }
        startApplying()
        service.replaceHttpsCertificate(result.certPem, result.keyPem) { outcome, detail ->
            when (outcome) {
                HttpsCertReplaceResult.SUCCESS -> succeed(R.string.custom_cert_applied)
                HttpsCertReplaceResult.SUCCESS_PENDING_START ->
                    succeed(R.string.custom_cert_applied_pending)
                else -> fail(outcome, detail)
            }
        }
    }

    /** Goes back to the certificate Syncthing generates. */
    fun reset() {
        val service = snapshot.value.service
        if (service == null || state.value.applying) {
            return
        }
        startApplying()
        service.resetHttpsCertificate { outcome, detail ->
            if (isSuccess(outcome)) {
                succeed(R.string.custom_cert_reset_done)
            } else {
                fail(outcome, detail)
            }
        }
    }

    private fun startApplying() {
        state.update { it.copy(applying = true) }
        setFailure(null)
    }

    private fun succeed(message: Int) {
        state.update { it.copy(applying = false) }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        send(SettingsEvent.CLOSE)
    }

    private fun fail(outcome: HttpsCertReplaceResult?, detail: String?) {
        state.update { it.copy(applying = false) }
        setFailure(ApplyFailure(outcome ?: HttpsCertReplaceResult.FAILED, detail))
    }

    private fun setFailure(failure: ApplyFailure?) {
        savedState[FAILURE_OUTCOME] = failure?.outcome?.name
        savedState[FAILURE_DETAIL] = failure?.detail
        state.update { it.copy(applyFailure = failure) }
    }

    /**
     * The enum is saved by name and looked up defensively: a name that no longer exists after an
     * app update restores as "no failure" rather than throwing.
     */
    private fun restoreFailure(): ApplyFailure? {
        val name = savedState.get<String>(FAILURE_OUTCOME) ?: return null
        val outcome = HttpsCertReplaceResult.entries.firstOrNull { it.name == name } ?: return null
        return ApplyFailure(outcome, savedState[FAILURE_DETAIL])
    }

    private fun validate() {
        val certBytes = savedState.get<ByteArray>(CERT_BYTES)
        val keyBytes = savedState.get<ByteArray>(KEY_BYTES)
        validation?.cancel()
        validation = viewModelScope.launch {
            val result =
                if (certBytes != null && keyBytes != null) {
                    withContext(ioDispatcher) { CertificateValidator.validate(certBytes, keyBytes) }
                } else {
                    null
                }
            state.update { it.copy(validation = result) }
        }
    }

    private suspend fun readCurrentCertificate(): CertificateValidator.CertInfo? =
        withContext(ioDispatcher) {
            try {
                val file = Constants.getHttpsCertFile(context)
                if (file.exists()) CertificateValidator.describe(file.readBytes()) else null
            } catch (_: Exception) {
                null
            }
        }

    private fun readPicked(uri: Uri?): Pair<String, ByteArray>? {
        if (uri == null) {
            return null
        }
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                toast(context, R.string.custom_cert_read_failed)
                null
            } else if (bytes.size > MAX_PEM_BYTES) {
                toast(context, R.string.custom_cert_file_too_large)
                null
            } else {
                (queryDisplayName(uri) ?: "selected file") to bytes
            }
        } catch (_: Exception) {
            toast(context, R.string.custom_cert_read_failed)
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
}

private fun isSuccess(outcome: SyncthingService.HttpsCertReplaceResult?): Boolean =
    outcome == HttpsCertReplaceResult.SUCCESS ||
        outcome == HttpsCertReplaceResult.SUCCESS_PENDING_START
