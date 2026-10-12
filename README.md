# 单词 PK 助手

Android 辅助应用：通过屏幕捕获和 ML Kit OCR 识别 e听说中学单词 PK 题目，并可使用无障碍服务自动点选答案。词义数据覆盖用户提供的教材 Unit 1–6 词表。

## 技术栈

- Java / Android 原生
- Google ML Kit Text Recognition（英文与中文）
- Android MediaProjection 屏幕捕获
- Android Accessibility 自动点击

## 构建

需要 JDK 17 或兼容版本，以及 Android SDK（API 36）。

```powershell
.\gradlew.bat assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

签名说明：仓库不包含 `debug.keystore`（签名材料不提交，见 `.gitignore`）。根目录没有该文件时使用 AGP 自动生成的默认调试签名，可直接出包；如果你希望多台机器共用同一个调试签名，把 `debug.keystore` 放到项目根目录即可自动启用。

## 使用

1. 在 Android 设置中启用本应用的无障碍服务。
2. 回到应用选择运行模式（自动点击 / 仅显示答案），点“开始识别”。
3. Android 13 及以上会先申请通知权限：拒绝也能识别，但状态栏通知会被静默，也就没有通知栏里的“停止识别”入口。
4. 授权屏幕捕获后切到 e听说中学的单词 PK 页面。

运行期间可直接在应用里切换运行模式，会立即同步给正在识别的服务，不需要重新授权录屏。结束识别可用应用内的“停止识别”、通知栏的“停止识别”，或系统状态栏的停止共享。
