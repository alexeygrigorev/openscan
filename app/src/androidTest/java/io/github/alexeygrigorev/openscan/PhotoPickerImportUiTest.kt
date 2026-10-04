package io.github.alexeygrigorev.openscan

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.alexeygrigorev.openscan.scan.Images
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real app UI across process boundaries with UiAutomator:
 *
 *  - the photo-picker import path (Documents → "Import images" → system
 *    picker → select → new document with the page on disk), which is the
 *    exact flow a user on a device without the ML Kit scanner module gets;
 *  - the same path with a three-image multi-select, checking the batch
 *    lands complete and is confirmed with a "Added 3 pages" summary;
 *  - the graceful fallback when Play services are missing and the scan
 *    button cannot open the document scanner (no crash, import offered).
 *
 * These complement BatchPipelineInstrumentedTest, which exercises the
 * repository layer below the UI.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPickerImportUiTest {

    private val app = "io.github.alexeygrigorev.openscan"

    // The system photo picker ships under either package depending on the
    // image (mainline module vs Google flavor).
    private val pickerPackages = setOf(
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
    )

    private fun device(): UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun target() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun launchApp(device: UiDevice) {
        // NOTE: never force-stop here — the instrumentation runs inside the
        // app process, so killing the app kills the tests themselves. And
        // NEW_TASK|CLEAR_TASK matters: a leftover photo picker sits in the
        // app's OWN task, where a plain am start just delivers onNewIntent
        // underneath it. Clearing the task also destroys the picker, so both
        // tests start fresh on the library screen.
        device.pressHome()
        device.executeShellCommand("am start -f 0x10008000 -n $app/.MainActivity")
        assertTrue(
            "app did not come to the foreground",
            device.wait(Until.hasObject(By.text("OpenScan")), 20_000),
        )
    }

    private fun waitForPackage(device: UiDevice, packages: Set<String>, timeout: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (device.currentPackageName in packages) return true
            Thread.sleep(300)
        }
        return device.currentPackageName in packages
    }

    /** Contributes a solid-color JPEG to MediaStore — no permissions needed for own inserts. */
    private fun seedGalleryImage(color: Int, name: String): Uri {
        val resolver = target().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw AssertionError("could not seed gallery image")
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return uri
    }

    private fun pageFiles(): Set<File> =
        File(target().filesDir, "documents").walkTopDown()
            .filter { it.isFile && it.extension == "jpg" }
            .toSet()

    private fun hierarchySnippet(device: UiDevice): String = try {
        val out = ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        String(out.toByteArray()).replace(Regex("\\s+"), " ").take(2500)
    } catch (t: Throwable) {
        "(hierarchy dump failed: ${t.message})"
    }

    @Test
    fun importThroughSystemPhotoPickerCreatesADocument() {
        val device = device()
        val seeded = seedGalleryImage(Color.rgb(200, 30, 30), "openscan-e2e-${System.currentTimeMillis()}.jpg")
        try {
            launchApp(device)
            val before = pageFiles()

            device.wait(Until.findObject(By.desc("Import images")), 10_000)?.click()
                ?: throw AssertionError("library screen never offered Import images")
            assertTrue(
                "the system photo picker did not open; foreground=${device.currentPackageName}",
                waitForPackage(device, pickerPackages, 15_000),
            )

            // Prefer our seeded image (its name may land in the accessibility
            // tree); fall back to the first grid cell by geometry — cells sit
            // below the Photos/Albums tabs, are cell-sized, and the top-left
            // one is the most recent image (ours was just seeded).
            val ours: UiObject2? = device.wait(Until.findObject(By.descContains("openscan-e2e")), 8_000)
            var clickedOurs = false
            if (ours != null) {
                ours.click()
                clickedOurs = true
            } else {
                val cells = device.findObjects(By.clickable(true)).filter {
                    val b = it.visibleBounds
                    b.centerY() > 500 && b.width() in 250..520 && b.height() > 250
                }
                assertFalse(
                    "no grid cells in the picker:\n${hierarchySnippet(device)}",
                    cells.isEmpty(),
                )
                // Select-access picker: the first click selects the item.
                cells.first().click()
            }

            // Multi-select mode confirms with an Add button ("Add (1)" on the
            // select-access picker); if it never appears the picker confirmed
            // by itself and is already closing.
            device.wait(Until.findObject(By.textContains("Add")), 5_000)?.click()

            assertTrue(
                "app did not come back to the foreground after import; foreground=${device.currentPackageName}",
                waitForPackage(device, setOf(app), 20_000),
            )

            // The imported page must land on disk as a decodable JPEG.
            var page: File? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                page = (pageFiles() - before).firstOrNull()
                if (page != null) break
                Thread.sleep(500)
            }
            val newPage = page ?: throw AssertionError(
                "no page file appeared after import (clickedOurs=$clickedOurs)\n${hierarchySnippet(device)}",
            )

            val bitmap = Images.decodeScaled(newPage, maxDim = 64)
            assertNotNull("imported page is not a decodable image: $newPage", bitmap)
            if (clickedOurs) {
                val center = bitmap!!.getPixel(bitmap.width / 2, bitmap.height / 2)
                val matches = abs(Color.red(center) - 200) <= 24 &&
                    abs(Color.green(center) - 30) <= 24 &&
                    abs(Color.blue(center) - 30) <= 24
                assertTrue("imported page color changed: $center", matches)
            }
            bitmap?.recycle()
        } finally {
            target().contentResolver.delete(seeded, null, null)
        }
    }

    @Test
    fun batchImportThroughSystemPhotoPickerAddsAllPages() {
        val device = device()
        val stamp = System.currentTimeMillis()
        val seeded = listOf(
            seedGalleryImage(Color.rgb(30, 200, 30), "openscan-batch-$stamp-1.jpg"),
            seedGalleryImage(Color.rgb(30, 30, 200), "openscan-batch-$stamp-2.jpg"),
            seedGalleryImage(Color.rgb(200, 200, 30), "openscan-batch-$stamp-3.jpg"),
        )
        try {
            launchApp(device)
            val before = pageFiles()

            device.wait(Until.findObject(By.desc("Import images")), 10_000)?.click()
                ?: throw AssertionError("library screen never offered Import images")
            assertTrue(
                "the system photo picker did not open; foreground=${device.currentPackageName}",
                waitForPackage(device, pickerPackages, 15_000),
            )

            // The three seeded images are the most recent, so they head the
            // "Recent" grid: select the first three cells in reading order.
            val cells = device.findObjects(By.clickable(true))
                .filter {
                    val b = it.visibleBounds
                    b.centerY() > 400 && b.width() in 250..520 && b.height() > 250
                }
                .sortedWith(compareBy({ it.visibleBounds.top }, { it.visibleBounds.left }))
                .take(3)
            assertTrue("expected 3 grid cells in the picker, got ${cells.size}", cells.size == 3)
            cells.forEach { it.click() }

            // Multi-select confirms with an Add button.
            val add = device.wait(Until.findObject(By.textContains("Add")), 5_000)
            assertNotNull("no Add button after multi-select:\n${hierarchySnippet(device)}", add)
            add!!.click()

            assertTrue(
                "app did not come back to the foreground after import; foreground=${device.currentPackageName}",
                waitForPackage(device, setOf(app), 20_000),
            )

            // The document screen confirms the batch once the import ends.
            assertTrue(
                "the 'Added 3 pages' confirmation never appeared",
                device.wait(Until.hasObject(By.text("Added 3 pages")), 30_000),
            )

            // All three pages must be on disk as decodable JPEGs.
            var added: Set<File> = emptySet()
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                added = pageFiles() - before
                if (added.size >= 3) break
                Thread.sleep(500)
            }
            assertTrue("expected 3 new page files, found ${added.size}", added.size >= 3)
            added.forEach { file ->
                val bitmap = Images.decodeScaled(file, maxDim = 64)
                assertNotNull("imported page is not a decodable image: $file", bitmap)
                bitmap?.recycle()
            }
        } finally {
            seeded.forEach { target().contentResolver.delete(it, null, null) }
        }
    }

    @Test
    fun scanWithoutPlayServicesShowsTheFallbackInsteadOfCrashing() {
        // On devices WITH Play services the scanner opens (or shows its own
        // module-download UI) instead of failing into our fallback — the
        // premise of this test only holds on non-GMS images. Skip there.
        val hasPlayServices = runCatching {
            target().packageManager.getPackageInfo("com.google.android.gms", 0)
        }.isSuccess
        assumeTrue("device has Play services; the fallback path never shows there", !hasPlayServices)

        val device = device()
        launchApp(device)

        // The Scan FAB lives on the library screen; its Text() is not exposed
        // to accessibility queries, so match the icon's contentDescription.
        val scan = device.wait(Until.findObject(By.desc("Scan")), 5_000)
            ?: run {
                device.pressBack()
                device.wait(Until.findObject(By.desc("Scan")), 5_000)
            }
        assertNotNull("library screen with the Scan button never appeared", scan)
        scan!!.click()

        // CaptureScreen auto-starts the ML Kit scanner; on a device without
        // Play services the request fails and must degrade to the error +
        // gallery-import fallback, not crash.
        assertTrue(
            "scanner-unavailable fallback did not appear",
            device.wait(Until.hasObject(By.textContains("document scanner is unavailable")), 30_000),
        )
        assertTrue(
            device.wait(Until.hasObject(By.text("Import images from the gallery")), 5_000),
        )

        // Cancel is optional to the assertion — the point of this test is
        // that the scanner's absence degrades to the fallback, not a crash.
        device.wait(Until.findObject(By.text("Cancel")), 5_000)?.click()
        assertTrue(
            "app should stay in the foreground after canceling the capture flow",
            device.wait(Until.hasObject(By.text("OpenScan")), 10_000),
        )
    }
}
