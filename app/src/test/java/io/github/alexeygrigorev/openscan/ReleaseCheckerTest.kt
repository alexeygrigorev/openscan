package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.update.ReleaseAsset
import io.github.alexeygrigorev.openscan.update.ReleaseChecker
import io.github.alexeygrigorev.openscan.update.ReleaseResponse
import io.github.alexeygrigorev.openscan.update.UpdateCheckResult
import io.github.alexeygrigorev.openscan.update.isNewerRelease
import io.github.alexeygrigorev.openscan.update.isTrustedReleaseUrl
import io.github.alexeygrigorev.openscan.update.parseLatestRelease
import io.github.alexeygrigorev.openscan.update.parseReleaseVersion
import io.github.alexeygrigorev.openscan.update.pickApkAsset
import io.github.alexeygrigorev.openscan.update.releaseHttpFailureReason
import io.github.alexeygrigorev.openscan.update.releaseVersionLabel
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release-check policy, tested against fake fetches so no network or
 * Android framework is involved. Mirrors PocketShell's releaseCheck contract:
 * up-to-date and failed stay distinct, and every payload oddity is a `failed`
 * answer rather than an invented "current".
 */
class ReleaseCheckerTest {

    private val current = "0.1.2"

    private fun checker(
        currentVersion: String = current,
        preferRelease: Boolean = true,
        response: ReleaseResponse? = null,
        error: Exception? = null,
    ) = ReleaseChecker(
        currentVersion = currentVersion,
        preferReleaseApk = preferRelease,
        fetch = {
            if (error != null) throw error else response!!
        },
    )

    private fun latestReleaseBody(
        tag: String = "v0.2.0",
        htmlUrl: String = "https://github.com/alexeygrigorev/openscan/releases/tag/$tag",
        assets: String = """
            {"name": "openscan-0.2.0-debug.apk",
             "browser_download_url": "https://github.com/alexeygrigorev/openscan/releases/download/$tag/openscan-0.2.0-debug.apk"},
            {"name": "openscan-0.2.0-release.apk",
             "browser_download_url": "https://github.com/alexeygrigorev/openscan/releases/download/$tag/openscan-0.2.0-release.apk"}
        """.trimIndent(),
        publishedAt: String = "2026-10-02T17:37:36Z",
    ) = """
        {"tag_name": "$tag",
         "html_url": "$htmlUrl",
         "published_at": "$publishedAt",
         "assets": [$assets]}
    """.trimIndent()

    // --- parseReleaseVersion -------------------------------------------------

    @Test
    fun `parses v-prefixed two and three part versions`() {
        assertEquals(0, parseReleaseVersion("v0.1.2")!!.major)
        assertEquals(2, parseReleaseVersion("0.1.2")!!.patch)
        assertEquals(2, parseReleaseVersion("V1.2")!!.minor)
        assertEquals(0, parseReleaseVersion("3")!!.minor)
        assertEquals(0, parseReleaseVersion("3")!!.patch)
    }

    @Test
    fun `pre-release and build suffixes are ignored`() {
        assertEquals(0, parseReleaseVersion("0.6.0-dev")!!.patch)
        assertEquals(3, parseReleaseVersion("0.5.3-4-gabc123")!!.patch)
        assertEquals(0, parseReleaseVersion("1.0.0+build.7")!!.patch)
    }

    @Test
    fun `garbage versions do not parse`() {
        assertNull(parseReleaseVersion(""))
        assertNull(parseReleaseVersion("latest"))
        assertNull(parseReleaseVersion("v1.x.0"))
        assertNull(parseReleaseVersion("release-1"))
    }

    // --- isNewerRelease ------------------------------------------------------

    @Test
    fun `strictly newer by major minor then patch`() {
        assertTrue(isNewerRelease("v0.2.0", "0.1.9"))
        assertTrue(isNewerRelease("v0.1.3", "0.1.2"))
        assertTrue(isNewerRelease("1.0.0", "0.9.9"))
        assertFalse(isNewerRelease("v0.1.2", "0.1.2"))
        assertFalse(isNewerRelease("v0.1.1", "0.1.2"))
        // An installed -dev build of the released version is not offered itself.
        assertFalse(isNewerRelease("v0.6.0", "0.6.0-dev"))
    }

