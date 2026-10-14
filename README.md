# 单词 PK 助手

Android 辅助应用：通过屏幕捕获和 ML Kit OCR 识别 e听说中学单词 PK 题目，并可使用无障碍服务自动点选答案。
界面是 Kotlin + Compose 写的多页应用，玻璃面板用 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
（Maven 坐标 `io.github.kyant0:backdrop`）做真实折射，另有一条液态玻璃悬浮条浮在 PK 页面上方。

词义数据覆盖用户提供的教材 Unit 1–6 词表，并支持导入自定义词表。

## 界面

三个页面，底部玻璃导航切换，背景是一层缓慢漂移的极光渐变 —— 玻璃要好看，
前提是背后有东西可折射。

| 页面 | 内容 |
| --- | --- |
| 主页 | 实时状态与大字号答案、运行模式切换、开始/停止、使用步骤 |
| 词表 | 内置/自定义词条统计、搜索、导入 TSV、词条列表 |
| 设置 | 识别节奏（帧间隔/点击间隔/冷却/位移容差）、悬浮条（开关/不透明度/位置/窗口模糊）、玻璃参数（折射/模糊）、关于 |

## 架构

```
MainActivity (Compose)
  └─ ui/            页面与液态玻璃设计系统
       ui/glass          GlassSurface / GlassButton / GlassToggle / Glyph（手绘玻璃与基础控件）
       ui/glass/liquid   从 AndroidLiquidGlass 移植的 LiquidBottomTabs / LiquidSlider 等
       ui/pages          HomePage / VocabPage / SettingsPage
       ui/overlay        OverlayGlassBar（悬浮条内容）
       ui/theme          配色、字号、圆角、极光背景

service/
  ScreenCaptureService        截屏 + OCR 主循环，所有识别状态只在 capture 线程上读写
  WordPkAccessibilityService  手势点击 + 承载悬浮窗（Compose 跑在无障碍窗口里）
  OverlayHost                 给悬浮窗补上 Compose 需要的三个 ViewTree owner

core/       ScreenLayout（版面推断）/ VocabMatcher（词表匹配）/ VocabStore（导入）
state/      CaptureBus：识别状态单向数据流，服务只写、界面只读
settings/   SettingsStore：可调参数，SharedPreferences 持久化 + StateFlow 暴露
```

### 液态玻璃是怎么接的

Backdrop 的原理是「把背景录制成一层，再在玻璃节点上采样它」。这里的根布局用了**两条**层：

```kotlin
// 图层 A：只有极光背景。页面内的玻璃（液体滑杆、运行模式选择器）折射它 ——
// 它不包含页面内容，所以在页面里采样它不会「采样到自己所在的层」。
val auroraBackdrop = rememberLayerBackdrop { drawContent() }
Box(Modifier.layerBackdrop(auroraBackdrop)) { AuroraBackground() }

// 图层 B：页面内容。
val contentBackdrop = rememberLayerBackdrop { drawContent() }
Box(Modifier.layerBackdrop(contentBackdrop)) { PageContent() }

// 玻璃浮层：上面两层的兄弟节点，折射「极光 + 页面内容」，
// 所以滚动时文字会从玻璃下面穿过。
ProvideGlassBackdrop(rememberCombinedBackdrop(auroraBackdrop, contentBackdrop)) {
    TopBar()
    BottomBar()
}
```

**唯一要守住的约束**：不要让某个 `drawBackdrop` 去采样「包含它自己的那一层」。
那会形成无界的 RenderNode 嵌套，最终在 RenderThread 里栈溢出崩溃
（实测 tombstone：`SIGSEGV / SEGV_ACCERR`，
`Cause: stack pointer is close to top of stack; likely stack overflow`，
backtrace 是 `RenderNode::prepareTreeImpl` 无限递归）。拆成两条层的意义就在这里：
页面内容在图层 B 里，拿到的折射源是图层 A。

页面里的普通玻璃（卡片、按钮）走 `GlassSurface` 的手绘分支
（半透明底 + 上亮下暗的高光描边），只有确实需要折射的控件才显式接收 backdrop，
避免每张卡片都挂一个折射节点。

悬浮条是另一个例外：它在无障碍窗口里，Backdrop 只能采样「同一个窗口里画在自己前面的像素」，
在别人的 App 上面没有内容可采样。所以悬浮条同样走手绘分支，再叠加系统级的跨窗口模糊
（`FLAG_BLUR_BEHIND`，Android 12+，部分 ROM 不支持）来虚化背后的画面。

### 第三方组件

