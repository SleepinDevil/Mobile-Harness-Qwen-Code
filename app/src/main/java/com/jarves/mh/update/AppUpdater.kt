package com.jarves.mh.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/** The two update feeds: GitHub releases (upstream-locked versions) and the fork's rolling debug prerelease. */
enum class AppUpdateChannel { STABLE, DEBUG }

data class AppUpdateInfo(
    val channel: AppUpdateChannel,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

/**
 * Parses one channel manifest and turns it into an offer relative to the
 * installed build, or null when there is nothing to offer.
 *
 * Version-code buckets: stable releases use upstream's versionCode times
 * 10,000 (upstream 11 -> 110000), and rolling debug betas for a release live
 * strictly between it and the next stable (110001..119999, then 120000).
 * Every direction — stable to beta, beta to beta, beta to the next
 * stable — is therefore a numeric upgrade, so one rule suffices for both
 * feeds: offer the manifest when its versionCode exceeds the installed one.
 * Equality (the build already installed) is suppressed by the same rule.
 */
internal fun parseAppUpdateManifest(
    body: String,
    channel: AppUpdateChannel,
    variant: String,
    installedCode: Long,
): AppUpdateInfo? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val versionCode = root.optLong("versionCode")
    if (versionCode <= installedCode) return null
    val artifact = root.optJSONObject("artifacts")?.optJSONObject(variant)
        ?: root.optJSONObject(variant)
        ?: root
    val url = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }
    if (!url.startsWith("https://")) return null
    return AppUpdateInfo(
        channel = channel,
        versionCode = versionCode,
        versionName = root.optString("versionName", versionCode.toString()),
        apkUrl = url,
        sha256 = artifact.optString("sha256").lowercase(),
        sizeBytes = artifact.optLong("sizeBytes", -1L),
        notes = root.optString("notes"),
    )
}

/**
 * Merges both channel feeds into user-facing offers, highest versionCode
 * first. Both feeds always compare numerically against the installed code.
 */
internal fun appUpdateOffers(
    stableBody: String?,
    debugBody: String?,
    variant: String,
    installedCode: Long,
): List<AppUpdateInfo> = listOfNotNull(
    stableBody?.let { parseAppUpdateManifest(it, AppUpdateChannel.STABLE, variant, installedCode) },
    debugBody?.let { parseAppUpdateManifest(it, AppUpdateChannel.DEBUG, variant, installedCode) },
).sortedByDescending { it.versionCode }

class AppUpdater(
    private val context: Context,
    /**
     * Optional STABLE-manifest URL override. When non-empty, used in place of
     * [BuildConfig.APP_UPDATE_MANIFEST_URL]. Debug builds populate this
     * from Settings → Update channel so the update flow can be exercised
     * end-to-end against a Cloudflare Tunnel or ngrok HTTPS URL without
     * publishing a release to GitHub.
     */
    private val manifestUrlOverride: String = "",
) {
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        AppStrings.get(context, id, *args)

    /** Fetches both channel manifests; an unreachable feed simply yields no offer. */
    fun check(): List<AppUpdateInfo> = appUpdateOffers(
        stableBody = fetch(manifestUrlOverride.ifBlank { BuildConfig.APP_UPDATE_MANIFEST_URL }),
        debugBody = fetch(BuildConfig.APP_UPDATE_DEBUG_MANIFEST_URL),
        variant = BuildConfig.APP_VARIANT,
        installedCode = BuildConfig.VERSION_CODE.toLong(),
    )

    private fun fetch(manifestUrl: String): String? {
        if (!manifestUrl.startsWith("https://")) return null
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        return runCatching {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
            .also { connection.disconnect() }
    }

    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
        val directory = File(context.filesDir, "updates").also { it.mkdirs() }
        val partial = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk.part")
        val target = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk")
        val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            check(code in 200..299) { str(R.string.rt_update_download_failed, code) }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        progress(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (info.sha256.isNotBlank()) {
            val actual = sha256(partial)
            check(actual.equals(info.sha256, ignoreCase = true)) { str(R.string.rt_update_checksum_failed) }
        }
        verifyApk(partial, info.versionCode)
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { str(R.string.rt_update_prepare_failed) }
        return target
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedVersionCode: Long) {
        // Android 9/10 archive parsing can leave signingInfo null, so also request the legacy
        // signatures and fall back to them rather than reporting a false key mismatch.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error(str(R.string.rt_update_invalid_apk))
        check(archive.packageName == context.packageName) { str(R.string.rt_update_package_mismatch) }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        check(archiveVersion == expectedVersionCode && archiveVersion > BuildConfig.VERSION_CODE) { str(R.string.rt_update_version_mismatch) }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installed)
        check(archiveSigners.isNotEmpty() && archiveSigners == installedSigners) { str(R.string.rt_update_signature_mismatch) }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: info.signatures
        } else {
            info.signatures
        }
        return signers.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
