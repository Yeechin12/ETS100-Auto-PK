package com.codex.wordpkhelper.service

import android.content.Context
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 让 Compose 跑在一个「不属于任何 Activity」的窗口里。
 *
 * Compose 运行时要求 View 树上存在 [LifecycleOwner]、[SavedStateRegistryOwner] 和
 * [ViewModelStoreOwner]；无障碍服务通过 `WindowManager` 直接挂悬浮窗，这三个都没有，
 * 所以这里自己造一份并塞到悬浮窗的 View 上。
 *
 * 用 [LifecycleRegistry] 的公开构造器即可：悬浮窗只在无障碍服务的主线程回调里创建，
 * 满足它「必须在主线程构造」的校验，不必去用仅面向测试的 `createUnsafe`。
 */
internal class OverlayTreeOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /** 挂到窗口之前调用：恢复状态并推到 RESUMED，Compose 才会真正开始组合。 */
    fun attach() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** 从窗口摘下来之后调用。 */
    fun detach() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

/** 造一个已经把三个 owner 挂好的 [ComposeView]。 */
internal fun createOverlayComposeView(context: Context, owner: OverlayTreeOwner): ComposeView =
    ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
    }

/** 悬浮窗里 Compose 内容的默认左边距/右边距（dp）。 */
internal const val OVERLAY_SIDE_MARGIN_DP = 12

/** 悬浮条与状态栏之间的额外间距（dp）。 */
internal const val OVERLAY_BELOW_STATUS_BAR_DP = 10
