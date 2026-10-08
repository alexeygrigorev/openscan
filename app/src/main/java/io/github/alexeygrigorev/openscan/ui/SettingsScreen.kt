package io.github.alexeygrigorev.openscan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexeygrigorev.openscan.data.SettingsRepository
import io.github.alexeygrigorev.openscan.update.ReleaseChecker
import io.github.alexeygrigorev.openscan.update.UpdateMonitor
import io.github.alexeygrigorev.openscan.update.UpdateState
import io.github.alexeygrigorev.openscan.update.UpdateStatus
import io.github.alexeygrigorev.openscan.update.releaseVersionLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    /** Opt-in telemetry toggle; StateFlow starts at false until DataStore loads. */
    val feedbackUploadEnabled: StateFlow<Boolean> = settings.feedbackUploadEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setFeedbackUploadEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setFeedbackUploadEnabled(enabled) }
    }

    /** Keep-originals toggle; StateFlow starts at true until DataStore loads. */
    val keepOriginalsEnabled: StateFlow<Boolean> = settings.keepOriginalsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setKeepOriginalsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setKeepOriginalsEnabled(enabled) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    updateMonitor: UpdateMonitor,
    onBack: () -> Unit,
) {
    val enabled by viewModel.feedbackUploadEnabled.collectAsState()
    val keepOriginals by viewModel.keepOriginalsEnabled.collectAsState()
    val updateState by updateMonitor.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Capture", style = MaterialTheme.typography.titleMedium)

            SettingSwitchRow(
                title = "Save original photos",
                description = "Keep the full camera frame next to each cropped page, so a " +
                    "page can be re-cropped later or captures can be shared for " +
                    "debugging. Roughly doubles the storage each page needs.",
                checked = keepOriginals,
                onCheckedChange = { viewModel.setKeepOriginalsEnabled(it) },
                modifier = Modifier.padding(top = 8.dp),
            )

            SettingSwitchRow(
                title = "Send pictures to our servers so we can use them to improve our application",
                description = "Off by default. Uploads happen only in the background and are used to " +
                    "improve document detection.",
                checked = enabled,
                onCheckedChange = { viewModel.setFeedbackUploadEnabled(it) },
                modifier = Modifier.padding(top = 16.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            UpdatesGroup(
                state = updateState,
                onCheckNow = { updateMonitor.check() },
                onDownload = {
                    updateState.available?.let {
                        ReleaseChecker.openReleaseUrl(context, it.downloadUrl)
                    }
                },
                onNotes = {
                    updateState.available?.let {
                        ReleaseChecker.openReleaseUrl(context, it.notesUrl)
                    }
                },
            )
        }
    }
}

/** Toggle row used by the Capture section: label + explanation, switch on the right. */
@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/**
 * Settings → Updates: the once-per-launch GitHub-releases check, re-runnable.
 * Nothing installs itself — an available update offers the published APK for
 * this install's flavour, opened in the browser like the first sideload.
 */
@Composable
private fun UpdatesGroup(
    state: UpdateState,
    onCheckNow: () -> Unit,
    onDownload: () -> Unit,
    onNotes: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Text(
            "The app checks the project's GitHub releases once per launch. Nothing " +
                "installs itself: when a newer version exists you get a banner with a " +
                "download link, and updating means installing the APK the way you first " +
                "put it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (state.status) {
                        UpdateStatus.IDLE -> releaseVersionLabel(state.currentVersion)
                        UpdateStatus.CHECKING -> "Checking…"
                        UpdateStatus.UP_TO_DATE -> "Up to date (${releaseVersionLabel(state.currentVersion)})."
                        UpdateStatus.AVAILABLE ->
                            "${state.available?.tagName} is available."
                        UpdateStatus.FAILED -> "Check failed: ${state.reason}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(
                onClick = onCheckNow,
                enabled = state.status != UpdateStatus.CHECKING,
                modifier = Modifier.padding(start = 16.dp),
            ) {
                Text(if (state.status == UpdateStatus.CHECKING) "Checking…" else "Check now")
            }
        }

        if (state.status == UpdateStatus.AVAILABLE && state.available != null) {
            Row {
                TextButton(onClick = onDownload) { Text("Download APK") }
                TextButton(onClick = onNotes) { Text("Release notes") }
            }
        }
    }
}
