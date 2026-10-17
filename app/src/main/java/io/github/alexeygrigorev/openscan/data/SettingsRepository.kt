package io.github.alexeygrigorev.openscan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App settings. Single source of truth: everything in this file, nothing
 * elsewhere reads or writes these preferences directly.
 */
interface SettingsRepository {

    /**
     * Opt-in telemetry: when true, the original captured page photo is
     * uploaded in the background so the document detector can be improved.
     * Default is OFF — nothing ever leaves the device unless the user
     * explicitly enables this.
     */
    val feedbackUploadEnabled: Flow<Boolean>

    suspend fun setFeedbackUploadEnabled(enabled: Boolean)

    /**
     * Keep the full (uncropped) camera frame next to every cropped page.
     * Default is ON: the originals are what re-cropping and a later
     * "share captures for debugging" export need. Costs roughly double the
     * page storage, which is why it can be turned off.
     */
    val keepOriginalsEnabled: Flow<Boolean>

    suspend fun setKeepOriginalsEnabled(enabled: Boolean)

    /**
     * Developer mode (item 1): unlocks the debug-archive export and extra
     * diagnostics. Default OFF.
     */
    val developerModeEnabled: Flow<Boolean>

    suspend fun setDeveloperModeEnabled(enabled: Boolean)

    /**
     * Default enhancement filter id ([ScanFilter.id]) for pages without a
     * document/page override. Default is the high-contrast document mode.
     */
    val filterDefault: Flow<String>

    suspend fun setFilterDefault(filterId: String)

    /**
     * Auto-capture (item 9): when on, the foss viewfinder snaps a page once
     * the detector holds its corners steady; when off, the live outline is
     * only a hint and capture is manual via the shutter. Default OFF:
     * tapping the shutter plus post-capture refinement is the primary flow.
     */
    val autoCaptureEnabled: Flow<Boolean>

    suspend fun setAutoCaptureEnabled(enabled: Boolean)
}

/** DataStore-backed [SettingsRepository]; the only reader/writer of the pref. */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val feedbackUploadEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[FEEDBACK_UPLOAD_ENABLED] ?: DEFAULT_FEEDBACK_UPLOAD_ENABLED }

    override suspend fun setFeedbackUploadEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[FEEDBACK_UPLOAD_ENABLED] = enabled }
    }

    override val keepOriginalsEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEEP_ORIGINALS_ENABLED] ?: DEFAULT_KEEP_ORIGINALS_ENABLED }

    override suspend fun setKeepOriginalsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEEP_ORIGINALS_ENABLED] = enabled }
    }

    override val developerModeEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[DEVELOPER_MODE_ENABLED] ?: DEFAULT_DEVELOPER_MODE_ENABLED }

    override suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DEVELOPER_MODE_ENABLED] = enabled }
    }

    override val filterDefault: Flow<String> =
        dataStore.data.map { prefs -> prefs[FILTER_DEFAULT] ?: DEFAULT_FILTER_DEFAULT }

    override suspend fun setFilterDefault(filterId: String) {
        dataStore.edit { prefs -> prefs[FILTER_DEFAULT] = filterId }
    }

    override val autoCaptureEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[AUTO_CAPTURE_ENABLED] ?: DEFAULT_AUTO_CAPTURE_ENABLED }

    override suspend fun setAutoCaptureEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AUTO_CAPTURE_ENABLED] = enabled }
    }

    companion object {
        /** Opt-in telemetry is off unless the user turns it on. */
        const val DEFAULT_FEEDBACK_UPLOAD_ENABLED = false

        /** Originals are kept unless the user opts out. */
        const val DEFAULT_KEEP_ORIGINALS_ENABLED = true

        /** Developer diagnostics are hidden unless the user opts in. */
        const val DEFAULT_DEVELOPER_MODE_ENABLED = false

        /** High-contrast document mode is the default enhancement. */
        const val DEFAULT_FILTER_DEFAULT = "document"

        /** Manual shutter is the primary flow; auto-capture is opt-in. */
        const val DEFAULT_AUTO_CAPTURE_ENABLED = false

        private val FEEDBACK_UPLOAD_ENABLED = booleanPreferencesKey("feedback_upload_enabled")
        private val KEEP_ORIGINALS_ENABLED = booleanPreferencesKey("keep_originals_enabled")
        private val DEVELOPER_MODE_ENABLED = booleanPreferencesKey("developer_mode_enabled")
        private val FILTER_DEFAULT = stringPreferencesKey("filter_default")
        private val AUTO_CAPTURE_ENABLED = booleanPreferencesKey("auto_capture_enabled")

        /** File-backed preference DataStore used by [io.github.alexeygrigorev.openscan.data.AppContainer]. */
        fun createDefaultDataStore(context: Context): DataStore<Preferences> {
            val appContext = context.applicationContext
            return PreferenceDataStoreFactory.create(
                produceFile = { File(appContext.filesDir, "settings.preferences_pb") }
            )
        }
    }
}
