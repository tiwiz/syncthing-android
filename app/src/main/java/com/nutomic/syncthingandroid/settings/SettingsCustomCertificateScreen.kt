package com.nutomic.syncthingandroid.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.CertificateValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.Preference

/**
 * Upper bound on a picked certificate or key file. Generous for PEM — a full chain is a few KiB — while
 * keeping both files small enough to sit in the saved-instance-state Bundle, which travels over a
 * Binder transaction with a hard size limit shared process-wide.
 */
private const val MAX_PEM_BYTES = 64 * 1024

/** Relative to [R.string.wiki_url], matching how the onboarding pages link into the wiki. */
private const val CUSTOM_CERT_WIKI_PATH =
    "/tips-and-tricks/Use-a-custom-HTTPS-certificate-for-the-Web-GUI.md"

fun EntryProviderScope<SettingsRoute>.settingsCustomCertificateEntry(navigator: Navigator<SettingsRoute>) {
    entry<SettingsRoute.CustomCertificate> {
        SettingsCustomCertificateScreen(navigator = navigator)
    }
}

@Composable
fun SettingsCustomCertificateScreen(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val stService = LocalSyncthingService.current

    var certBytes by rememberSaveable { mutableStateOf<ByteArray?>(null) }
    var keyBytes by rememberSaveable { mutableStateOf<ByteArray?>(null) }
    var certName by rememberSaveable { mutableStateOf<String?>(null) }
    var keyName by rememberSaveable { mutableStateOf<String?>(null) }
    var applyFailure by rememberSaveable(stateSaver = ApplyFailureSaver) { mutableStateOf(null) }
    // Deliberately not saved: both are recomputed by the LaunchedEffects below, from the picked bytes
    // and from the certificate on disk respectively.
    var validation by remember { mutableStateOf<CertificateValidator.ValidationResult?>(null) }
    var currentInfo by remember { mutableStateOf<CertificateValidator.CertInfo?>(null) }
    // Not saved on purpose: an apply in flight cannot survive the composition being torn down, since
    // its result callback writes to it. Restoring "applying" would strand the spinner forever.
    var applying by remember { mutableStateOf(false) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        currentInfo = readCurrentCertificate(context, ioDispatcher)
    }

    LaunchedEffect(certBytes, keyBytes) {
        validation = validatePicked(certBytes, keyBytes, ioDispatcher)
    }

    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        readPicked(context, uri)?.let { (name, bytes) ->
            certName = name; certBytes = bytes; applyFailure = null
        }
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        readPicked(context, uri)?.let { (name, bytes) ->
            keyName = name; keyBytes = bytes; applyFailure = null
        }
    }

    val canApply = validation?.canApply == true && stService != null && !applying

    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        title = stringResource(R.string.webui_custom_cert_title),
        modifier = modifier,
    ) {
        explanationItems()

        item { CurrentCertificateCard(currentInfo) }

        item {
            PickFilePreference(R.string.custom_cert_select_cert, certName, enabled = !applying) {
                certPicker.launch(arrayOf("*/*"))
            }
        }
        item {
            PickFilePreference(R.string.custom_cert_select_key, keyName, enabled = !applying) {
                keyPicker.launch(arrayOf("*/*"))
            }
        }

        validation?.let { validationItems(it) }

        applyFailure?.let { failure ->
            item { ApplyFailureCard(applyFailureMessage(failure)) }
        }

        // Offered whenever the user is stuck — a rejected file or a failed apply. Both are cases where
        // the wiki page (root CA installation, full chain, matching key) is the actual answer.
        if (isBlockedByChecks(validation) || applyFailure != null) {
            item { HelpRow() }
        }

        item {
            ApplyCertificatePreference(enabled = canApply, applying = applying) {
                val result = validation
                val service = stService
                if (result != null && result.canApply && service != null && !applying) {
                    applying = true
                    applyFailure = null
                    service.replaceHttpsCertificate(result.certPem, result.keyPem) { outcome, detail ->
                        applying = false
                        applyFailure = onApplyOutcome(context, navigator, outcome, detail)
                    }
                }
            }
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.custom_cert_reset_title)) },
                summary = { Text(stringResource(R.string.custom_cert_reset_summary)) },
                enabled = stService != null && !applying,
                onClick = { showResetDialog = true },
            )
        }
    }

    if (showResetDialog) {
        ResetCertificateDialog(
            onDismiss = { showResetDialog = false },
            onConfirm = {
                showResetDialog = false
                val service = stService
                if (service != null && !applying) {
                    applying = true
                    applyFailure = null
                    service.resetHttpsCertificate { outcome, detail ->
                        applying = false
                        applyFailure = onResetOutcome(context, navigator, outcome, detail)
                    }
                }
            },
        )
    }
}

private suspend fun readCurrentCertificate(
    context: Context,
    ioDispatcher: CoroutineDispatcher,
): CertificateValidator.CertInfo? = withContext(ioDispatcher) {
    try {
        val file = Constants.getHttpsCertFile(context)
        if (file.exists()) CertificateValidator.describe(file.readBytes()) else null
    } catch (_: Exception) {
        null
    }
}

