package com.eddyizm.tempus.github.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.github.utils.PiterfyUpdateLogic.Update
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Piterfy: aktualizacje z poziomu aplikacji, ten sam mechanizm co w Szpili i Kropi. Ostatnie wydanie GitHuba to kanał,
 * APK trafia do cache, przechodzi kontrolę (SHA-256 z GitHuba, nazwa pakietu, nowszy versionCode, ten sam podpis)
 * i dopiero wtedy do systemowego instalatora, który prosi użytkownika o potwierdzenie.
 *
 * Metody `check*`, `fetchLatest` i `backgroundCheck` blokują: wołać z wątku w tle.
 * Stan pobierania ([state]) przeżywa obrót ekranu, bo dialog tylko go pokazuje.
 */
object PiterfyUpdateManager {
    private const val PREFS = "piterfy_update"
    private const val KEY_CHECKED = "checked_at"
    private const val KEY_FOUND = "found_json"
    private const val KEY_NOTIFIED = "notified"
    private const val KEY_LATER_AT = "later_at"
    private const val KEY_LATER_VERSION = "later_version"
    const val EXTRA_OPEN_UPDATE = "piterfy_open_update"

    enum class Kind { IDLE, DOWNLOADING, NEED_PERMISSION, READY, ERROR }

    data class State(
        val kind: Kind = Kind.IDLE,
        val update: Update? = null,
        val received: Long = 0,
        val total: Long = 0,
        val message: String = "",
    ) {
        val percent: Int get() = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    @Volatile var state: State = State()
        private set
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false

    fun addListener(l: (State) -> Unit) { listeners.add(l) }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }
    private fun set(next: State) {
        state = next
        main.post { listeners.forEach { it(next) } }
    }

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Wersje debug (inny pakiet, inny podpis) nie aktualizują się z wydań. */
    fun supported(): Boolean = !BuildConfig.DEBUG

    fun installedVersionName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty()