`ui/glass/liquid/` 下的 `LiquidBottomTabs`、`LiquidBottomTab`、`LiquidSlider`、
`DampedDragAnimation`、`InteractiveHighlight`、`LiquidGestures` 移植自
[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache License 2.0）
的 Catalog，各文件头部注明了原始路径与改动。除配色改为本项目的 `GlassColors`、
去掉亮色主题分支、`Clock.System.now()` 换成 Android 单调时钟之外，还修了上游一个同步缺陷：
上游用 `snapshotFlow { selectedTabIndex() }` 监听外部索引，而调用方传入的通常是普通
`Int` 而非快照状态，导致**点按**切换后玻璃指示器不跟随（只有拖动才生效）；
这里改为按值重启的 `LaunchedEffect`，并让点按直接驱动指示器。

`Capsule` / `RoundedRectangle` 等形状来自 `io.github.kyant0:shapes`。它原本只是 backdrop
的 runtime 传递依赖，编译期取不到，所以在 `app/build.gradle` 里显式声明了一次。

## 构建

需要 JDK 17+ 与 Android SDK。**compileSdk 必须是 37**：`backdrop:2.0.1` 的 AAR 元数据里写了
`minCompileSdk=37`。注意新版 SDK 的包名带次版本号，命令行安装时是：

```powershell
sdkmanager "platforms;android-37.0"
.\gradlew.bat assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

### 工具链为什么是这几个版本

| 组件 | 版本 | 为什么不能用别的 |
| --- | --- | --- |
| AGP | 9.3.2 | Compose 1.12.0 与 androidx.core 1.19.0 都要求 AGP ≥ 9.1.0 |
| Gradle | 9.7.1 | 与 AGP 9.3.2 配套 |
| Kotlin | 2.4.10 | `backdrop:2.0.1` 用 Kotlin 2.4.10 编译，低版本编译器读不了它的元数据 |
| Compose | 1.12.0 | `backdrop:2.0.1` 依赖 CMP 1.12.0，在 Android 上即 AndroidX Compose 1.12.0 |
| compileSdk | 37 | backdrop 的 AAR 元数据要求 |

`gradle.properties` 里有两个**必须保留**的开关：

```properties
android.builtInKotlin=false
android.newDsl=false
```

AGP 9 默认使用「内置 Kotlin」，但内置版本固定在 **2.2.10**（AGP 9.0.1 与 9.3.2 都是），
读不了 backdrop 的 Kotlin 2.4.10 元数据；而 `org.jetbrains.kotlin.android` 插件又与
AGP 9 的新 DSL 不兼容。两者只能同时关掉，才能用显式声明的 Kotlin 2.4.10。

这两项在 AGP 10 会被移除。届时若 backdrop 没有放宽 Kotlin 版本要求，就只能等 AGP 的内置
Kotlin 追上来，或改用不依赖 Kotlin 2.4 的玻璃实现。

签名说明：仓库不包含 `debug.keystore`（签名材料不提交，见 `.gitignore`）。根目录没有该文件时
使用 AGP 自动生成的默认调试签名，可直接出包；如果你希望多台机器共用同一个调试签名，
把 `debug.keystore` 放到项目根目录即可自动启用。

API 26+ 用 `res/mipmap-anydpi-v26/ic_launcher.xml` 自适应图标；更低版本的位图是
`tools/make_launcher_icon.py`（Pillow）生成的，改了图标设计就重跑它。

## 使用

1. 在 Android 设置中启用本应用的无障碍服务。
2. 回到应用选择运行模式（自动点击 / 仅显示答案），点「开始识别」。
3. Android 13 及以上会先申请通知权限：拒绝也能识别，但状态栏通知会被静默，也就没有通知栏里的「停止识别」入口。
4. 授权屏幕捕获后切到 e听说中学的单词 PK 页面。

运行期间可直接在应用里切换运行模式 —— 识别循环每帧都从设置里读，立即生效，
不需要重新授权录屏，也不会创建第二个 `MediaProjection` 会话。
结束识别可用应用内的「停止识别」、通知栏的「停止识别」，或系统状态栏的停止共享。

若识别提示「词表未覆盖：…」，说明该题的选项不在词表里，提示里会列出识别到的选项文字，
按下面的格式补进自定义词表即可。

## 识别区域

默认按屏幕高度比例切两条带定位题干（22%–33%）与选项（34%–70%）——这是旧行为，
在常规比例的竖屏手机上工作正常。若按默认区域认不出题面（提示「选项区没有识别到文字」或
「未识别到中英文题干」），应用会用整屏 OCR 探一次版面结构：把行框聚成「行」，找一段字号相近、
行距正常的连续行作为选项区，其上方紧邻的文字作为题干，然后改用定位出来的区域
（该探测帧不答题，之后每 3 秒最多重探一次、最多 8 次）。检测结果与默认比例区域差异小于屏高 6%
时不切换，因此原本正常的机型行为不变。

## 自定义词表

内置词表是 `app/src/main/assets/vocab.tsv`，共 198 条，覆盖 Unit 1–6。运行时可在「词表」页
点「导入自定义词表（TSV）」选择文件；导入的条目会追加到内置词表，同名单词覆盖内置释义，
识别过程中立即生效（无需重启识别）。

格式与内置词表一致，UTF-8、每行一条，单词与释义之间是制表符（Tab），多个释义用半角分号分隔，
`#` 开头的行会被忽略：

```
teenage	十几岁的;青少年的
first-aid kit	急救箱
```

导入文件保存在应用私有目录的 `vocab-extra.tsv`，大小上限 512 KB；解析不出任何合法行时会拒绝导入，
不会破坏已有词表。

## 隐私

截屏只在本机解码并交给 ML Kit 识别，图片不会离开设备；`allowBackup="false"`，
`dataExtractionRules` 也关掉了云端备份与设备间迁移，词表写在应用私有目录。
无障碍权限仅用于代点选项。

关于联网：ML Kit 的遥测组件 `com.google.android.datatransport` 会在清单合并时带进
`INTERNET` 与 `ACCESS_NETWORK_STATE`。应用自己的代码不做任何网络请求，所以清单里用
`tools:node="remove"` 把这两个权限移除了 —— 可以自己核对装出来的包：

```powershell
aapt2 dump badging app-debug.apk | Select-String "uses-permission"
```

这里只移除权限、**不**排除依赖：datatransport 的类仍然存在，只是发不出请求；
用 `exclude` 排掉该依赖会让 ML Kit 初始化时抛 `NoClassDefFoundError`。

需要留意的是：全屏捕获意味着任意界面内容都会被临时解码到内存并交给 OCR 流水线，
叠加悬浮窗与手势能力属于高权限组合。另外自动答题本身可能违反「e听说中学」的用户协议，
用于真实竞赛或考试场景存在合规风险 —— 请自行判断使用场景。
