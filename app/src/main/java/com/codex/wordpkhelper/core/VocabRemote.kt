package com.codex.wordpkhelper.core

import android.content.Context
import android.util.Log
import com.codex.wordpkhelper.state.CaptureBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在线词表：下载、校验、安装。
 *
 * 内置词表只有 198 条，初次使用时从网络拉一份完整的初中词表来替换它（同名单词覆盖内置释义）。
 * 下载失败不影响使用 —— 内置词表始终可用，识别逻辑不需要感知这里的状态。
 *
 * 数据来源：KyleBing/english-vocabulary 的「1 初中-乱序.txt」，BSD-3-Clause，3223 条。
 * 主地址走 jsDelivr CDN：实测 raw.githubusercontent.com 在部分网络下会长时间超时，
 * 而 jsDelivr 1.6 秒就能下完，所以它排第一，raw 只做备用。
 */
object VocabRemote {

    private const val TAG = "VocabRemote"

    /** 默认来源（初中词表，3223 条）。 */
    const val DEFAULT_URL =
        "https://cdn.jsdelivr.net/gh/KyleBing/english-vocabulary@master/" +
            "1%20%E5%88%9D%E4%B8%AD-%E4%B9%B1%E5%BA%8F.txt"

    /** 备用地址，按顺序尝试。 */
    val FALLBACK_URLS = listOf(
        DEFAULT_URL,
        "https://raw.githubusercontent.com/KyleBing/english-vocabulary/master/" +
            "1%20%E5%88%9D%E4%B8%AD-%E4%B9%B1%E5%BA%8F.txt",
    )

    const val SOURCE_LABEL = "KyleBing/english-vocabulary · 初中"
    const val SOURCE_LICENSE = "BSD-3-Clause"

    /** 单次下载上限，防止对方返回异常大的内容。 */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** 少于这么多条就认为下载到的不是词表（例如错误页），拒绝安装。 */
    private const val MIN_ENTRIES = 100

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** 释义分隔符：上游用全角分号，内置词表用半角，两种都认。 */
    private val MEANING_SEPARATOR = Regex("[;；]")

    enum class Phase { Idle, Downloading, Succeeded, Failed }

    data class State(
        val phase: Phase = Phase.Idle,
        val downloadedBytes: Int = 0,
        /** -1 表示服务器没给 Content-Length（分块传输）。 */
        val totalBytes: Int = -1,
        val entryCount: Int = 0,
        val message: String = "",
        /** 每次安装/移除后自增，界面用它当 remember 的 key 来刷新词表统计。 */
        val revision: Int = 0,
    ) {
        val progress: Float
            get() = if (totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                -1f
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)

    /** 本进程是否已经尝试过「首次自动下载」，避免每次进前台都重试。 */
    private val firstRunAttempted = AtomicBoolean(false)

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, VocabMatcher.REMOTE_VOCAB_FILE)

    fun isInstalled(context: Context): Boolean = file(context).exists() && file(context).length() > 0

    fun lastModified(context: Context): Long =
        file(context).takeIf { it.exists() }?.lastModified() ?: 0L

    /** 已安装在线词表的条目数；没装过返回 0。 */
    fun installedCount(context: Context): Int {
        val f = file(context)
        if (!f.exists()) return 0
        return try {
            VocabMatcher.countEntries(f.inputStream())
        } catch (ignored: Exception) {
            0
        }
    }

    /**
     * 首次使用时自动下载一次。只在「还没装过」且「本进程没试过」时触发，
     * 失败不重试（改由界面上的按钮手动重试）。
     */
    fun ensureFirstRun(context: Context) {
        if (isInstalled(context)) return
        if (!firstRunAttempted.compareAndSet(false, true)) return
        download(context.applicationContext, null)
    }

    /** 手动更新（设置页/词表页的按钮）。 */
    fun refresh(context: Context, url: String?) {
        download(context.applicationContext, url)
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        _state.value = State(revision = _state.value.revision + 1)
        CaptureBus.publishStatus("已移除在线词表，回到内置 ${VocabMatcher(context).builtinSize} 条")
    }