    private fun codeOf(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()

    private fun installedVersionCode(c: Context): Long = codeOf(c.packageManager.getPackageInfo(c.packageName, 0))

    // ── sprawdzanie ──

    /** Pyta GitHuba o ostatnie wydanie dla tego wariantu. null = brak użytecznego wydania; błąd sieci rzuca wyjątek. */
    @Throws(IOException::class)
    fun fetchLatest(): Update? {
        val response = App.getGithubClientInstance().releaseClient.latestRelease.execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
        return PiterfyUpdateLogic.pick(response.body(), BuildConfig.FLAVOR)
    }

    /** Ręczne sprawdzenie: nowsze wydanie albo null (jesteś na bieżąco). Błąd sieci rzuca wyjątek. */
    @Throws(IOException::class)
    fun checkNow(c: Context): Update? {
        val latest = fetchLatest()
        val newer = latest?.takeIf { PiterfyUpdateLogic.isNewer(installedVersionName(c), it.version) }
        prefs(c).edit().putLong(KEY_CHECKED, System.currentTimeMillis())
            .putString(KEY_FOUND, newer?.let { Gson().toJson(it) } ?: "").apply()
        return newer
    }

    /** Ostatnio znalezione wydanie, jeśli nadal nowsze od zainstalowanego (do pokazania bez sieci). */
    fun hint(c: Context): Update? {
        val json = prefs(c).getString(KEY_FOUND, "").orEmpty()
        if (json.isEmpty()) return null
        val update = runCatching { Gson().fromJson(json, Update::class.java) }.getOrNull() ?: return null
        return update.takeIf { PiterfyUpdateLogic.isNewer(installedVersionName(c), it.version) }
    }

    /**
     * Automatyczne sprawdzenie przy starcie i powrocie do aplikacji: pyta GitHuba najwyżej co 3 godziny, a w
     * międzyczasie korzysta z zapamiętanego wyniku. „Później” ukrywa tę wersję na 24 godziny.
     */
    fun autoCheck(c: Context): Update? {
        if (!supported()) return null
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val found = if (PiterfyUpdateLogic.checkDue(now, p.getLong(KEY_CHECKED, 0))) {
            runCatching { checkNow(c) }.getOrElse { hint(c) }
        } else hint(c)
        found ?: return null
        return found.takeUnless {
            PiterfyUpdateLogic.laterActive(now, p.getLong(KEY_LATER_AT, 0), it.version, p.getString(KEY_LATER_VERSION, null))
        }
    }

    /** Zadanie w tle (najwyżej co 6 h): zwraca wydanie tylko za pierwszym razem, żeby powiadomić raz na wersję. */
    fun backgroundCheck(c: Context): Update? {
        if (!supported()) return null
        val p = prefs(c)
        val now = System.currentTimeMillis()
        if (!PiterfyUpdateLogic.checkDue(now, p.getLong(KEY_CHECKED, 0), PiterfyUpdateLogic.BACKGROUND_EVERY_MS)) return null
        val newer = runCatching { checkNow(c) }.getOrNull() ?: return null
        if (newer.version == p.getString(KEY_NOTIFIED, "")) return null
        p.edit().putString(KEY_NOTIFIED, newer.version).apply()
        return newer
    }

    fun later(c: Context, update: Update) {
        prefs(c).edit().putLong(KEY_LATER_AT, System.currentTimeMillis()).putString(KEY_LATER_VERSION, update.version).apply()
    }

    // ── pobieranie i instalacja ──

    private fun updatesDir(c: Context): File = File(c.cacheDir, "updates").also { it.mkdirs() }

    /** Pobiera, sprawdza i (gdy system pozwala) uruchamia instalator. Postęp i błędy trafiają do [state]. */
    fun start(c: Context, update: Update) {
        if (state.kind == Kind.DOWNLOADING) return
        val app = c.applicationContext
        cancelled = false
        set(State(Kind.DOWNLOADING, update))
        io.execute {
            try {
                val apk = download(app, update) { received, total ->
                    if (!cancelled) set(State(Kind.DOWNLOADING, update, received, total))
                }
                if (cancelled) { apk.delete(); set(State()); return@execute }
                val problem = verify(app, apk, update.sha256)
                if (problem != null) {
                    apk.delete()
                    set(State(Kind.ERROR, update, message = problem))
                } else proceed(app, update, apk)
            } catch (e: Exception) {
                if (cancelled) set(State()) else
                    set(State(Kind.ERROR, update, message = app.getString(R.string.update_err_download, e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    /** Wznawia po przyznaniu zgody na instalowanie aplikacji (pobrany plik czeka w cache). */
    fun resume(c: Context) {
        val update = state.update ?: return
        val apk = File(updatesDir(c), "piterfy-update.apk")
        if (!apk.exists()) { start(c, update); return }
        proceed(c.applicationContext, update, apk)
    }

    private fun proceed(c: Context, update: Update, apk: File) {
        if (!canInstall(c)) { set(State(Kind.NEED_PERMISSION, update, message = c.getString(R.string.update_allow_install))); return }
        set(State(Kind.READY, update, message = c.getString(R.string.update_ready)))
        main.post { install(c, apk) }
    }

    fun cancel() { cancelled = true }
    fun reset() { if (state.kind != Kind.DOWNLOADING) set(State()) }

    private fun download(c: Context, update: Update, onProgress: (Long, Long) -> Unit): File {
        require(PiterfyUpdateLogic.allowedUrl(update.apkUrl)) { "Not a Piterfy release APK" }
        updatesDir(c).listFiles()?.forEach { it.delete() }
        val out = File(updatesDir(c), "piterfy-update.apk")
        val conn = URL(update.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true  // GitHub przekierowuje do swojego magazynu (https → https)
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/octet-stream")
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.size
            conn.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    var lastReport = 0L
                    while (!cancelled) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        received += n
                        if (received - lastReport >= 256 * 1024) { lastReport = received; onProgress(received, total) }
                    }
                    onProgress(received, total)
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** null, gdy APK można zainstalować; inaczej krótki powód. */
    fun verify(c: Context, apk: File, expectedSha: String): String? = try {
        val want = PiterfyUpdateLogic.normalizeSha(expectedSha)
        val pm = c.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
        when {
            want.isNotEmpty() && want != sha256(apk) -> c.getString(R.string.update_err_checksum)
            archive == null -> c.getString(R.string.update_err_not_app)
            archive.packageName != c.packageName -> c.getString(R.string.update_err_other_app)
            codeOf(archive) <= installedVersionCode(c) -> c.getString(R.string.update_err_same_version)
            else -> {
                val installed = pm.getPackageInfo(c.packageName, flags)
                val a = signers(archive)
                if (a.isEmpty() || signers(installed) != a) c.getString(R.string.update_err_other_key) else null
            }
        }
    } catch (e: Exception) {
        c.getString(R.string.update_err_generic, e.message ?: e.javaClass.simpleName)
    }

    private fun signers(pi: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pi.signingInfo ?: return emptySet()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") pi.signatures
        }
        return sigs?.map { it.toByteArray().contentToString() }?.toSet().orEmpty()
    }

    fun canInstall(c: Context): Boolean = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** Systemowy ekran „Instaluj nieznane aplikacje” dla Piterfy. */
    fun openInstallPermission(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Oddaje pobrany APK systemowemu instalatorowi (sam poprosi użytkownika o potwierdzenie). */
    fun install(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.update.fileprovider", apk)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
