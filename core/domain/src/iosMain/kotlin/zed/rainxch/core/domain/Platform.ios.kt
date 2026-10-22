package zed.rainxch.core.domain

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages
import platform.UIKit.UIDevice
import zed.rainxch.core.domain.model.system.Platform

actual fun getPlatform(): Platform = Platform.IOS

actual fun getOsVersion(): String = "iOS ${UIDevice.currentDevice.systemVersion}"

actual fun getSystemLocaleTag(): String = (NSLocale.preferredLanguages.firstOrNull() as? String)?.takeIf { it.isNotBlank() } ?: "und"
