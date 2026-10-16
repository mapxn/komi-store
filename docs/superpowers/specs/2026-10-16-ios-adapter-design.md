# iOS 适配设计

分支:`ios-adapter`
日期:2026-10-16

## 1. 目标

为 Komi Store 增加 iOS 目标,复用现有 KMP 与 Compose Multiplatform 代码。iOS 不允许应用安装第三方二进制,因此 iOS 版本把"下载并安装"改为"仅下载并保存",把"已安装应用"改为"已下载应用"。其余功能与现有版本保持一致。

iOS 版同时支持 `.ipa` 与 `.apk` 两种安装包。设备平台使用项目已有的 `DiscoveryPlatform.Ios`:首页、搜索、发现页按 iOS 过滤并以 `.ipa` 为主要下载资源,同时允许下载 `.apk`。应用标识沿用各自平台的原生做法:`.apk` 用 APK 里的安卓包名(与安卓一致),`.ipa` 用 `Info.plist` 里的 Bundle ID。

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
- 数据库迁移:iOS 沿用桌面端(`jvmMain` 的 `initDatabase`)的做法,只注册 `commonMain` 里已有的 `MIGRATION_19_20` 并开启 `fallbackToDestructiveMigration`。iOS 是全新安装,不存在从旧版本升级的数据,所以不需要搬动 `androidMain` 里的 `MIGRATION_1_2` 至 `MIGRATION_17_18`,安卓与桌面代码不动。
- `commonMain` 中少量只能在 JVM 上编译的代码要改成跨平台写法:`System.currentTimeMillis()` 改为项目已在用的 `Clock.System.now().toEpochMilliseconds()`,`java.*` 引用(`File`、`MessageDigest`、`IOException`、`URLEncoder`、`SecureRandom`、`AtomicReference` 等)改为跨平台等价物,`String.format`/`"%.1f".format()` 改为手写格式化。行为不变。
- iOS 工程用 XcodeGen 的 `iosApp/project.yml` 生成 `iosApp.xcodeproj`,两者都提交,这样没有 XcodeGen 也能直接打开工程。

## 4. 平台实现(iosMain)

沿用现有 `expect/actual` 与 `corePlatformModule` 的组织方式。`core/data` 新增 `PlatformModule.ios.kt`,`core/domain` 的 `Platform` 枚举新增 `IOS`,`isMobile` 改为包含 iOS。所有对 `Platform` 的 `when` 分支同步补齐。

