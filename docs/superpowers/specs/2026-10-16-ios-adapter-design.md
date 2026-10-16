# iOS 适配设计

分支:`ios-adapter`
日期:2026-10-16

## 1. 目标

为 Komi Store 增加 iOS 目标,复用现有 KMP 与 Compose Multiplatform 代码。iOS 不允许应用安装第三方二进制,因此 iOS 版本把"下载并安装"改为"仅下载并保存",把"已安装应用"改为"已下载应用"。其余功能与现有版本保持一致。

技术上遵循原项目约定:`build-logic` 约定插件、`expect/actual`、Koin 平台模块(`corePlatformModule`)、`FileLocationsProvider` 等接口,不引入新的结构。

## 2. 范围

### 2.1 保留(与现有版本一致)

- 首页、搜索、详情、README、开发者主页、Feed、Repo pages
- 收藏、星标、最近浏览、隐藏仓库
- GitHub 登录(web OAuth、设备流、PAT)、按主机的访问令牌、Codeberg/Forgejo/Gitea 源
- 设置:主题、语言、代理、翻译、访问令牌等

### 2.2 改造

| 现状 | iOS |
|---|---|
| 详情页"安装"按钮 | "下载",文件保存到应用 Documents 目录 |
| 下载完成后安装 | 下载完成后提供"导出/分享"(系统分享面板) |
| "已安装应用"页 | "已下载应用"页(见第 6 节) |

### 2.3 不做

- 后台自动更新、更新通知、开机/后台任务
- 扫描手机已装应用、把已装应用关联到仓库
- Obtainium 导入导出、APK 清单查看
- 安装方式选项(Shizuku、Dhizuku、Root)、电池与厂商优化提示
- iosX64(Intel 模拟器)目标

## 3. 构建配置

- 新增分支 `ios-adapter`,所有工作在此分支。
- `build-logic` 新增 `KotlinIosTarget.kt`,与 `KotlinAndroidTarget.kt`、`KotlinJvmTarget.kt` 同级,并在 `KotlinMultiplatform.kt` 的 `configureKotlinMultiplatform()` 中调用。目标为 `iosArm64` 与 `iosSimulatorArm64`。`convention.kmp.library`、`convention.cmp.library`、`convention.cmp.feature` 创建的模块因此自动获得 iOS 目标。
- `RoomConventionPlugin` 为两个 iOS 目标加 KSP 配置;`BuildKonfigConventionPlugin` 补 iOS 配置。具体写法在实施计划阶段读完这两个插件后确定。
- `gradle/libs.versions.toml` 新增 `ktor-client-darwin`(版本跟随现有 `ktor`)。`sqlite-bundled` 已存在,供 Room 在 iOS 使用。依赖只加在各模块的 `iosMain.dependencies`。
- `composeApp` 增加 iOS 目标,输出静态 framework `ComposeApp`。`iosMain` 提供 `MainViewController()`,内部通过 `ComposeUIViewController` 调用现有共享 `App()`。
- 新增 `iosApp/` Xcode 工程,Swift 仅作宿主:承载 ViewController、`Info.plist`、URL scheme `githubstore`、`UIFileSharingEnabled` 与 `LSSupportsOpeningDocumentsInPlace`。
- `core/data` 的 `androidMain` 中 `MIGRATION_1_2` 至 `MIGRATION_17_18` 移到 `commonMain`,供 iOS 复用。迁移逻辑本身不改,安卓与桌面行为不变。

## 4. 平台实现(iosMain)

沿用现有 `expect/actual` 与 `corePlatformModule` 的组织方式。`core/data` 新增 `PlatformModule.ios.kt`,`core/domain` 的 `Platform` 枚举新增 `IOS`,`isMobile` 改为包含 iOS。所有对 `Platform` 的 `when` 分支同步补齐。