    private fun download(context: Context, customUrl: String?) {
        if (!inFlight.compareAndSet(false, true)) return
        _state.value = State(phase = Phase.Downloading, message = "正在下载词表…")
        scope.launch {
            val candidates = buildList {
                val trimmed = customUrl?.trim().orEmpty()
                if (trimmed.isNotEmpty()) add(trimmed) else addAll(FALLBACK_URLS)
            }
            var lastError = "下载失败"
            for (url in candidates) {
                when (val result = fetch(context, url)) {
                    is Fetch.Ok -> {
                        _state.value = State(
                            phase = Phase.Succeeded,
                            entryCount = result.entryCount,
                            message = "词表已更新：${result.entryCount} 条",
                            revision = _state.value.revision + 1,
                        )
                        // 识别循环每帧都会 reloadIfChanged()，文件时间戳变了就会自动热加载
                        CaptureBus.publishStatus("词表已更新，共 ${result.entryCount} 条")
                        inFlight.set(false)
                        return@launch
                    }
                    is Fetch.Err -> {
                        Log.w(TAG, "下载失败 $url：${result.message}")
                        lastError = result.message
                    }
                }
            }
            _state.value = State(phase = Phase.Failed, message = lastError)
            CaptureBus.publishStatus("词表下载失败：$lastError")
            inFlight.set(false)
        }
    }

    private sealed interface Fetch {
        data class Ok(val entryCount: Int) : Fetch
        data class Err(val message: String) : Fetch
    }

    private suspend fun fetch(context: Context, url: String): Fetch = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            // 用局部 val 而不是那个可空 var：后者被 finally 捕获，Kotlin 不给智能转换。
            val conn = openFollowingRedirects(url)
            connection = conn
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext Fetch.Err("服务器返回 HTTP $code")
            }
            val total = conn.contentLength
            _state.value = _state.value.copy(totalBytes = total, downloadedBytes = 0)

            val bytes = readWithProgress(conn.inputStream, total)
                ?: return@withContext Fetch.Err("词表超过 ${MAX_BYTES / 1024 / 1024} MB，已中止")

            val text = bytes.toString(Charsets.UTF_8)
            if (text.isBlank() || text.trimStart().startsWith("<")) {
                return@withContext Fetch.Err("下载到的不是词表文件")
            }
            val tsv = normalizeToTsv(text)
            if (tsv.isEmpty()) {
                return@withContext Fetch.Err("词表内容无法解析（有效条目少于 $MIN_ENTRIES 条）")
            }
            val count = VocabMatcher.countEntries(tsv.byteInputStream())
            if (count < MIN_ENTRIES) {
                return@withContext Fetch.Err("词表内容无法解析（只认出 $count 条）")
            }
            install(context, tsv)
            Fetch.Ok(count)
        } catch (error: Exception) {
            Fetch.Err(describe(error))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** jsDelivr 可能返回 3xx，HttpURLConnection 不保证自动跨协议跟随，这里手动跟一次。 */
    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(3) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "WordPkHelper")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (!location.isNullOrBlank()) {
                    current = URL(current, location)
                    return@repeat
                }
            }
            return connection
        }
        throw IllegalStateException("重定向次数过多")
    }

    /** 边读边回报进度；超过上限返回 null。 */
    private fun readWithProgress(stream: InputStream, total: Int): ByteArray? {
        val buffer = ByteArrayOutputStream(if (total > 0) total else 256 * 1024)
        val chunk = ByteArray(64 * 1024)
        stream.use { input ->
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                if (buffer.size() + read > MAX_BYTES) return null
                buffer.write(chunk, 0, read)
                _state.value = _state.value.copy(downloadedBytes = buffer.size())
            }
        }
        return buffer.toByteArray()
    }

    /**
     * 把上游文本规整成本项目的词表格式：`单词<TAB>释义;释义`，小写单词、UTF-8。
     * 顺带完成校验 —— 有效条目少于 [MIN_ENTRIES] 就返回空串。
     */
    private fun normalizeToTsv(raw: String): String {
        val out = StringBuilder(raw.length)
        var kept = 0
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val tab = trimmed.indexOf('\t')
            if (tab <= 0) continue
            val word = trimmed.substring(0, tab).trim().lowercase(Locale.ROOT)
            if (word.isEmpty()) continue
            val meanings = MEANING_SEPARATOR.split(trimmed.substring(tab + 1))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (meanings.isEmpty()) continue
            out.append(word).append('\t').append(meanings.joinToString(";")).append('\n')
            kept++
        }
        return if (kept >= MIN_ENTRIES) out.toString() else ""
    }

    /** 先写临时文件再改名，避免下载中断后留下半截词表被当成完整的加载。 */
    private fun install(context: Context, tsv: String) {
        val target = file(context)
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(tsv.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun describe(error: Exception): String = when (error) {
        is java.net.UnknownHostException -> "无法解析域名，请检查网络"
        is java.net.SocketTimeoutException -> "连接超时，请检查网络"
        is java.net.ConnectException -> "无法连接服务器"
        else -> error.message ?: error.javaClass.simpleName
    }
}