private suspend fun validatePicked(
    certBytes: ByteArray?,
    keyBytes: ByteArray?,
    ioDispatcher: CoroutineDispatcher,
): CertificateValidator.ValidationResult? =
    if (certBytes != null && keyBytes != null)
        withContext(ioDispatcher) { CertificateValidator.validate(certBytes, keyBytes) }
    else
        null

private fun isBlockedByChecks(validation: CertificateValidator.ValidationResult?): Boolean =
    validation?.let { result ->
        result.parseError != null ||
                result.checks.any { it.status == CertificateValidator.Status.FAIL }
    } == true

/**
 * A failed apply, kept on screen rather than shown as a toast. The reason matters here — "Syncthing
 * generated its own certificate instead" tells the user something actionable that a disappearing
 * "Could not apply the certificate" does not — and a toast cannot carry the help link.
 */
private data class ApplyFailure(
    val outcome: SyncthingService.HttpsCertReplaceResult,
    val detail: String?,
)

/**
 * Stores an [ApplyFailure] as two plain strings so it survives a configuration change. The enum is
 * saved by name and looked up defensively — a name that no longer exists after an app update restores
 * as "no failure" rather than throwing during state restoration.
 */
private val ApplyFailureSaver = listSaver<ApplyFailure?, String>(
    save = { failure ->
        failure?.let { listOf(it.outcome.name, it.detail.orEmpty()) }.orEmpty()
    },
    restore = { saved ->
        saved.takeIf { it.size == 2 }?.let { (name, detail) ->
            runCatching { SyncthingService.HttpsCertReplaceResult.valueOf(name) }
                .getOrNull()
                ?.let { ApplyFailure(it, detail.ifEmpty { null }) }
        }
    },
)

@Composable
@ReadOnlyComposable
private fun applyFailureMessage(failure: ApplyFailure): String = when (failure.outcome) {
    SyncthingService.HttpsCertReplaceResult.FAILED_CERT_REJECTED ->
        stringResource(R.string.custom_cert_failed_rejected)
    SyncthingService.HttpsCertReplaceResult.FAILED_NOT_ONLINE ->
        stringResource(R.string.custom_cert_failed_not_online)
    else -> stringResource(R.string.custom_cert_failed, failure.detail.orEmpty())
}

private fun isSuccess(outcome: SyncthingService.HttpsCertReplaceResult?): Boolean =
    outcome == SyncthingService.HttpsCertReplaceResult.SUCCESS ||
            outcome == SyncthingService.HttpsCertReplaceResult.SUCCESS_PENDING_START

/**
 * Reports the outcome of an apply. Success leaves the screen; failure stays put and is returned so it
 * can be rendered inline, because the reason is worth reading and acting on.
 */
private fun onApplyOutcome(
    context: Context,
    navigator: Navigator<SettingsRoute>,
    outcome: SyncthingService.HttpsCertReplaceResult?,
    detail: String?,
): ApplyFailure? {
    when (outcome) {
        SyncthingService.HttpsCertReplaceResult.SUCCESS ->
            Toast.makeText(context.applicationContext, R.string.custom_cert_applied, Toast.LENGTH_LONG).show()
        SyncthingService.HttpsCertReplaceResult.SUCCESS_PENDING_START ->
            Toast.makeText(context.applicationContext, R.string.custom_cert_applied_pending, Toast.LENGTH_LONG).show()
        else -> return ApplyFailure(outcome ?: SyncthingService.HttpsCertReplaceResult.FAILED, detail)
    }
    navigator.navigateUp()
    return null
}

/** Like [onApplyOutcome], for resetting to the certificate Syncthing generates. */
private fun onResetOutcome(
    context: Context,
    navigator: Navigator<SettingsRoute>,
    outcome: SyncthingService.HttpsCertReplaceResult?,
    detail: String?,
): ApplyFailure? {
    if (!isSuccess(outcome)) {
        return ApplyFailure(outcome ?: SyncthingService.HttpsCertReplaceResult.FAILED, detail)
    }
    Toast.makeText(
        context.applicationContext,
        R.string.custom_cert_reset_done,
        Toast.LENGTH_LONG,
    ).show()
    navigator.navigateUp()
    return null
}

/**
 * Links to the wiki page for this feature, following the `wiki_url` + relative path convention.
 *
 * Deliberately an ordinary preference row rather than a button: it is a secondary action sitting just
 * above "Apply certificate", and a second filled button competes with — and when Apply is disabled,
 * visually outranks — the primary one.
 */
@Composable
private fun HelpRow() {
    val context = LocalContext.current
    val url = stringResource(R.string.wiki_url) + CUSTOM_CERT_WIKI_PATH
    Preference(
        title = { Text(stringResource(R.string.generic_help)) },
        summary = { Text(stringResource(R.string.wiki_open_setup_guide)) },
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (_: ActivityNotFoundException) {
                toast(context, R.string.no_app_to_open_link)
            }
        },
    )
}

private fun readPicked(context: Context, uri: Uri?): Pair<String, ByteArray>? {
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
            (queryDisplayName(context, uri) ?: "selected file") to bytes
        }
    } catch (_: Exception) {
        toast(context, R.string.custom_cert_read_failed)
        null
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
}

private fun toast(context: Context, resId: Int) {
    Toast.makeText(context.applicationContext, resId, Toast.LENGTH_LONG).show()
}
