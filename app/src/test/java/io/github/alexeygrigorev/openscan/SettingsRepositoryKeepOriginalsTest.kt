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
 * Keep-originals default-on logic against a real (file-backed) DataStore in
 * a temp directory: an empty DataStore must expose the toggle as enabled —
 * originals are what re-cropping and a later debug-sharing export need.
 */
class SettingsRepositoryKeepOriginalsTest {

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
    fun `default constant is on`() {
        assertEquals(true, DataStoreSettingsRepository.DEFAULT_KEEP_ORIGINALS_ENABLED)
    }

    @Test
    fun `empty datastore exposes keep originals as enabled`() = runBlocking {
        val repository = repositoryInTempDir()
        assertTrue(repository.keepOriginalsEnabled.first())
    }

    @Test
    fun `setter persists the opt-out and it can be turned back on`() = runBlocking {
        val repository = repositoryInTempDir()
        assertTrue(repository.keepOriginalsEnabled.first())
        repository.setKeepOriginalsEnabled(false)
        assertFalse(repository.keepOriginalsEnabled.first())
        repository.setKeepOriginalsEnabled(true)
        assertTrue(repository.keepOriginalsEnabled.first())
    }
}
