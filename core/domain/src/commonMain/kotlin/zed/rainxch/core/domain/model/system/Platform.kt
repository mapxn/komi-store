package zed.rainxch.core.domain.model.system

enum class Platform {
    ANDROID,
    WINDOWS,
    MACOS,
    LINUX,
    IOS,
    ;

    val isMobile: Boolean get() = this == ANDROID || this == IOS
    val isDesktop: Boolean get() = !isMobile
    val tracksInstalledApps: Boolean get() = this == ANDROID || this == IOS
    val tracksDownloadedFiles: Boolean get() = this == IOS
}
