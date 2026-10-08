package io.github.alexeygrigorev.openscan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    companion object {
        /** Opt-in telemetry is off unless the user turns it on. */
        const val DEFAULT_FEEDBACK_UPLOAD_ENABLED = false

        /** Originals are kept unless the user opts out. */
        const val DEFAULT_KEEP_ORIGINALS_ENABLED = true

        private val FEEDBACK_UPLOAD_ENABLED = booleanPreferencesKey("feedback_upload_enabled")
        private val KEEP_ORIGINALS_ENABLED = booleanPreferencesKey("keep_originals_enabled")

        /** File-backed preference DataStore used by [io.github.alexeygrigorev.openscan.data.AppContainer]. */
        fun createDefaultDataStore(context: Context): DataStore<Preferences> {
            val appContext = context.applicationContext
            return PreferenceDataStoreFactory.create(
                produceFile = { File(appContext.filesDir, "settings.preferences_pb") }
            )
        }
    }
}
