package io.github.alexeygrigorev.openscan.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Release check policy — one GitHub `releases/latest` poll compared against
 * the installed versionName. The Kotlin port of PocketShell's
 * `releaseCheck.ts`, with the same honesty contract:
 *
 *  - `up-to-date` and `failed` stay distinct answers: a network blip must
 *    never read as "current";
 *  - the check never throws — every failure mode is a [UpdateCheckResult.Failed]
 *    with a human-readable reason;
 *  - nothing installs itself: an available result carries vetted GitHub URLs
 *    and handing them to the system browser is the user's act.
 */
sealed interface UpdateCheckResult {

    val currentVersion: String

    /** The latest release is not newer than the installed version. */
    data class UpToDate(override val currentVersion: String) : UpdateCheckResult

    /**
     * A newer release exists. Both URLs are vetted by [isTrustedReleaseUrl];
     * the download is for this install's APK flavour.
     */
    data class Available(
        override val currentVersion: String,
        val tagName: String,
        val downloadUrl: String,
        val notesUrl: String,
        val publishedAt: String?,
    ) : UpdateCheckResult

    /** The check itself failed; [reason] is shown where the check was asked for. */
    data class Failed(override val currentVersion: String, val reason: String) : UpdateCheckResult
}

/** One release asset worth considering for download. */
data class ReleaseAsset(val name: String, val downloadUrl: String)

data class ParsedVersion(val major: Int, val minor: Int, val patch: Int)

private val VERSION_PATTERN = Regex("^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+].*)?$")

/**
 * Parse `v0.5.3`, `0.5.3`, `0.6.0-dev` or `0.5.3-4-gabc123` into its numeric
 * release. A pre-release/build suffix is ignored: an installed `0.6.0-dev`
 * build is not offered `v0.6.0` as an "update" of itself.
 */
fun parseReleaseVersion(raw: String): ParsedVersion? {
    val match = VERSION_PATTERN.matchEntire(raw.trim()) ?: return null
    // Unmatched optional groups come back as "" and default to zero, so
    // `v1` parses as 1.0.0.
    return ParsedVersion(
        major = match.groupValues[1].toInt(),
        minor = match.groupValues[2].toIntOrNull() ?: 0,
        patch = match.groupValues[3].toIntOrNull() ?: 0,
    )
}

/** True only when [tag] is strictly newer than [current]; an unreadable side is never an update. */
fun isNewerRelease(tag: String, current: String): Boolean {
    val remote = parseReleaseVersion(tag) ?: return false
    val installed = parseReleaseVersion(current) ?: return false
    if (remote.major != installed.major) return remote.major > installed.major
    if (remote.minor != installed.minor) return remote.minor > installed.minor
    return remote.patch > installed.patch
}

/** `v0.1.2` for display; an unparseable name is shown trimmed rather than invented. */
fun releaseVersionLabel(versionName: String): String {
    val parsed = parseReleaseVersion(versionName)
    if (parsed != null) return "v${parsed.major}.${parsed.minor}.${parsed.patch}"
    return versionName.trim().replace(Regex("^[vV]"), "").ifEmpty { "unknown" }
}

/**
 * Pick the APK for this build flavour: an `openscan…-debug.apk` for a debug
 * install, `…-release.apk` (today: `…-foss-release.apk`, the only flavour
 * published) for a release install; else the first APK. When the play flavour
 * returns to the release page this needs flavour matching — see issue #2.
 */
fun pickApkAsset(assets: List<ReleaseAsset>, preferRelease: Boolean): String? {
    var firstApk: String? = null
    for (asset in assets) {
        val name = asset.name.lowercase()
        if (!name.endsWith(".apk")) continue
        val variant = if (preferRelease) name.contains("-release.apk") else name.contains("-debug.apk")
        if (variant && name.contains("openscan")) return asset.downloadUrl
        if (firstApk == null) firstApk = asset.downloadUrl
    }
    return firstApk
}

private val RELEASE_HOSTS =
    setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

/** Only GitHub HTTPS release links may be handed to the system browser. */
fun isTrustedReleaseUrl(url: String): Boolean = try {
    val parsed = URI(url)
    if (parsed.scheme != "https") {
        false
    } else {
        parsed.host?.lowercase()?.let { it in RELEASE_HOSTS } == true
    }
} catch (_: URISyntaxException) {
    false
}

/**
 * Map one `releases/latest` payload to the three-way result. JSON parsing
 * may throw [JSONException]; [ReleaseChecker.check] turns that into `failed`.
 */
