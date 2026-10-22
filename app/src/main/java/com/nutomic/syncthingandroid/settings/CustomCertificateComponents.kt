package com.nutomic.syncthingandroid.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.CertificateValidator

// Building blocks of SettingsCustomCertificateScreen.

internal fun LazyListScope.explanationItems() {
    groupedSection(
        R.string.preference_category_explanation,
        listOf({ shape: Shape ->
            SettingsItem(
                title = stringResource(R.string.custom_cert_explanation),
                shape = shape,
                onClick = {},
            )
        }),
    )
}

@Composable
internal fun PickFilePreference(
    @StringRes title: Int,
    fileName: String?,
    enabled: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    SettingsItem(
        title = stringResource(title),
        summary = fileName ?: stringResource(R.string.custom_cert_not_selected),
        shape = shape,
        enabled = enabled,
        onClick = onClick,
    )
}

internal fun LazyListScope.validationItems(result: CertificateValidator.ValidationResult) {
    val parseError = result.parseError
    if (parseError != null) {
        item { CheckRow(CertificateValidator.Status.FAIL, parseError, null) }
    } else {
        result.checks.forEach { check ->
            item { CheckRow(check.status, stringResource(checkTitle(check.check)), check.detail) }
        }
    }
}

@Composable
internal fun ApplyCertificateItem(
    enabled: Boolean,
    applying: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    val summary = if (applying) stringResource(R.string.custom_cert_applying) else null
    SettingsItem(
        title = stringResource(R.string.custom_cert_apply),
        summary = summary,
        shape = shape,
        enabled = enabled && !applying,
        onClick = onClick,
    )
}

@Composable
internal fun ResetCertificateDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_cert_reset_title)) },
        text = { Text(stringResource(R.string.custom_cert_reset_question)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

internal fun LazyListScope.currentCertificateItems(info: CertificateValidator.CertInfo?) {
    groupedSection(
        R.string.custom_cert_current_title,
        listOf({ shape: Shape ->
            val lines =
                when (info) {
                    null -> listOf(stringResource(R.string.custom_cert_current_none))
                    else ->
                        listOf(
                            stringResource(R.string.custom_cert_subject, info.subject),
                            stringResource(R.string.custom_cert_issuer, info.issuer),
                            stringResource(R.string.custom_cert_expires, info.notAfter),
                            stringResource(
                                if (info.selfSigned) R.string.custom_cert_self_signed
                                else R.string.custom_cert_ca_signed
                            ),
                        )
                }
            SettingsItem(
                title = lines.joinToString("\n"),
                shape = shape,
                onClick = {},
            )
        }),
    )
}

internal val CHECK_PASS_COLOR = Color(0xFF2E7D32)
internal val CHECK_WARN_COLOR = Color(0xFFF9A825)

@Composable
internal fun CheckRow(status: CertificateValidator.Status, title: String, detail: String?) {
    val (icon, tint) =
        when (status) {
            CertificateValidator.Status.PASS -> Icons.Filled.CheckCircle to CHECK_PASS_COLOR
            CertificateValidator.Status.WARN -> Icons.Filled.Warning to CHECK_WARN_COLOR
            CertificateValidator.Status.FAIL ->
                Icons.Filled.Error to MaterialTheme.colorScheme.error
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun checkTitle(check: CertificateValidator.Check): Int =
    when (check) {
        CertificateValidator.Check.CHAIN -> R.string.cert_check_chain
        CertificateValidator.Check.TRUST -> R.string.cert_check_trust
        CertificateValidator.Check.VALIDITY -> R.string.cert_check_validity
        CertificateValidator.Check.KEY -> R.string.cert_check_key
    }

/** Relative to [R.string.fork_wiki_url], matching how the onboarding pages link into the wiki. */
internal const val CUSTOM_CERT_WIKI_PATH =
    "/tips-and-tricks/Use-a-custom-HTTPS-certificate-for-the-Web-GUI.md"

internal fun toast(context: Context, resId: Int) {
    Toast.makeText(context.applicationContext, resId, Toast.LENGTH_LONG).show()
}

/**
 * Links to the wiki page for this feature, following the `wiki_url` + relative path convention.
 *
 * Deliberately an ordinary settings row rather than a button: it is a secondary action sitting just
 * above "Apply certificate", and a second filled button competes with — and when Apply is disabled,
 * visually outranks — the primary one.
 */
@Composable
internal fun HelpRow(shape: Shape) {
    val context = LocalContext.current
    val url = stringResource(R.string.fork_wiki_url) + CUSTOM_CERT_WIKI_PATH
    SettingsItem(
        title = stringResource(R.string.generic_help),
        summary = stringResource(R.string.wiki_open_setup_guide),
        shape = shape,
        onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (_: ActivityNotFoundException) {
                toast(context, R.string.no_app_to_open_link)
            }
        },
    )
}

/**
 * The outcome of an apply, which is a different kind of thing from the validation checks above it —
 * hence a card rather than another [CheckRow]. It also has to carry more text than a check detail,
 * so it needs the room.
 */
@Composable
internal fun ApplyFailureCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.custom_cert_apply_failed_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