| 接口/声明 | iOS 实现要点 |
|---|---|
| `createPlatformHttpClient` | Ktor Darwin 引擎,保持现有代理与超时约定 |
| `Downloader` | 基于 `NSURLSession` 的下载,保留进度 Flow、取消、`GithubAssetAuth` 令牌注入 |
| `FileLocationsProvider` | `appDownloadsDir()` 用缓存目录,`userDownloadsDir()` 用 Documents 下的 `Komi Store Downloads` |
| `Installer` | 不安装。`isSupported` 恒为 false,其余方法为无操作 |
| `InstallerStatusProvider`、`PackageMonitor`、`ExternalAppScanner`、`AggressiveOemDetector`、`UpdateScheduleManager`、`PendingInstallNotifier`、`DownloadProgressNotifier` | 无操作实现,`PackageMonitor.canEnumerateInstalledPackages()` 为 false |
| `DigestVerifier` | 基于 CommonCrypto 的 SHA-256 |
| `InstallerInfoExtractor` | 解析 APK 的 `AndroidManifest.xml` 获取包名与版本(见第 6 节),非 APK 返回 null |
| `ApkInspector` | 无操作实现 |
| `BrowserHelper`、`ClipboardHelper`、`ShareManager`、`AppLauncher` | `UIApplication.open`、`UIPasteboard`、`UIActivityViewController`;`AppLauncher` 改为导出/分享文件 |
| `LocalizationManager`、`getSystemLocaleTag`、`getOsVersion`、`getPlatform` | 读取系统 locale 与系统版本 |
| `createDataStore`、`createAnnouncementsDataStore`、`initDatabase` | DataStore 与 Room 使用 Documents 或 Application Support 目录,Room 使用 bundled SQLite 驱动 |
| `core/presentation` 的 `ScrollbarContainer`、`InstalledAppIcon`、`ObserveTimeZoneChanges`、`isPullToRefreshSupported` | iOS 实现:不显示滚动条,支持下拉刷新,监听系统时区通知 |
| `rememberPackageVisibilityRequester`、`rememberSdkInt`、`rememberSystemReducedMotion` | 无操作、null、读取 `UIAccessibility.isReduceMotionEnabled` |
| `restartAppAfterLanguageChange` | iOS 不能自重启,语言切换改为运行时生效或提示手动重启,实施时按现有 desktop 行为对齐 |

KSafe 与 moko-permissions 的 iOS 支持需要在实施计划第一步验证(见第 9 节)。

## 5. 功能开关

沿用现有 `Platform` 与 `isMobile`/`isDesktop` 的做法,新增能力判断(如 `canInstall`、`supportsBackgroundUpdates`),由 `Platform.IOS` 派生,供 presentation 层使用:

- 详情页:`canInstall = false` 时按钮文案与动作改为下载。
- 设置页:隐藏安装方式、电池与厂商优化等安卓专属条目。
- 导航:iOS 保留底部导航,Apps 入口显示为"已下载"。
- Apps 页内:隐藏"关联已装应用""Obtainium 导入导出""查看 APK 清单"入口。

安卓与桌面行为在开关为默认值时不变。

## 6. "已下载应用"页

复用 Apps 页与 `InstalledAppsRepository`,不拆分、不重写 `AppsViewModel`。通过第 5 节的能力开关调整文案与可见入口。

