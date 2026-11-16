package com.eddyizm.tempus.github.utils

import com.eddyizm.tempus.github.models.Assets
import com.eddyizm.tempus.github.models.LatestRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyUpdateLogicTest {
    private val base = "https://github.com/pi0trdotsys/piterfy-android/releases/download/v4.29.0-piterfy.14/"
    private val sha = "a".repeat(64)

    private fun asset(name: String, url: String = base + name, digest: String? = "sha256:$sha", size: Int = 11_000_000) =
        Assets(name = name, browserDownloadUrl = url, digest = digest, size = size)

    private fun release(tag: String = "v4.29.0-piterfy.14", draft: Boolean = false, pre: Boolean = false, assets: List<Assets> =
        listOf(asset("app-tempus-release.apk"), asset("app-degoogled-release.apk"), asset("tempus-mapping.txt.gz", base + "tempus-mapping.txt.gz"))) =
        LatestRelease(tagName = tag, draft = draft, prerelease = pre, name = "Piterfy $tag", body = "- **Miksy** - nowe\n- `Ruch`\nzwykła linia", htmlUrl = "https://github.com/x",
            assets = ArrayList(assets))

    @Test fun picksTheApkOfTheInstalledFlavor() {
        assertEquals("app-tempus-release.apk", PiterfyUpdateLogic.pick(release(), "tempus")!!.apkName)
        assertEquals("app-degoogled-release.apk", PiterfyUpdateLogic.pick(release(), "degoogled")!!.apkName)
    }

    @Test fun parsesVersionSizeAndDigest() {
        val u = PiterfyUpdateLogic.pick(release(), "tempus")!!
        assertEquals("4.29.0-piterfy.14", u.version)
        assertEquals(11_000_000L, u.size)
        assertEquals(sha, u.sha256)
        assertEquals("https://github.com/x", u.htmlUrl)
    }

    @Test fun ignoresDraftsPreReleasesAndForeignTags() {
        assertNull(PiterfyUpdateLogic.pick(release(draft = true), "tempus"))
        assertNull(PiterfyUpdateLogic.pick(release(pre = true), "tempus"))
        assertNull(PiterfyUpdateLogic.pick(release(tag = "v4.29.0"), "tempus"))
        assertNull(PiterfyUpdateLogic.pick(release(tag = "nightly"), "tempus"))
        assertNull(PiterfyUpdateLogic.pick(null, "tempus"))
    }

    @Test fun refusesApksFromOtherRepositoriesOrPaths() {
        val evil = release(assets = listOf(asset("app-tempus-release.apk", "https://evil.example/pi0trdotsys/piterfy-android/releases/download/x/app-tempus-release.apk")))
        assertNull(PiterfyUpdateLogic.pick(evil, "tempus"))
        assertFalse(PiterfyUpdateLogic.allowedUrl("https://github.com/pi0trdotsys/piterfy-android/releases/download/../../x.apk"))
        assertFalse(PiterfyUpdateLogic.allowedUrl("https://github.com/pi0trdotsys/piterfy-android/releases/download/v1/file.zip"))
        assertFalse(PiterfyUpdateLogic.allowedUrl(null))
        assertTrue(PiterfyUpdateLogic.allowedUrl(base + "app-tempus-release.apk"))
        assertTrue(PiterfyUpdateLogic.allowedUrl(base.uppercase().replace("HTTPS", "https") + "App.APK"))
    }

    @Test fun missingFlavorApkMeansNoUpdate() {
        assertNull(PiterfyUpdateLogic.pick(release(assets = listOf(asset("app-degoogled-release.apk"))), "tempus"))
    }

    @Test fun normalizesGithubDigests() {
        assertEquals(sha, PiterfyUpdateLogic.normalizeSha("sha256:" + sha.uppercase()))
        assertEquals(sha, PiterfyUpdateLogic.normalizeSha(" $sha "))
        assertEquals("", PiterfyUpdateLogic.normalizeSha("sha1:abc"))
        assertEquals("", PiterfyUpdateLogic.normalizeSha(null))
        assertEquals("", PiterfyUpdateLogic.pick(release(assets = listOf(asset("app-tempus-release.apk", digest = null))), "tempus")!!.sha256)
    }

    @Test fun comparesPiterfyVersionsNumerically() {
        assertTrue(PiterfyUpdateLogic.isNewer("4.29.0-piterfy.9", "4.29.0-piterfy.10"))
        assertTrue(PiterfyUpdateLogic.isNewer("4.29.0-piterfy.14", "4.30.0-piterfy.1"))
        assertFalse(PiterfyUpdateLogic.isNewer("4.29.0-piterfy.14", "4.29.0-piterfy.14"))
        assertFalse(PiterfyUpdateLogic.isNewer("4.29.0-piterfy.14", "4.29.0-piterfy.13"))
        assertFalse(PiterfyUpdateLogic.isNewer("4.29.0-piterfy.14", "v4.29.0"))
    }

    @Test fun releaseNotesKeepOnlyBulletsWithoutMarkdown() {
        assertEquals(listOf("Miksy - nowe", "Ruch"), PiterfyUpdateLogic.notes(release().body))
        assertEquals(3, PiterfyUpdateLogic.notes((1..20).joinToString("\n") { "- punkt $it" }, 3).size)
        assertTrue(PiterfyUpdateLogic.notes(null).isEmpty())
    }

    @Test fun checksAreThrottledAndLaterHidesOnlyThatVersion() {
        val h = 3600_000L
        assertTrue(PiterfyUpdateLogic.checkDue(10 * h, 0))
        assertFalse(PiterfyUpdateLogic.checkDue(10 * h, 9 * h))
        assertTrue(PiterfyUpdateLogic.checkDue(12 * h + 1, 9 * h))
        assertTrue(PiterfyUpdateLogic.checkDue(1 * h, 5 * h))                      // zegar cofnięty: pytamy
        assertFalse(PiterfyUpdateLogic.checkDue(10 * h, 5 * h, PiterfyUpdateLogic.BACKGROUND_EVERY_MS))
        assertTrue(PiterfyUpdateLogic.laterActive(20 * h, 10 * h, "4.29.0-piterfy.15", "4.29.0-piterfy.15"))
        assertFalse(PiterfyUpdateLogic.laterActive(40 * h, 10 * h, "4.29.0-piterfy.15", "4.29.0-piterfy.15"))   // po 24 h wraca
        assertFalse(PiterfyUpdateLogic.laterActive(20 * h, 10 * h, "4.29.0-piterfy.16", "4.29.0-piterfy.15"))   // nowsza wersja nie jest ukryta
        assertNotNull(PiterfyUpdateLogic.pick(release(), "tempus"))
    }
}
