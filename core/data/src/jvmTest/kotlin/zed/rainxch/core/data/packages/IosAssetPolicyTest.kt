package zed.rainxch.core.data.packages

import zed.rainxch.core.domain.model.account.github.GithubAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosAssetPolicyTest {
    private fun asset(
        name: String,
        size: Long = 1L,
    ) = GithubAsset(
        id = name.hashCode().toLong(),
        name = name,
        contentType = "application/octet-stream",
        size = size,
        downloadUrl = "https://dl/$name",
    )

    @Test
    fun acceptsIpaAndAndroidApkCaseInsensitively() {
        assertTrue(IosAssetPolicy.isDownloadable("App.ipa"))
        assertTrue(IosAssetPolicy.isDownloadable("APP.IPA"))
        assertTrue(IosAssetPolicy.isDownloadable("app-arm64-v8a.apk"))
        assertTrue(IosAssetPolicy.isDownloadable("app-release.apk"))
    }

    @Test
    fun rejectsEverythingElse() {
        listOf("App.dmg", "setup.exe", "app.zip", "notes.txt", "App.ipa.sig", "tool-linux-amd64.apk").forEach {
            assertFalse(IosAssetPolicy.isDownloadable(it), it)
        }
    }

    @Test
    fun prefersIpaOverApkEvenWhenTheApkIsLarger() {
        val chosen = IosAssetPolicy.choosePrimary(listOf(asset("App.apk", size = 100), asset("App.ipa", size = 10)))
        assertEquals("App.ipa", chosen?.name)
    }

    @Test
    fun fallsBackToApkWhenThereIsNoIpa() {
        val chosen = IosAssetPolicy.choosePrimary(listOf(asset("app-x86_64.apk"), asset("app-arm64-v8a.apk")))
        assertEquals("app-arm64-v8a.apk", chosen?.name)
    }

    @Test
    fun returnsNullForAnEmptyList() {
        assertNull(IosAssetPolicy.choosePrimary(emptyList()))
    }
}
