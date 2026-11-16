package com.eddyizm.tempus.github.utils

import com.eddyizm.tempus.github.models.LatestRelease
import java.util.Locale

/**
 * Piterfy: czysta logika aktualizacji z GitHuba (bez Androida, testowana na JVM), ta sama zasada co w Szpili i Kropi:
 * kanałem jest ostatnie wydanie (nie szkic, nie pre-release), pobieramy wyłącznie APK z wydań tego repozytorium,
 * a wariant (tempus / degoogled) dobiera plik `app-<wariant>-release.apk`. Wersje mają postać `4.29.0-piterfy.14`.
 */
object PiterfyUpdateLogic {
    const val REPO = "pi0trdotsys/piterfy-android"
    const val ALLOWED_PREFIX = "https://github.com/$REPO/releases/download/"

    data class Update(
        /** „4.29.0-piterfy.14”: tag bez początkowego „v”. */
        val version: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val size: Long,
        /** Mała litera hex SHA-256, który GitHub podaje dla pliku, albo „”. */
        val sha256: String,
        val htmlUrl: String,
    )

    fun assetName(flavor: String) = "app-$flavor-release.apk"

    /** Tylko pliki z wydań tego repozytorium (bez wielkości liter, bez „..”). */
    fun allowedUrl(url: String?): Boolean {
        if (url == null) return false
        val u = url.lowercase(Locale.ROOT)
        return u.startsWith(ALLOWED_PREFIX.lowercase(Locale.ROOT)) && u.endsWith(".apk") && !url.contains("..")
    }

    /** „sha256:abc…” albo „abc…” → hex małymi literami, albo „” gdy brak lub to nie SHA-256. */
    fun normalizeSha(digest: String?): String {
        var d = digest?.trim()?.lowercase(Locale.ROOT) ?: return ""
        if (d.startsWith("sha256:")) d = d.removePrefix("sha256:")
        return if (Regex("[0-9a-f]{64}").matches(d)) d else ""
    }

    /** Czy tag wydania jest nowszy od zainstalowanej wersji (obie w postaci `x.y.z-piterfy.n`). */
    fun isNewer(current: String?, remote: String?): Boolean = UpdateUtil.isNewer(current, remote)

    /** Wydanie jako aktualizacja dla danego wariantu albo null (szkic, pre-release, obcy tag, brak dozwolonego APK). */
    fun pick(release: LatestRelease?, flavor: String): Update? {
        if (release == null || release.draft == true || release.prerelease == true) return null
        val tag = release.tagName ?: return null
        val version = tag.removePrefix("v").removePrefix("V")
        if (!Regex("\\d+\\.\\d+\\.\\d+-piterfy\\.\\d+").matches(version)) return null
        val wanted = assetName(flavor)
        val asset = release.assets.firstOrNull { it.name.equals(wanted, ignoreCase = true) && allowedUrl(it.browserDownloadUrl) }
            ?: return null
        return Update(
            version = version,
            title = release.name?.takeIf { it.isNotBlank() } ?: "Piterfy $version",
            notes = release.body.orEmpty(),
            apkUrl = asset.browserDownloadUrl!!,
            apkName = asset.name ?: wanted,
            size = (asset.size ?: 0).toLong(),
            sha256 = normalizeSha(asset.digest),
            htmlUrl = release.htmlUrl.orEmpty(),
        )
    }

    /** Punkty z opisu wydania (linie zaczynające się od „- ”), bez znaczników Markdown, najwyżej [max]. */
    fun notes(body: String?, max: Int = 8): List<String> =
        body.orEmpty().lines().map { it.trim() }.filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").replace("**", "").replace("`", "").trim() }
            .filter { it.isNotEmpty() }.take(max)

    /** „Później” ukrywa propozycję tej wersji na tyle czasu (ręczne sprawdzenie pokazuje ją zawsze). */
    const val LATER_MS = 24L * 60 * 60 * 1000

    /** Automatyczne pytanie GitHuba nie częściej niż co tyle (przy starcie i powrocie do aplikacji). */
    const val CHECK_EVERY_MS = 3L * 60 * 60 * 1000

    /** Zadanie w tle pyta najwyżej tak często. */
    const val BACKGROUND_EVERY_MS = 6L * 60 * 60 * 1000

    fun checkDue(now: Long, last: Long, every: Long = CHECK_EVERY_MS): Boolean = last <= 0 || now < last || now - last >= every

    fun laterActive(now: Long, laterAt: Long, version: String, laterVersion: String?): Boolean =
        laterVersion == version && laterAt > 0 && now >= laterAt && now - laterAt < LATER_MS
}
