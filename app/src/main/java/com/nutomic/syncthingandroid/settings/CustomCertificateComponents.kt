package com.nutomic.syncthingandroid.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.CertificateValidator
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.PreferenceCategory

// Building blocks of SettingsCustomCertificateScreen.

internal fun LazyListScope.explanationItems() {
    item {
        PreferenceCategory(
            title = { Text(stringResource(R.string.preference_category_explanation)) }
        )
    }
    item { Preference(title = { Text(stringResource(R.string.custom_cert_explanation)) }) }
}

@Composable
internal fun PickFilePreference(
    @StringRes title: Int,
    fileName: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Preference(
        title = { Text(stringResource(title)) },
        summary = { Text(fileName ?: stringResource(R.string.custom_cert_not_selected)) },
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
internal fun ApplyCertificatePreference(
    enabled: Boolean,
    applying: Boolean,
    onClick: () -> Unit,
) {
    Preference(
        title = {
            Button(
                onClick = onClick,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.custom_cert_apply))
            }
        },
        summary = {
            Box(
                modifier = Modifier.height(24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                AnimatedVisibility(
                    visible = applying,
                    enter = fadeIn(animationSpec = tween(durationMillis = 120)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 120)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.custom_cert_applying),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
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

@Composable
internal fun CurrentCertificateCard(info: CertificateValidator.CertInfo?) {
    PreferenceCategory(title = { Text(stringResource(R.string.custom_cert_current_title)) })
    Preference(
        title = {
            if (info == null) {
                Text(stringResource(R.string.custom_cert_current_none))
            } else {
                Text(stringResource(R.string.custom_cert_subject, info.subject))
                Text(stringResource(R.string.custom_cert_issuer, info.issuer))
                Text(stringResource(R.string.custom_cert_expires, info.notAfter))
                Text(
                    stringResource(
                        if (info.selfSigned) R.string.custom_cert_self_signed
                        else R.string.custom_cert_ca_signed
                    )
                )
            }
        }
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
