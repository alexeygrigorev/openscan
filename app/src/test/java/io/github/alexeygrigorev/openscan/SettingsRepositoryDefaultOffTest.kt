package io.github.alexeygrigorev.openscan

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.alexeygrigorev.openscan.data.DataStoreSettingsRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Default-off logic against a real (file-backed) DataStore in a temp
 * directory: an empty DataStore must expose the opt-in as disabled.
 */
class SettingsRepositoryDefaultOffTest {

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private fun repositoryInTempDir(): DataStoreSettingsRepository {
        val dir = Files.createTempDirectory("openscan-settings-test").toFile()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "settings.preferences_pb") },
        )
        return DataStoreSettingsRepository(dataStore)
    }

    @Test
    fun `default constant is off`() {
        assertEquals(false, DataStoreSettingsRepository.DEFAULT_FEEDBACK_UPLOAD_ENABLED)
    }

    @Test
    fun `empty datastore exposes feedback upload as disabled`() = runBlocking {
        val repository = repositoryInTempDir()
        assertFalse(repository.feedbackUploadEnabled.first())
    }

    @Test
    fun `setter persists the opt-in and it can be turned off again`() = runBlocking {
        val repository = repositoryInTempDir()
        assertFalse(repository.feedbackUploadEnabled.first())
        repository.setFeedbackUploadEnabled(true)
        assertTrue(repository.feedbackUploadEnabled.first())
        repository.setFeedbackUploadEnabled(false)
        assertFalse(repository.feedbackUploadEnabled.first())
    }
}
