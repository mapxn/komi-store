package zed.rainxch.core.data.packages

import zed.rainxch.core.domain.model.account.github.GithubAsset
import zed.rainxch.core.domain.model.repository.DiscoveryPlatform
import zed.rainxch.core.domain.model.system.SystemArchitecture
import zed.rainxch.core.domain.utils.AssetSelector
import zed.rainxch.core.domain.utils.assetPlatformOf

object IosAssetPolicy {
    val extensionPriority: List<String> = listOf(".ipa", ".apk")

    fun isDownloadable(assetName: String): Boolean =
        when (assetPlatformOf(assetName)) {
            DiscoveryPlatform.Ios, DiscoveryPlatform.Android -> true
            else -> false
        }

    fun choosePrimary(assets: List<GithubAsset>): GithubAsset? =
        AssetSelector.choose(
            assets = assets,
            deviceArch = SystemArchitecture.AARCH64,
            extensionPriority = extensionPriority,
        )
}