    @Test
    fun `an unreadable side is never an update`() {
        assertFalse(isNewerRelease("not-a-version", "0.1.2"))
        assertFalse(isNewerRelease("v0.2.0", ""))
    }

    // --- releaseVersionLabel -------------------------------------------------

    @Test
    fun `label is v-prefixed normalized`() {
        assertEquals("v0.1.2", releaseVersionLabel("0.1.2"))
        assertEquals("v0.1.2", releaseVersionLabel("v0.1.2"))
        assertEquals("v0.1.0", releaseVersionLabel("0.1"))
        assertEquals("banana", releaseVersionLabel("banana"))
        assertEquals("unknown", releaseVersionLabel(""))
    }

    // --- pickApkAsset --------------------------------------------------------

    private val assets = listOf(
        ReleaseAsset(
            "openscan-0.2.0-release.aab",
            "https://github.com/alexeygrigorev/openscan/releases/download/v0.2.0/openscan-0.2.0-release.aab",
        ),
        ReleaseAsset(
            "openscan-0.2.0-debug.apk",
            "https://github.com/alexeygrigorev/openscan/releases/download/v0.2.0/openscan-0.2.0-debug.apk",
        ),
        ReleaseAsset(
            "openscan-0.2.0-release.apk",
            "https://github.com/alexeygrigorev/openscan/releases/download/v0.2.0/openscan-0.2.0-release.apk",
        ),
    )

    @Test
    fun `release install prefers the release apk`() {
        val picked = pickApkAsset(assets, preferRelease = true)
        assertTrue(picked!!.endsWith("openscan-0.2.0-release.apk"))
    }

    @Test
    fun `debug install prefers the debug apk`() {
        val picked = pickApkAsset(assets, preferRelease = false)
        assertTrue(picked!!.endsWith("openscan-0.2.0-debug.apk"))
    }

    @Test
    fun `falls back to the first apk when no flavour matches`() {
        val picked = pickApkAsset(
            listOf(ReleaseAsset("other-app-1.0.apk", "https://github.com/x/other-1.0.apk")),
            preferRelease = true,
        )
        assertEquals("https://github.com/x/other-1.0.apk", picked)
    }

    @Test
    fun `no apk asset means no download`() {
        assertNull(pickApkAsset(assets.filter { it.name.endsWith(".aab") }, preferRelease = true))
        assertNull(pickApkAsset(emptyList(), preferRelease = false))
    }

    // --- isTrustedReleaseUrl -------------------------------------------------

