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

    companion object {
        /** Opt-in telemetry is off unless the user turns it on. */
        const val DEFAULT_FEEDBACK_UPLOAD_ENABLED = false

        private val FEEDBACK_UPLOAD_ENABLED = booleanPreferencesKey("feedback_upload_enabled")

        /** File-backed preference DataStore used by [io.github.alexeygrigorev.openscan.data.AppContainer]. */
        fun createDefaultDataStore(context: Context): DataStore<Preferences> {
            val appContext = context.applicationContext
            return PreferenceDataStoreFactory.create(
                produceFile = { File(appContext.filesDir, "settings.preferences_pb") }
            )
        }
    }
}
