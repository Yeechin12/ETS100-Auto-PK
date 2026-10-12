package com.codex.wordpkhelper.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 运行模式：自动点选答案，或只把答案显示出来。 */
enum class RunMode { Auto, ShowOnly }

/**
 * 可调参数。
 *
 * 原先这些阈值全是 `ScreenCaptureService` 里的常量，改一次要重新编译。
 * 现在集中到一处，由设置页实时写入、识别循环每帧读取。
 */
data class AppSettings(
    val runMode: RunMode = RunMode.Auto,
    /** 两帧之间的最小间隔，直接决定识别频率与耗电。 */
    val frameIntervalMs: Long = 700,
    /** 两次点击之间的最短间隔。 */
    val tapIntervalMs: Long = 1800,
    /** 同一答案同一位置的冷却时间。 */
    val sameAnswerCooldownMs: Long = 6000,
    /** 答案框位移超过这个像素数就视为换了新题。 */
    val moveTolerancePx: Int = 24,
    /** 是否显示液态玻璃悬浮条。 */
    val overlayEnabled: Boolean = true,
    /** 是否在没有可显示内容时也保留悬浮条（便于确认它已就绪）。 */
    val overlayKeepVisible: Boolean = true,
    /** 悬浮条距离屏幕顶部的距离。 */
    val overlayTopDp: Int = 28,
    /** 悬浮条玻璃表面的底色透明度。 */
    val overlayAlpha: Float = 0.55f,
    /** 是否请求系统对悬浮条背后的内容做跨窗口模糊。 */
    val overlayWindowBlur: Boolean = true,
    /** 玻璃折射强度（dp）。 */
    val glassRefractionDp: Float = 18f,
    /** 玻璃模糊半径（dp）。 */
    val glassBlurDp: Float = 6f,
)

/**
 * 设置的唯一入口。用 [StateFlow] 暴露，方便界面直接 `collectAsState`。
 *
 * 需要在任何读写的进程组件里先调用 [init]（重复调用无副作用）。
 */
object SettingsStore {
    private const val FILE = "wordpk-settings"

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    /** 识别循环里高频读取，走这个属性避免每次创建协程。 */
    val current: AppSettings get() = _state.value

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = store
        _state.value = load(store)
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        prefs?.let { save(it, next) }
    }

    private fun load(p: SharedPreferences) = AppSettings(
        runMode = if (p.getBoolean(KEY_AUTO, true)) RunMode.Auto else RunMode.ShowOnly,
        frameIntervalMs = p.getLong(KEY_FRAME_INTERVAL, 700L).coerceIn(200L, 3000L),
        tapIntervalMs = p.getLong(KEY_TAP_INTERVAL, 1800L).coerceIn(500L, 10_000L),
        sameAnswerCooldownMs = p.getLong(KEY_COOLDOWN, 6000L).coerceIn(0L, 60_000L),
        moveTolerancePx = p.getInt(KEY_MOVE_TOLERANCE, 24).coerceIn(0, 400),
        overlayEnabled = p.getBoolean(KEY_OVERLAY, true),
        overlayKeepVisible = p.getBoolean(KEY_OVERLAY_IDLE, true),
        overlayTopDp = p.getInt(KEY_OVERLAY_TOP, 28).coerceIn(0, 400),
        overlayAlpha = p.getFloat(KEY_OVERLAY_ALPHA, 0.55f).coerceIn(0.1f, 1f),
        overlayWindowBlur = p.getBoolean(KEY_OVERLAY_BLUR, true),
        glassRefractionDp = p.getFloat(KEY_GLASS_REFRACTION, 18f).coerceIn(0f, 48f),
        glassBlurDp = p.getFloat(KEY_GLASS_BLUR, 6f).coerceIn(0f, 32f),
    )

    private fun save(p: SharedPreferences, s: AppSettings) {
        p.edit()
            .putBoolean(KEY_AUTO, s.runMode == RunMode.Auto)
            .putLong(KEY_FRAME_INTERVAL, s.frameIntervalMs)
            .putLong(KEY_TAP_INTERVAL, s.tapIntervalMs)
            .putLong(KEY_COOLDOWN, s.sameAnswerCooldownMs)
            .putInt(KEY_MOVE_TOLERANCE, s.moveTolerancePx)
            .putBoolean(KEY_OVERLAY, s.overlayEnabled)
            .putBoolean(KEY_OVERLAY_IDLE, s.overlayKeepVisible)
            .putInt(KEY_OVERLAY_TOP, s.overlayTopDp)
            .putFloat(KEY_OVERLAY_ALPHA, s.overlayAlpha)
            .putBoolean(KEY_OVERLAY_BLUR, s.overlayWindowBlur)
            .putFloat(KEY_GLASS_REFRACTION, s.glassRefractionDp)
            .putFloat(KEY_GLASS_BLUR, s.glassBlurDp)
            .apply()
    }

    private const val KEY_AUTO = "auto"
    private const val KEY_FRAME_INTERVAL = "frameInterval"
    private const val KEY_TAP_INTERVAL = "tapInterval"
    private const val KEY_COOLDOWN = "cooldown"
    private const val KEY_MOVE_TOLERANCE = "moveTolerance"
    private const val KEY_OVERLAY = "overlay"
    private const val KEY_OVERLAY_IDLE = "overlayIdle"
    private const val KEY_OVERLAY_TOP = "overlayTop"
    private const val KEY_OVERLAY_ALPHA = "overlayAlpha"
    private const val KEY_OVERLAY_BLUR = "overlayBlur"
    private const val KEY_GLASS_REFRACTION = "glassRefraction"
    private const val KEY_GLASS_BLUR = "glassBlur"
}
