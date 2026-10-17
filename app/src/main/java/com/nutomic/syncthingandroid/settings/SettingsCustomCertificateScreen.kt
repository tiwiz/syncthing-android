package com.nutomic.syncthingandroid.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.groupedItemShape
import com.nutomic.syncthingandroid.util.CertificateValidator

fun EntryProviderScope<SettingsRoute>.settingsCustomCertificateEntry(
    navigator: Navigator<SettingsRoute>,
) {
    entry<SettingsRoute.CustomCertificate> {
        SettingsCustomCertificateRoute(navigator = navigator)
    }
}

@Composable
fun SettingsCustomCertificateRoute(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
    viewModel: CustomCertificateViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsEffects(viewModel.events, navigator)

    val certPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), viewModel::onCertPicked)
    val keyPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), viewModel::onKeyPicked)

    SettingsCustomCertificateScreen(
        navigator = navigator,
        state = state,
        serviceBound = snapshot.service != null,
        onPickCert = { certPicker.launch(arrayOf("*/*")) },
        onPickKey = { keyPicker.launch(arrayOf("*/*")) },
        onApply = viewModel::apply,
        onReset = viewModel::reset,
        modifier = modifier,
    )
}

@Composable
fun SettingsCustomCertificateScreen(
    navigator: Navigator<SettingsRoute>,
    state: CustomCertificateUiState,
    serviceBound: Boolean,
    onPickCert: () -> Unit,
    onPickKey: () -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        title = stringResource(R.string.webui_custom_cert_title),
        modifier = modifier,
    ) {
        customCertificateScaffoldContent(
            serviceBound = serviceBound,
            applying = state.applying,
            applyFailure = state.applyFailure,
            validation = state.validation,
            currentInfo = state.currentInfo,
            certName = state.certName,
            keyName = state.keyName,
            onPickCert = onPickCert,
            onPickKey = onPickKey,
            onApply = onApply,
            onShowResetDialog = { showResetDialog = true },
        )
    }

    if (showResetDialog) {
        ResetCertificateDialog(
            onDismiss = { showResetDialog = false },
            onConfirm = {
                showResetDialog = false
                onReset()
            },
        )
    }
}

private fun LazyListScope.customCertificateScaffoldContent(
    serviceBound: Boolean,
    applying: Boolean,
    applyFailure: ApplyFailure?,
    validation: CertificateValidator.ValidationResult?,
    currentInfo: CertificateValidator.CertInfo?,
    certName: String?,
    keyName: String?,
    onPickCert: () -> Unit,
    onPickKey: () -> Unit,
    onApply: () -> Unit,
    onShowResetDialog: () -> Unit,
) {
    explanationItems()

    currentCertificateItems(currentInfo)

    item {
        PickFilePreference(
            R.string.custom_cert_select_cert,
            certName,
            enabled = !applying,
            shape = groupedItemShape(0, 2),
            onClick = onPickCert,
        )
    }
    item {
        PickFilePreference(
            R.string.custom_cert_select_key,
            keyName,
            enabled = !applying,
            shape = groupedItemShape(1, 2),
            onClick = onPickKey,
        )
    }

    validation?.let { validationItems(it) }

    applyFailure?.let { failure ->
        item { ApplyFailureCard(applyFailureMessage(failure)) }
    }

    // Offered whenever the user is stuck — a rejected file or a failed apply. Both are cases where
    // the wiki page (root CA installation, full chain, matching key) is the actual answer.
    item {
        val showHelp = isBlockedByChecks(validation) || applyFailure != null
        val canApply = validation?.canApply == true && serviceBound && !applying
        SettingsGroup(
            items = buildList {
                add { shape: Shape ->
                    ApplyCertificateItem(
                        enabled = canApply,
                        applying = applying,
                        shape = shape,
                        onClick = onApply,
                    )
                }
                add { shape: Shape ->
                    SettingsItem(
                        title = stringResource(R.string.custom_cert_reset_title),
                        summary = stringResource(R.string.custom_cert_reset_summary),
                        shape = shape,
                        enabled = serviceBound && !applying,
                        onClick = onShowResetDialog,
                    )
                }
                if (showHelp) {
                    add { shape: Shape -> HelpRow(shape = shape) }
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private fun isBlockedByChecks(validation: CertificateValidator.ValidationResult?): Boolean =
    validation?.let { result ->
        result.parseError != null ||
                result.checks.any { it.status == CertificateValidator.Status.FAIL }
    } == true

@Composable
@ReadOnlyComposable
private fun applyFailureMessage(failure: ApplyFailure): String = when (failure.outcome) {
    SyncthingService.HttpsCertReplaceResult.FAILED_CERT_REJECTED ->
        stringResource(R.string.custom_cert_failed_rejected)
    SyncthingService.HttpsCertReplaceResult.FAILED_NOT_ONLINE ->
        stringResource(R.string.custom_cert_failed_not_online)
    else -> stringResource(R.string.custom_cert_failed, failure.detail.orEmpty())
}
