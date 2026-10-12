package com.codex.wordpkhelper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import com.codex.wordpkhelper.settings.SettingsStore
import com.codex.wordpkhelper.ui.overlay.OverlayGlassBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 无障碍服务：负责两件事。
 *
 * 1. 用 [dispatchGesture] 代替用户点选答案；
 * 2. 承载液态玻璃悬浮状态条 —— 用 [ComposeView] 把 Compose 内容挂进
 *    `TYPE_ACCESSIBILITY_OVERLAY` 窗口（三个 ViewTree owner 由 [OverlayTreeOwner] 提供）。
 *
 * 与旧实现的差别：`tap` 会通过 [GestureResultCallback] 回报结果，不再「发射后不管」，
 * 手势被系统拦截时调用方能收到 false 并重置冷却。
 */
class WordPkAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WordPkA11y"

        /** 点击手势持续时长，太短部分 ROM 会判定为误触。 */
        private const val TAP_DURATION_MS = 60L

        /** 悬浮条背后内容的跨窗口模糊半径（dp）。 */
        private const val OVERLAY_BLUR_DP = 26

        @Volatile
        private var instance: WordPkAccessibilityService? = null

        /** 无障碍服务当前是否已连接。 */
        val connected: Boolean get() = instance != null

        /**
         * 在屏幕坐标 (x, y) 处点一下。
         *
         * @param onFinished 手势结束时回调，true 表示系统真的执行了这次点击。
         * @return false 表示服务当前不可用、手势根本没有派发出去。
         */
        fun tap(x: Float, y: Float, onFinished: (Boolean) -> Unit = {}): Boolean {
            val service = instance
            if (service == null) {
                onFinished(false)
                return false
            }
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
                .build()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onFinished(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onFinished(false)
                }
            }
            return try {
                service.dispatchGesture(gesture, callback, null)
            } catch (error: Exception) {
                Log.w(TAG, "dispatchGesture 失败", error)
                onFinished(false)
                false
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var treeOwner: OverlayTreeOwner? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SettingsStore.init(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ensureOverlay()
        // 设置里的悬浮条参数可能随时变，跟着更新窗口与内容
        scope.launch {
            SettingsStore.state.collectLatest { applyOverlayLayout() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        treeOwner?.detach()
        treeOwner = null
        super.onDestroy()
    }

    /** 创建悬浮窗。窗口本身常驻，显不显示交给 Compose 内容决定（不显示时高度为 0）。 */
    private fun ensureOverlay() {
        if (overlayView != null) return
        val wm = windowManager ?: return
        val owner = OverlayTreeOwner().also { it.attach() }
        val view = createOverlayComposeView(this, owner).apply {
            setContent { OverlayGlassBar() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        runCatching { wm.addView(view, params) }
            .onFailure { Log.w(TAG, "悬浮窗添加失败", it) }
        treeOwner = owner
        overlayView = view
        applyOverlayLayout()
    }

    /** 把设置里的悬浮条位置/模糊同步到窗口参数上。 */
    private fun applyOverlayLayout() {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val settings = SettingsStore.current
        val density = resources.displayMetrics.density
        params.y = statusBarHeight() +
            ((OVERLAY_BELOW_STATUS_BAR_DP + settings.overlayTopDp) * density).toInt()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (settings.overlayWindowBlur) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = (OVERLAY_BLUR_DP * density).toInt()
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                params.blurBehindRadius = 0
            }
        }
        runCatching { wm.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "悬浮窗更新失败", it) }
    }

    /**
     * 状态栏高度。悬浮窗不受 insets 分发约束，所以直接从窗口度量里取。
     *
     * API 30 起用 [WindowManager.getCurrentWindowMetrics]，这是受支持的做法；
     * 更低版本没有公开 API 能在窗口之外拿到状态栏高度，只能退回读系统资源。
     */
    private fun statusBarHeight(): Int {
        val fallback = (24 * resources.displayMetrics.density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager?.currentWindowMetrics?.windowInsets
            val top = insets
                ?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                ?.top ?: 0
            if (top > 0) return top
        }
        return internalStatusBarHeight() ?: fallback
    }

    /** 仅 API 30 以下的兜底路径；读内部资源属于「尽力而为」，取不到就返回 null。 */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun internalStatusBarHeight(): Int? {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id <= 0) return null
        return runCatching { resources.getDimensionPixelSize(id) }.getOrNull()
    }
}
