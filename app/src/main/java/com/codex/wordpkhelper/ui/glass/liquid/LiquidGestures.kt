/*
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0）的 Catalog 工具类：
 *   app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/DragGestureInspector.kt
 *   app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/Coroutines.kt
 *
 * 改动：package 换成本项目；`expect fun awaitFrame()` 是 KMP 写法，
 * Android 端直接用 Compose 的 `withFrameNanos` 实现，不再需要 expect/actual。
 */
package com.codex.wordpkhelper.ui.glass.liquid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull

/** 等到下一帧。 */
internal suspend fun awaitFrame() {
    withFrameNanos { }
}

/**
 * 与 `detectDragGestures` 的差别：按下就立刻回调 [onDragStart]（不必先移动过阈值），
 * 玻璃面板的「按住即放大」反馈需要这个语义。
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)

        val down = awaitFirstDown(false)
        val dragChange = initialDown

        onDragStart(down)
        onDrag(dragChange, Offset.Zero)
        val upEvent =
            awaitDrag(
                pointerId = dragChange.id,
                onDrag = { onDrag(it, it.positionChange()) }
            )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDrag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) {
            return null
        }
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}
