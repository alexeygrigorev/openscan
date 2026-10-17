package io.github.alexeygrigorev.openscan

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexeygrigorev.openscan.data.OpenScanDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrade path for the v0.2.4 release: a v1 database (everything shipped up
 * to v0.2.3, built here with the framework helper) must open through the
 * real Room stack with migrations 1→2→3→4 — Room validates the migrated
 * schema against the current entities on open, so any column mismatch fails
 * loudly here instead of on Alexey's phone. Rows must survive with sane
 * defaults, so updating never needs an uninstall.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
    fun migrate1To4KeepsRowsWithSaneDefaults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)

        // v1 as shipped pre-v0.2.4 (documents + pages only).
        object : SQLiteOpenHelper(context, TEST_DB, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE documents (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE pages (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "documentId INTEGER NOT NULL, position INTEGER NOT NULL, " +
                        "filePath TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX index_pages_documentId ON pages(documentId)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }.apply {
            writableDatabase.execSQL(
                "INSERT INTO documents (title, createdAt, updatedAt) VALUES ('Oct 17, 15:37', 1, 2)"
            )
            writableDatabase.execSQL(
                "INSERT INTO pages (documentId, position, filePath, createdAt) VALUES (1, 0, '/x.jpg', 3)"
            )
            close()
        }

        // The real upgrade: Room runs 1→2→3→4 and validates the result.
        val db = Room.databaseBuilder(context, OpenScanDatabase::class.java, TEST_DB)
            .addMigrations(
                OpenScanDatabase.MIGRATION_1_2,
                OpenScanDatabase.MIGRATION_2_3,
                OpenScanDatabase.MIGRATION_3_4,
            )
            .build()
        try {
            val pages = runBlocking { db.openscanDao().getPages(1) }
            assertEquals(1, pages.size)
            val page = pages[0]
            assertEquals("/x.jpg", page.filePath)
            assertNull(page.autoCorners)
            assertNull(page.refinedCorners)
            assertNull(page.userCorners)
            assertNull(page.cornerSchema)
            assertNull(page.imageWidth)
            assertNull(page.imageHeight)
            assertNull(page.filter)
            assertNull(page.needsCheck)
            val documents = runBlocking { db.openscanDao().getDocument(1) }
            assertEquals("Oct 17, 15:37", documents?.title)
            assertNull(documents?.filter)
            // New writes work on the migrated schema.
            runBlocking { db.openscanDao().setNeedsCheck(page.id, true) }
            runBlocking { db.openscanDao().setNeedsCheck(page.id, true) }
            assertEquals(true, runBlocking { db.openscanDao().getPage(page.id) }?.needsCheck)
        } finally {
            db.close()
            context.deleteDatabase(TEST_DB)
        }
        assertTrue(true)
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
