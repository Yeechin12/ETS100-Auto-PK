package com.codex.wordpkhelper.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 识别流水线对外发布的实时状态。
 *
 * 旧实现里状态是在服务之间用静态方法互推的（`showStatus`），主界面读不到、
 * 悬浮条也只能被动接收。现在统一成单向数据流：服务只写，主界面与悬浮条只读。
 */
object CaptureBus {
    private val _status = MutableStateFlow("")
    /** 一句话状态，悬浮条与主页都显示它。空字符串表示没有内容可显示。 */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    /** 是否已经持有一个可用的录屏会话。 */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _answer = MutableStateFlow("")
    /** 最近一次命中的答案；为空表示还没命中。 */
    val answer: StateFlow<String> = _answer.asStateFlow()

    private val _hitCount = MutableStateFlow(0)
    /** 本次会话命中答案的次数。 */
    val hitCount: StateFlow<Int> = _hitCount.asStateFlow()

    fun publishStatus(text: String) {
        _status.value = text
    }

    fun publishAnswer(text: String) {
        _answer.value = text
        _hitCount.value = _hitCount.value + 1
    }

    fun setRunning(running: Boolean) {
        _running.value = running
        if (!running) _answer.value = ""
    }

    /** 结束一次识别会话时清空统计。 */
    fun resetSession() {
        _status.value = ""
        _answer.value = ""
        _hitCount.value = 0
    }
}