- 下载完成后写入一条 `InstalledApp` 记录(仓库、版本、资源名、文件路径),`installSource` 使用现有的 `THIS_APP`。
- `packageName` 是数据库主键,沿用安卓包名,与安卓一致。安卓通过 `PackageManager` 读取 APK 得到包名,iOS 没有该能力,因此 iOS 的 `InstallerInfoExtractor` 直接解析下载的 APK:按 zip 读取 `AndroidManifest.xml`,解析二进制 XML 得到 `packageName`、`versionName`、`versionCode`。`ApkPackageInfo.appName` 在清单里是资源引用,iOS 不解析 `resources.arsc`,用仓库名代替。`signingFingerprint` 为 null,现有 `checkSigningFingerprint` 在为 null 时按通过处理。
- 非 APK 文件(`.dmg`、`.exe`、`.deb`、`.zip` 等)没有安卓包名,`InstallerInfoExtractor` 返回 null。此时 iOS 回退到备用标识 `ios:<sourceHost 或 github.com>/<owner>/<repo>`;同一仓库发布多个文件时,复用现有 `AssetVariant`/`AssetOwnership` 的指纹追加后缀以区分。
- 同一个包名下的不同架构变体(如 arm64 与 x86)与安卓行为一致,按同一条记录处理。
- 更新时沿用现有的包名一致性校验:新下载文件的包名与已记录的不一致时,与安卓一样提示不匹配。
- 更新检查:沿用"回到应用时检查,30 分钟冷却"和下拉刷新。有新版本时显示角标,"更新"即下载新版本,新版本下载完成后替换记录并删除旧文件。
- 保留:忽略更新、跳过某版本、固定变体、过滤规则、按仓库分组、排序、搜索。
- "打开应用"改为导出/分享该文件。"卸载"改为删除文件并移除记录。
- 记录对应文件被用户在"文件"App 中手动删除时,页面显示"已删除"状态,并允许重新下载,不崩溃。页面每次显示时检查记录对应的文件是否仍然存在。重新下载完成后恢复为正常的已下载状态,版本与更新角标逻辑不变。

## 7. 下载、保存与分享

- 默认保存到应用 Documents 目录下的 `Komi Store Downloads`,在"文件 App -> 我的 iPhone -> Komi Store"可见。
- 下载完成后,在详情页与已下载页提供"导出/分享"按钮,调用系统分享面板(`UIActivityViewController`),用户可存到 iCloud Drive、AirDrop 等。
- 文件位于应用沙盒内,卸载应用会一并删除。这一点在设置页的缓存说明中注明。
- 下载进度、取消、令牌注入、镜像与代理行为与现有版本一致。无后台下载,应用退到后台后下载是否继续由系统决定,不做额外保证。

## 8. 交付阶段与验证

分三个阶段,每个阶段都能独立验证:

1. **编译**:所有模块通过 `compileKotlinIosSimulatorArm64`,`composeApp` 能链接出 framework。验证:相关 Gradle 任务成功。
2. **可运行**:Xcode 工程在 iOS 模拟器启动,能浏览首页、搜索、查看详情与 README。验证:模拟器运行并截图。
3. **下载与已下载页**:下载保存到 Documents 并在"文件"App 可见,分享面板可用,已下载页的增删与更新角标正常。验证:模拟器操作并截图,必要时补充单元测试。

每个阶段结束时同时回归安卓与桌面构建(`:composeApp:assembleDebug` 及桌面编译),确认没有被破坏。

## 9. 风险与前置验证

- Kotlin 2.3.10 与 Xcode 27.0 的兼容性未验证。实施第一步先跑 `:core:domain:compileKotlinIosSimulatorArm64`,失败则暂停并与用户商量。
- KSafe 2.0.0、moko-permissions 0.20.1、multiplatform-markdown-renderer 0.39.2、Landscapist/coil3、Room 2.8.4 的 iOS 可用性需逐个确认,不可用的以 iosMain 的最小替代实现处理,并在计划中单独列出。
- Apps 页代码量大,功能开关需要逐处排查入口,遗漏会造成 iOS 上出现无效按钮。阶段 3 需要逐项点击检查。
- iOS 上解析 APK 清单需要新写一个小型的 zip 读取与二进制 XML 解析器(优先使用项目已间接依赖的 Okio 读取 zip,实施时确认其是否可用)。用真实 APK 样本做单元测试,覆盖正常文件、损坏文件与非 APK 文件。
- 没有 Apple 开发者账号时只能在模拟器验证,真机运行与分发不在本次范围内。