| 接口/声明 | iOS 实现要点 |
|---|---|
| `createPlatformHttpClient` | Ktor Darwin 引擎,代理沿用现有 `ProxyConfig`,HTTP 与 SOCKS 通过引擎的 `proxy` 配置。已知限制:Ktor 文档说明 Darwin 引擎的 HTTP 代理不支持 HTTPS 请求,GitHub 接口都是 HTTPS,因此 iOS 上 HTTP 代理实际无效,SOCKS 与系统代理可用 |
| `Downloader` | 用上面的 Ktor 客户端流式下载,okio 写文件,直接保存到 `userDownloadsDir()`(与桌面端一致),先写 `.part` 临时文件再改名,保留进度 Flow、取消、`GithubAssetAuth` 令牌注入 |
| `FileLocationsProvider` | `appDownloadsDir()` 用缓存目录,`userDownloadsDir()` 用 Documents 下的 `Komi Store Downloads` |
| `Installer` | 不安装系统。文件已由下载器保存到 `userDownloadsDir()`,`install()` 直接返回 `COMPLETED`,现有"下载后安装并记录"的流程因此原样复用。`.ipa` 与 `.apk` 视为可下载,`.ipa` 优先;`uninstall()` 删除对应文件与记录 |
| `InstallerStatusProvider`、`PackageMonitor`、`ExternalAppScanner`、`AggressiveOemDetector`、`UpdateScheduleManager`、`PendingInstallNotifier`、`DownloadProgressNotifier` | 无操作实现,`PackageMonitor.canEnumerateInstalledPackages()` 为 false |
| `DigestVerifier` | 基于 okio 的 SHA-256 |
| `InstallerInfoExtractor` | `.apk`:读取 `AndroidManifest.xml`;`.ipa`:读取 `Payload/*.app/Info.plist`(见第 6 节);其他文件返回 null |
| `ApkInspector` | 无操作实现 |
| `BrowserHelper`、`ClipboardHelper`、`ShareManager`、`AppLauncher` | `UIApplication.open`、`UIPasteboard`、`UIActivityViewController`;`AppLauncher` 改为导出/分享文件 |
| `LocalizationManager`、`getSystemLocaleTag`、`getOsVersion`、`getPlatform` | 读取系统 locale 与系统版本 |
| `createDataStore`、`createAnnouncementsDataStore`、`initDatabase` | DataStore 与 Room 使用 Documents 或 Application Support 目录,Room 使用 bundled SQLite 驱动 |
| `core/presentation` 的 `ScrollbarContainer`、`InstalledAppIcon`、`ObserveTimeZoneChanges`、`isPullToRefreshSupported` | iOS 实现:不显示滚动条,支持下拉刷新,监听系统时区通知 |
| `rememberPackageVisibilityRequester`、`rememberSdkInt`、`rememberSystemReducedMotion` | 无操作、null、读取 `UIAccessibility.isReduceMotionEnabled` |
| `restartAppAfterLanguageChange` | iOS 不能自重启,语言切换改为运行时生效或提示手动重启,实施时按现有 desktop 行为对齐 |

KSafe 与 moko-permissions 的 iOS 支持需要在实施计划第一步验证(见第 9 节)。

## 5. 功能开关

沿用现有 `Platform` 与 `isMobile`/`isDesktop` 的做法,新增 `isIos()` 与 `Platform.tracksInstalledApps`,供 presentation 层使用:

- 详情页与已下载页:iOS 上"安装""已安装""安装中""卸载"的文案改为"下载""已下载""保存中""删除"。
- 设置页:iOS 只保留"更新"分组里的"包含预发布版本"和"已跳过的更新"入口;"检查更新开关与间隔"属于后台更新,安装方式、自动更新、电池优化都是安卓专属,在 iOS 上隐藏。
- 导航:iOS 保留底部导航,Apps 入口显示为"已下载"。
- Apps 页内:隐藏"关联已装应用""Obtainium 导入导出""查看 APK 清单"入口。

安卓与桌面行为在开关为默认值时不变。`Platform` 新增 `IOS`,并新增 `tracksInstalledApps`(安卓与 iOS 为 true),原来用 `platform == Platform.ANDROID` 判断"是否记录已安装应用"的地方改用它;Obtainium、App Manager、静默安装等真正只属于安卓的判断保持原样。`isMobile` 包含 iOS,原来用 `isAndroid()` 判断下拉刷新的地方改用 `isMobile()`。`Platform.IOS.toDiscoveryPlatform()` 返回 `DiscoveryPlatform.Ios`。

## 6. "已下载应用"页

复用 Apps 页与 `InstalledAppsRepository`,不拆分、不重写 `AppsViewModel`。通过第 5 节的能力开关调整文案与可见入口。

