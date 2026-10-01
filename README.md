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

首次使用需由用户授予屏幕捕获权限，并在 Android 设置中启用本应用的无障碍服务。