    @Test
    fun `only github https hosts are trusted`() {
        assertTrue(isTrustedReleaseUrl("https://github.com/alexeygrigorev/openscan/releases/tag/v0.2.0"))
        assertTrue(
            isTrustedReleaseUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/x",
            ),
        )
        assertFalse(isTrustedReleaseUrl("http://github.com/alexeygrigorev/openscan/releases/tag/v0.2.0"))
        assertFalse(isTrustedReleaseUrl("https://evil.example.com/openscan.apk"))
        assertFalse(isTrustedReleaseUrl("https://github.com.evil.example.com/openscan.apk"))
        assertFalse(isTrustedReleaseUrl("not a url"))
    }

    // --- parseLatestRelease --------------------------------------------------

    @Test
    fun `newer release parses to available with flavour asset`() {
        val result = parseLatestRelease(latestReleaseBody(), current, preferReleaseApk = true)
        val available = result as UpdateCheckResult.Available
        assertEquals("v0.2.0", available.tagName)
        assertEquals(current, available.currentVersion)
        assertTrue(available.downloadUrl.endsWith("openscan-0.2.0-release.apk"))
        assertEquals("https://github.com/alexeygrigorev/openscan/releases/tag/v0.2.0", available.notesUrl)
        assertEquals("2026-10-02T17:37:36Z", available.publishedAt)
    }

    @Test
    fun `same release parses to up-to-date`() {
        val result = parseLatestRelease(latestReleaseBody(tag = "v0.1.2"), current, preferReleaseApk = true)
        assertEquals(UpdateCheckResult.UpToDate(current), result)
    }

    @Test
    fun `missing or non-version tag is failed not up-to-date`() {
        val noTag = """{"html_url": "https://github.com/x"}"""
        val result = parseLatestRelease(noTag, current, preferReleaseApk = true)
        assertEquals("latest release has no tag", (result as UpdateCheckResult.Failed).reason)

        val weirdTag = latestReleaseBody(tag = "autobuild-2026")
        val failed = parseLatestRelease(weirdTag, current, preferReleaseApk = true) as UpdateCheckResult.Failed
        assertTrue(failed.reason.startsWith("latest release tag is not a version"))
    }

    @Test
    fun `unknown installed version is failed`() {
        val result = parseLatestRelease(latestReleaseBody(), "banana", preferReleaseApk = true)
        assertEquals("installed version is unknown", (result as UpdateCheckResult.Failed).reason)
    }

    @Test
    fun `untrusted release page is failed`() {
        val body = latestReleaseBody(htmlUrl = "https://evil.example.com/releases/v0.2.0")
        val result = parseLatestRelease(body, current, preferReleaseApk = true)
        assertEquals("release v0.2.0 has no release page", (result as UpdateCheckResult.Failed).reason)
    }

    @Test
    fun `no matching apk asset is failed`() {
        val body = latestReleaseBody(
            assets = """
                {"name": "openscan-0.2.0-release.aab",
                 "browser_download_url": "https://github.com/alexeygrigorev/openscan/releases/download/v0.2.0/openscan-0.2.0-release.aab"}
            """.trimIndent(),
        )
        val result = parseLatestRelease(body, current, preferReleaseApk = true)
        assertEquals(
            "release v0.2.0 has no download for this platform",
            (result as UpdateCheckResult.Failed).reason,
        )
    }

    // --- checkNow (transport + policy end to end) ----------------------------

    @Test
    fun `a successful poll answers available`() = runBlocking {
        val result = checker(response = ReleaseResponse(200, latestReleaseBody())).check()
        assertTrue(result is UpdateCheckResult.Available)
    }

    @Test
    fun `403 is the rate limit and stays distinct from up-to-date`() = runBlocking {
        val result = checker(response = ReleaseResponse(403, null)).check()
        assertEquals(
            UpdateCheckResult.Failed(current, "rate-limited, try again later"),
            result,
        )
    }

    @Test
    fun `other http failures name the status`() = runBlocking {
        val result = checker(response = ReleaseResponse(503, null)).check()
        assertEquals(UpdateCheckResult.Failed(current, "server error (HTTP 503)"), result)
    }

    @Test
    fun `unparsable body is failed not up-to-date`() = runBlocking {
        val result = checker(response = ReleaseResponse(200, "<html>gateway</html>")).check()
        assertEquals(UpdateCheckResult.Failed(current, "unreadable release response"), result)
    }

    @Test
    fun `transport errors are failed with distinct reasons`() = runBlocking {
        val timedOut = checker(error = SocketTimeoutException()).check()
        assertEquals(UpdateCheckResult.Failed(current, "timed out"), timedOut)

        val offline = checker(error = java.io.IOException("network unreachable")).check()
        assertEquals(UpdateCheckResult.Failed(current, "no network connection"), offline)
    }

    @Test
    fun `the endpoint is this repository's release feed`() {
        assertEquals(
            "https://api.github.com/repos/alexeygrigorev/openscan/releases/latest",
            ReleaseChecker.RELEASES_API,
        )
        // The default fetch target must be a parseable URL.
        URL(ReleaseChecker.RELEASES_API)
    }

    @Test
    fun `http failure wording`() {
        assertEquals("rate-limited, try again later", releaseHttpFailureReason(403))
        assertEquals("server error (HTTP 429)", releaseHttpFailureReason(429))
    }
}