fun parseLatestRelease(body: String, currentVersion: String, preferReleaseApk: Boolean): UpdateCheckResult {
    val doc = JSONObject(body)
    val tagName = doc.optString("tag_name").trim()
    if (tagName.isEmpty()) {
        return UpdateCheckResult.Failed(currentVersion, "latest release has no tag")
    }
    if (parseReleaseVersion(tagName) == null) {
        return UpdateCheckResult.Failed(currentVersion, "latest release tag is not a version: ${tagName.take(40)}")
    }
    if (parseReleaseVersion(currentVersion) == null) {
        return UpdateCheckResult.Failed(currentVersion, "installed version is unknown")
    }
    if (!isNewerRelease(tagName, currentVersion)) {
        return UpdateCheckResult.UpToDate(currentVersion)
    }

    val notesUrl = doc.optString("html_url")
    if (!isTrustedReleaseUrl(notesUrl)) {
        return UpdateCheckResult.Failed(currentVersion, "release $tagName has no release page")
    }

    val assets = mutableListOf<ReleaseAsset>()
    val jsonAssets: JSONArray? = doc.optJSONArray("assets")
    if (jsonAssets != null) {
        for (i in 0 until jsonAssets.length()) {
            val asset = jsonAssets.getJSONObject(i)
            val name = asset.optString("name")
            val downloadUrl = asset.optString("browser_download_url")
            if (name.isNotEmpty() && downloadUrl.isNotEmpty()) {
                assets.add(ReleaseAsset(name, downloadUrl))
            }
        }
    }
    val downloadUrl = pickApkAsset(assets, preferReleaseApk)
    if (downloadUrl == null || !isTrustedReleaseUrl(downloadUrl)) {
        return UpdateCheckResult.Failed(currentVersion, "release $tagName has no download for this platform")
    }

    val publishedAt = doc.optString("published_at").takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(it) }
    return UpdateCheckResult.Available(currentVersion, tagName, downloadUrl, notesUrl, publishedAt)
}

/** Wording for HTTP failures: 403 is GitHub's unauthenticated rate limit. */
fun releaseHttpFailureReason(status: Int): String =
    if (status == 403) "rate-limited, try again later" else "server error (HTTP $status)"

/** The response the [ReleaseChecker.fetch] seam hands back. */
data class ReleaseResponse(val status: Int, val body: String?)

class ReleaseChecker(
    /** The installed versionName; the release tag is compared against it. */
    val currentVersion: String,
    /**
     * Which published APK this install should be offered: release installs get
     * `…-release.apk`, debug installs `…-debug.apk`.
     */
    private val preferReleaseApk: Boolean,
    private val endpoint: String = RELEASES_API,
    private val fetch: (URL) -> ReleaseResponse = ::httpGet,
) {

    /**
     * One GitHub `releases/latest` poll on the IO dispatcher. Never throws:
     * every failure mode, transport included, is a `Failed` answer.
     */
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) { checkNow() }

    /** Blocking core of [check]; exposed for tests. */
    fun checkNow(): UpdateCheckResult = try {
        val response = fetch(URL(endpoint))
        if (response.status !in 200..299) {
            UpdateCheckResult.Failed(currentVersion, releaseHttpFailureReason(response.status))
        } else {
            val body = response.body
            if (body == null) {
                UpdateCheckResult.Failed(currentVersion, "unreadable release response")
            } else {
                parseLatestRelease(body, currentVersion, preferReleaseApk)
            }
        }
    } catch (_: SocketTimeoutException) {
        UpdateCheckResult.Failed(currentVersion, "timed out")
    } catch (_: IOException) {
        UpdateCheckResult.Failed(currentVersion, "no network connection")
    } catch (_: JSONException) {
        UpdateCheckResult.Failed(currentVersion, "unreadable release response")
    }

    companion object {
        private const val TAG = "ReleaseChecker"

        /** This app's release feed on GitHub. */
        const val RELEASES_API = "https://api.github.com/repos/alexeygrigorev/openscan/releases/latest"

        private const val TIMEOUT_MS = 10_000

        /**
         * Blocking GET of [url] as GitHub's JSON; the body is read only on
         * success, error statuses carry their code alone. Throws are handled
         * by [checkNow].
         */
        fun httpGet(url: URL): ReleaseResponse {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
                }
                return ReleaseResponse(code, body)
            } finally {
                connection.disconnect()
            }
        }

        /**
         * The install handoff: a vetted GitHub link goes to the system
         * browser through `ACTION_VIEW`. Returns false (and logs) when the
         * URL is not a trusted release link or no browser exists — the
         * checker itself never installs anything.
         */
        fun openReleaseUrl(context: Context, url: String): Boolean {
            if (!isTrustedReleaseUrl(url)) {
                Log.w(TAG, "refusing to open non-release URL")
                return false
            }
            return try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (_: ActivityNotFoundException) {
                Log.w(TAG, "no activity to open the release URL")
                false
            }
        }
    }
}
