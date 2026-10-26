package com.inspectionbrowser.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** A newer build on GitHub: its build number, name and where to download this app's APK. */
data class UpdateRelease(val build: Int, val name: String, val apkUrl: String, val bytes: Long)

/** Reading GitHub's releases (kept apart from Android so it can be tested). */
object UpdateReleases {
    /** The build number in a release tag like "v1.0.123", or null. */
    fun buildOf(tag: String): Int? = Regex("""^v?\d+\.\d+\.(\d+)$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** The newest release in GitHub's list [json] that's newer than [installed] and has [asset]. */
    fun newest(json: String, asset: String, installed: Int): UpdateRelease? {
        val list = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return null
        return list.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            if (o.str("draft") == "true") return@mapNotNull null
            val tag = o.str("tag_name") ?: return@mapNotNull null
            val build = buildOf(tag) ?: return@mapNotNull null
            val file = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("name") == asset } ?: return@mapNotNull null
            val url = file.str("browser_download_url") ?: return@mapNotNull null
            UpdateRelease(build, o.str("name")?.takeIf { it.isNotBlank() } ?: tag, url, file.str("size")?.toLongOrNull() ?: 0L)
        }.filter { it.build > installed }.maxByOrNull { it.build }
    }

    private fun JsonObject.str(k: String) = runCatching { this[k]?.jsonPrimitive?.content }.getOrNull()
}

/**
 * Keeps the app up to date from the project's GitHub releases: checks for a newer build,
 * downloads this app's APK and installs it over the top (bookmarks, cookies and settings are kept).
 * Android asks to confirm the first time; once the app has installed an update itself, later
 * ones go in without asking (Android 12 and newer).
 */
class AppUpdater(
    private val context: Context,
    private val asset: String,
    /** True while updating now would interrupt something : automatic updates wait. */
    private val busy: () -> Boolean = { false },
    /** Only download by itself on Wi-Fi. */
    private val wifiOnly: Boolean = true,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val build: Int) : State
        data class Available(val release: UpdateRelease) : State
        data class Downloading(val release: UpdateRelease, val progress: Float) : State
        data class Installing(val release: UpdateRelease) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _auto = MutableStateFlow(prefs.getBoolean("auto", true))
    /** Whether updates are checked for and put in by themselves. */
    val auto: StateFlow<Boolean> = _auto.asStateFlow()
    fun setAuto(on: Boolean) { prefs.edit().putBoolean("auto", on).apply(); _auto.value = on }

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val mutex = Mutex()

    @Suppress("DEPRECATION")
    val installedBuild: Int get() = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }.getOrDefault(0)

    /** Whether Android lets this app install updates (the "install unknown apps" switch). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Where to switch that on. */
    fun allowInstallsIntent() = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * On its own (when switched on): at most every few hours, and only on Wi-Fi or another
     * unmetered network, so a download never eats mobile data.
     */
    suspend fun checkInBackground() {
        if (!_auto.value) return
        if (System.currentTimeMillis() - prefs.getLong("lastCheck", 0L) < CHECK_EVERY_MS) return
        if ((wifiOnly && metered()) || busy()) return
        check(install = true)
    }

    /** Looks for a newer build and, with [install], downloads and installs it. Returns the newer one, if any. */
    suspend fun check(install: Boolean): UpdateRelease? = mutex.withLock {
        _state.value = State.Checking
        try {
            val release = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(RELEASES_URL).header("Accept", "application/vnd.github+json").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("GitHub said ${r.code}")
                    UpdateReleases.newest(r.body?.string().orEmpty(), asset, installedBuild)
                }
            }
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            if (release == null) { _state.value = State.UpToDate(installedBuild); return@withLock null }
            if (install) download(release)?.let { installApk(it, release) } else _state.value = State.Available(release)
            release
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Couldn't check for updates")
            null
        }
    }

    private suspend fun download(release: UpdateRelease): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "update-${release.build}.apk")
        _state.value = State.Downloading(release, 0f)
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Download failed (${r.code})")
            val body = r.body ?: throw IllegalStateException("Download failed")
            val total = body.contentLength().takeIf { it > 0 } ?: release.bytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L; var shown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0 && done - shown > total / 50) { shown = done; _state.value = State.Downloading(release, done.toFloat() / total) }
                    }
                }
            }
        }
        file
    }

    private suspend fun installApk(file: File, release: UpdateRelease) = withContext(Dispatchers.IO) {
        _state.value = State.Installing(release)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // After the first update it puts in itself, later ones need no tap (Android 12+).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    private fun metered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/evanrobinson1985/Inspection-browser/releases?per_page=10"
        private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L
        /** The installer's answer, for the screen to show. */
        val lastResult = MutableStateFlow<String?>(null)
    }
}

/** Android's answer to an install: asks to confirm when it needs to, and reports failures. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdater.lastResult.value = "Updated."
            else -> AppUpdater.lastResult.value = "The update didn't install: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason")
        }
    }
}
