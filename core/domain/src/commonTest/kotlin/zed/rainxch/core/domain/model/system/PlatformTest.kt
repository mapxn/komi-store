package zed.rainxch.core.domain.model.system

import zed.rainxch.core.domain.model.repository.DiscoveryPlatform
import zed.rainxch.core.domain.utils.toDiscoveryPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun iosIsMobileAndNotDesktop() {
        assertTrue(Platform.IOS.isMobile)
        assertFalse(Platform.IOS.isDesktop)
    }

    @Test
    fun androidIsMobileAndDesktopsAreNot() {
        assertTrue(Platform.ANDROID.isMobile)
        listOf(Platform.WINDOWS, Platform.MACOS, Platform.LINUX).forEach {
            assertFalse(it.isMobile)
            assertTrue(it.isDesktop)
        }
    }

    @Test
    fun onlyAndroidAndIosTrackInstalledApps() {
        assertTrue(Platform.ANDROID.tracksInstalledApps)
        assertTrue(Platform.IOS.tracksInstalledApps)
        listOf(Platform.WINDOWS, Platform.MACOS, Platform.LINUX).forEach {
            assertFalse(it.tracksInstalledApps)
        }
    }

    @Test
    fun onlyIosTracksDownloadedFiles() {
        assertTrue(Platform.IOS.tracksDownloadedFiles)
        Platform.entries.filter { it != Platform.IOS }.forEach {
            assertFalse(it.tracksDownloadedFiles)
        }
    }

    @Test
    fun iosMapsToIosDiscoveryPlatform() {
        assertEquals(DiscoveryPlatform.Ios, Platform.IOS.toDiscoveryPlatform())
    }
}
