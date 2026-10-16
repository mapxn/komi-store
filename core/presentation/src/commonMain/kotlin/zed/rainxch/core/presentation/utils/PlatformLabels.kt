package zed.rainxch.core.presentation.utils

import org.jetbrains.compose.resources.StringResource
import zed.rainxch.core.domain.isIos
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.delete_download
import zed.rainxch.githubstore.core.presentation.res.download_latest
import zed.rainxch.githubstore.core.presentation.res.download_version
import zed.rainxch.githubstore.core.presentation.res.downloaded
import zed.rainxch.githubstore.core.presentation.res.export_download
import zed.rainxch.githubstore.core.presentation.res.install_latest
import zed.rainxch.githubstore.core.presentation.res.install_version
import zed.rainxch.githubstore.core.presentation.res.installed
import zed.rainxch.githubstore.core.presentation.res.installing
import zed.rainxch.githubstore.core.presentation.res.open
import zed.rainxch.githubstore.core.presentation.res.open_app
import zed.rainxch.githubstore.core.presentation.res.saving_download
import zed.rainxch.githubstore.core.presentation.res.uninstall

fun installedRes(): StringResource = if (isIos()) Res.string.downloaded else Res.string.installed

fun uninstallRes(): StringResource = if (isIos()) Res.string.delete_download else Res.string.uninstall

fun installingRes(): StringResource = if (isIos()) Res.string.saving_download else Res.string.installing

fun installLatestRes(): StringResource = if (isIos()) Res.string.download_latest else Res.string.install_latest

fun installVersionRes(): StringResource = if (isIos()) Res.string.download_version else Res.string.install_version

fun openRes(): StringResource = if (isIos()) Res.string.export_download else Res.string.open

fun openAppRes(): StringResource = if (isIos()) Res.string.export_download else Res.string.open_app