- 下载完成后写入一条 `InstalledApp` 记录(仓库、版本、资源名、文件路径),`installSource` 使用现有的 `THIS_APP`。
- `packageName` 是数据库主键,沿用各平台原生标识。`.apk`:沿用安卓包名,与安卓一致。安卓通过 `PackageManager` 读取 APK 得到包名,iOS 没有该能力,因此 iOS 的 `InstallerInfoExtractor` 直接解析下载的 APK:按 zip 读取 `AndroidManifest.xml`,解析二进制 XML 得到 `packageName`、`versionName`、`versionCode`;清单里的应用名是资源引用,iOS 不解析 `resources.arsc`,用仓库名代替。`.ipa`:按 zip 读取 `Payload/*.app/Info.plist`,解析二进制或 XML 属性列表,`packageName` 取 `CFBundleIdentifier`,`versionName` 取 `CFBundleShortVersionString`,`versionCode` 取 `CFBundleVersion` 的整数值(无法转为整数时为 0),应用名取 `CFBundleDisplayName`,没有时取 `CFBundleName`,再没有时用仓库名。两种格式的 `signingFingerprint` 都为 null,现有 `checkSigningFingerprint` 在为 null 时按通过处理。
- 其他类型的文件在 iOS 上不提供下载按钮,所以不需要备用标识。
- 同一个包名下的不同架构变体(如 arm64 与 x86)与安卓行为一致,按同一条记录处理。
- 更新时沿用现有的包名一致性校验:新下载文件的包名与已记录的不一致时,与安卓一样提示不匹配。
- 更新检查:沿用"回到应用时检查,30 分钟冷却"和下拉刷新。有新版本时显示角标,"更新"即下载新版本,新版本下载完成后替换记录并删除旧文件。
- 保留:忽略更新、跳过某版本、固定变体、过滤规则、按仓库分组、排序、搜索。
- "打开应用"改为导出/分享该文件。"卸载"改为删除文件并移除记录。
- 记录对应文件被用户在"文件"App 中手动删除时,页面显示"已删除"状态,并允许重新下载,不崩溃。实现方式:已下载页每次加载时检查记录对应的文件(`userDownloadsDir()` 加 `installedAssetName`)是否存在,不存在则该条目标记为已删除,版本行追加"已删除"文字,主按钮由"打开"变为"重新下载"并进入详情页;详情页在文件缺失时按"未下载"处理,显示下载按钮。重新下载完成后记录恢复正常,版本与更新角标逻辑不变。

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
- 第三方库已核对 Maven 元数据,以下库的指定版本都发布了 `iosArm64` 与 `iosSimulatorArm64`:KSafe 2.0.0、Koin 4.1.1、Room 2.8.4、DataStore 1.2.0、sqlite-bundled 2.6.2、Coil 3.3.0、markdown-renderer 0.39.2、highlights 1.0.0、Ktor Darwin 3.4.0、navigation-compose 2.9.2、material3 1.11.0-alpha03、kotlinx-datetime 0.7.1。moko-permissions 在代码中没有被引用,不影响。
- 本机模拟器运行时是 iOS 17.2 和 17.4,而 Xcode 自带 SDK 是 iOS 27.0。若 Xcode 不能在这些运行时上运行用 27.0 SDK 构建的应用,需要下载较新的模拟器运行时(约数 GB),届时先征求用户同意。
- 本机没有 `local.properties`,且 Android SDK 只有 `android-35` 平台,而 `compileSdk` 为 36。安卓回归构建依赖 AGP 自动下载平台 36;若失败,回归改为桌面端编译,并在汇报里明确说明安卓构建未能验证。
- Apps 页代码量大,功能开关需要逐处排查入口,遗漏会造成 iOS 上出现无效按钮。阶段 3 需要逐项点击检查。
- iOS 上解析安装包需要新写三个小型解析器:APK 的二进制 `AndroidManifest.xml`、IPA 里的二进制属性列表与 XML 属性列表,zip 读取使用 Okio 的 `FileSystem.openZip`(其 JVM 公共 API 已确认存在,iOS 目标是否可用在第一次编译时确认,不可用则暂停商量)。用 `aapt2` 生成的最小 APK 和 `plutil` 生成的最小 IPA 做单元测试,覆盖正常文件、损坏文件与不含清单的文件。
- 首页与搜索使用 `DiscoveryPlatform.Ios`,项目里部分后端缓存路径对 `Ios` 返回 null(回退到其他数据源)。阶段 2 要在模拟器确认首页和搜索确实有内容,没有则排查回退路径,仍不行再与用户商量是否默认用"全部"平台。
- 没有 Apple 开发者账号时只能在模拟器验证,真机运行与分发不在本次范围内。
