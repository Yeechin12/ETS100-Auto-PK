package com.codex.wordpkhelper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.codex.wordpkhelper.core.VocabRemote
import com.codex.wordpkhelper.core.VocabStore
import com.codex.wordpkhelper.service.ScreenCaptureService
import com.codex.wordpkhelper.service.WordPkAccessibilityService
import com.codex.wordpkhelper.settings.SettingsStore
import com.codex.wordpkhelper.ui.AppActions
import com.codex.wordpkhelper.ui.WordPkApp

/**
 * 唯一的 Activity。界面全部是 Compose，这里只负责那些必须在 Activity 上注册的能力：
 * 录屏授权、通知权限、文件选择，以及无障碍开关的检查。
 */
class MainActivity : ComponentActivity() {

    /** 导入成功后自增，用来让词表页重新读取词表。 */
    private var vocabRevision by mutableIntStateOf(0)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "未授予通知权限：仍可识别，但通知栏不会显示停止入口",
                    Toast.LENGTH_LONG,
                ).show()
            }
            requestScreenCapture()
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "屏幕捕获未获授权", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, intent)
        }

    private val vocabLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            VocabStore.import(this, uri)
                .onSuccess { count ->
                    vocabRevision++
                    Toast.makeText(this, "已导入 $count 条自定义词条", Toast.LENGTH_LONG).show()
                }
                .onFailure { error ->
                    Toast.makeText(this, "导入失败：${error.message}", Toast.LENGTH_LONG).show()
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        // 首次使用：内置词表只有 198 条，装好第一次打开时自己拉一份完整词表。
        // 失败不阻塞任何功能，界面上会给重试入口。
        if (SettingsStore.current.autoDownloadVocab) {
            VocabRemote.ensureFirstRun(this)
        }
        setContent {
            WordPkApp(
                actions = AppActions(
                    start = ::beginCapture,
                    stop = ::stopCapture,
                    openAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    importVocab = { vocabLauncher.launch(arrayOf("*/*")) },
                    setRunMode = { mode ->
                        // 识别循环每帧都从设置里读运行模式，所以切换立即生效，
                        // 不需要重新申请录屏授权、也不会创建第二个 MediaProjection 会话。
                        SettingsStore.update { it.copy(runMode = mode) }
                    },
                ),
                vocabRevision = vocabRevision,
            )
        }
    }

    private fun beginCapture() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先在无障碍设置中启用「单词 PK 助手」", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        // 已经在识别时重复点「开始识别」不再申请授权：重复授权会带来多余的
        // MediaProjection 会话，而旧会话并不会因此被释放。
        if (ScreenCaptureService.isRunning) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // 没有通知权限时前台服务通知会被静默，用户就看不到状态栏里的“停止识别”入口
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val ours = ComponentName(this, WordPkAccessibilityService::class.java).flattenToString()
        return enabled.lowercase().contains(ours.lowercase())
    }
}
