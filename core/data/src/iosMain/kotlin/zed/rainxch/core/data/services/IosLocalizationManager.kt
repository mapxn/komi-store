package zed.rainxch.core.data.services

import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

class IosLocalizationManager : LocalizationManager {
    private var activeTag: String? = null

    override fun getCurrentLanguageCode(): String = activeTag ?: systemTag()

    override fun getPrimaryLanguageCode(): String = getCurrentLanguageCode().substringBefore('-')

    override fun setActiveLanguageTag(tag: String?) {
        activeTag = tag?.trim()?.takeIf { it.isNotEmpty() }
        val defaults = NSUserDefaults.standardUserDefaults
        val selected = activeTag
        if (selected != null) {
            defaults.setObject(listOf(selected), forKey = "AppleLanguages")
        } else {
            defaults.removeObjectForKey("AppleLanguages")
        }
    }

    private fun systemTag(): String = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"
}
