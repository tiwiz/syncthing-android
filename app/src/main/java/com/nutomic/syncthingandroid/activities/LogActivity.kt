package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

/** Shows the Android log or Syncthing's log, and shares it as a file. */
@AndroidEntryPoint
class LogActivity : ThemedAppCompatActivity() {

    private val viewModel: LogViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ApplicationTheme {
                LogScreen(
                    showSyncthingLog = state.showSyncthingLog,
                    logText = state.logText,
                    onSwitchLogs = viewModel::switchLogs,
                    onShare = { shareLogFile(viewModel.shownLogFile) },
                    onNavigateBack = ::finish,
                )
            }
        }
    }

    private fun shareLogFile(logFile: File) {
        if (!logFile.exists()) {
            Toast.makeText(this, R.string.share_log_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val contentUri = FileProvider.getUriForFile(this, "$packageName.provider", logFile)
        val shareIntent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, contentUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_log_file)))
    }
}

/** The log in small selectable text, scrolled to its end, with switch and share actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogScreen(
    showSyncthingLog: Boolean,
    logText: String?,
    onSwitchLogs: () -> Unit,
    onShare: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (showSyncthingLog) R.string.syncthing_log_title
                            else R.string.android_log_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.share_log_file),
                        )
                    }
                    SwitchLogsMenu(showSyncthingLog = showSyncthingLog, onSwitchLogs = onSwitchLogs)
                },
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        LaunchedEffect(logText) { scrollState.scrollTo(scrollState.maxValue) }
        Box(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState)) {
            SelectionContainer {
                Text(
                    text = logText ?: stringResource(R.string.retrieving_logs),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier =
                        Modifier.padding(16.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                RoundedCornerShape(CARD_CORNER.dp),
                            )
                            .padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchLogsMenu(showSyncthingLog: Boolean, onSwitchLogs: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (showSyncthingLog) R.string.view_android_log
                        else R.string.view_syncthing_log
                    )
                )
            },
            onClick = {
                expanded = false
                onSwitchLogs()
            },
        )
    }
}
