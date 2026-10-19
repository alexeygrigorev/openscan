package io.github.alexeygrigorev.openscan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexeygrigorev.openscan.data.DocumentsRepository
import io.github.alexeygrigorev.openscan.data.SettingsRepository
import io.github.alexeygrigorev.openscan.scan.ScanFilter
import io.github.alexeygrigorev.openscan.scan.UploadStats
import io.github.alexeygrigorev.openscan.update.ReleaseChecker
import io.github.alexeygrigorev.openscan.update.UpdateMonitor
import io.github.alexeygrigorev.openscan.update.UpdateState
import io.github.alexeygrigorev.openscan.update.UpdateStatus
import io.github.alexeygrigorev.openscan.update.releaseVersionLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val repository: DocumentsRepository,
) : ViewModel() {

    /** Opt-in telemetry toggle; StateFlow starts at false until DataStore loads. */
    val feedbackUploadEnabled: StateFlow<Boolean> = settings.feedbackUploadEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setFeedbackUploadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setFeedbackUploadEnabled(enabled)
            // Re-enabling flushes whatever queued while paused.
            if (enabled) repository.flushUploads()
        }
    }

    /** Keep-originals toggle; StateFlow starts at true until DataStore loads. */
    val keepOriginalsEnabled: StateFlow<Boolean> = settings.keepOriginalsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setKeepOriginalsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setKeepOriginalsEnabled(enabled) }
    }

    /** Developer mode unlocks the debug-archive actions. */
    val developerModeEnabled: StateFlow<Boolean> = settings.developerModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setDeveloperModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setDeveloperModeEnabled(enabled) }
    }

    /** Default enhancement filter. */
    val filterDefault: StateFlow<String> = settings.filterDefault
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanFilter.DEFAULT.id)

    fun setFilterDefault(filterId: String) {
        viewModelScope.launch { settings.setFilterDefault(filterId) }
    }

    /** Auto-capture for the foss viewfinder (item 9). */
    val autoCaptureEnabled: StateFlow<Boolean> = settings.autoCaptureEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAutoCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoCaptureEnabled(enabled) }
    }

    /** Visible developer-share status (item 0). */
    val uploadStats: StateFlow<UploadStats> = repository.observeUploadStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UploadStats())

    fun retryUploads() {
        repository.retryUploads()
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
    val developerMode by viewModel.developerModeEnabled.collectAsState()
    val filterDefault by viewModel.filterDefault.collectAsState()
    val autoCapture by viewModel.autoCaptureEnabled.collectAsState()
    val uploadStats by viewModel.uploadStats.collectAsState()
    val updateState by updateMonitor.state.collectAsState()
    val context = LocalContext.current
    var filterPickerOpen by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
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

            // Visible upload status (item 0): queued / uploading / uploaded /
            // failed, with a manual retry. Paused while the toggle is off.
            UploadStatusRow(
                stats = uploadStats,
                toggleOn = enabled,
                onRetry = { viewModel.retryUploads() },
                modifier = Modifier.padding(top = 8.dp),
            )

            SettingSwitchRow(
                title = "Auto-capture",
                description = "Snap a page automatically once detection holds steady. " +
                    "When off, the live outline is only a hint and you capture " +
                    "with the Take photo shutter instead.",
                checked = autoCapture,
                onCheckedChange = { viewModel.setAutoCaptureEnabled(it) },
                modifier = Modifier.padding(top = 16.dp),
            )

            SettingChoiceRow(
                title = "Scan enhancement",
                description = "Default filter for new pages. The last filter chosen " +
                    "for a document or page becomes the default.",
                value = ScanFilter.fromId(filterDefault).title,
                onClick = { filterPickerOpen = true },
                modifier = Modifier.padding(top = 16.dp),
            )

            SettingSwitchRow(
                title = "Developer mode",
                description = "Unlocks the debug-archive export and upload: raw photos " +
                    "plus corner markup as a ZIP for improving detection.",
                checked = developerMode,
                onCheckedChange = { viewModel.setDeveloperModeEnabled(it) },
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

    if (filterPickerOpen) {
        FilterPickerDialog(
            title = "Default enhancement",
            selectedId = filterDefault,
            defaultLabel = null,
            onPick = { id ->
                if (id != null) viewModel.setFilterDefault(id)
            },
            onDismiss = { filterPickerOpen = false },
        )
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
 * Settings → Developer sharing status (item 0): one line per state plus a
 * retry for failed/queued rows. Retrying while the toggle is off does
 * nothing — the queue stays paused until opt-in.
 */
@Composable
private fun UploadStatusRow(
    stats: UploadStats,
    toggleOn: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                when {
                    !toggleOn && stats.queued + stats.failed > 0 ->
                        "Uploads paused (toggle off): ${stats.queued} queued, ${stats.failed} failed."
                    !toggleOn -> "Uploads off."
                    stats.uploading > 0 ->
                        "Uploading… ${stats.uploading} in flight, ${stats.queued} queued."
                    stats.failed > 0 ->
                        "${stats.uploaded} uploaded, ${stats.failed} failed — retry below."
                    stats.queued > 0 -> "${stats.queued} queued for upload."
                    stats.uploaded > 0 -> "${stats.uploaded} uploaded."
                    else -> "Nothing uploaded yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stats.queued + stats.failed > 0 && toggleOn) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.padding(start = 16.dp),
            ) {
                Text("Retry")
            }
        }
    }
}

/** Choice row used by the enhancement default: label + value, opens a dialog. */
@Composable
private fun SettingChoiceRow(
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit,
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
        TextButton(
            onClick = onClick,
            modifier = Modifier.padding(start = 16.dp),
        ) {
            Text(value)
        }
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
