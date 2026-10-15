package com.codex.wordpkhelper.core

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 词表规模快照，供词表管理页展示。 */
data class VocabSummary(
    val builtinSize: Int,
    val remoteSize: Int,
    val customSize: Int,
) {
    val totalSize: Int get() = builtinSize + remoteSize + customSize
}

/**
 * 词表的导入与查询入口，把原先写在 `MainActivity` 里的文件读写逻辑收拢到一处。
 *
 * 导入约定（与旧实现一致）：上限 512 KB、UTF-8、每行「单词<TAB>释义」；
 * 先解析校验、解析不出任何合法行就拒绝写入，避免毁掉已有词表。
 */
object VocabStore {
    /** 自定义词表体积上限，防止误选大文件。 */
    const val MAX_VOCAB_BYTES = 512 * 1024

    fun summary(context: Context): VocabSummary {
        val matcher = VocabMatcher(context)
        return VocabSummary(matcher.builtinSize, matcher.remoteSize, matcher.customSize)
    }

    /**
     * 把用户选中的文件导入为自定义词表。
     *
     * @return 成功时返回导入的条目数，失败时返回带可读原因的 [Result.failure]。
     */
    fun import(context: Context, uri: Uri?): Result<Int> {
        if (uri == null) return Result.failure(IOException("没有选择文件"))
        val app = context.applicationContext ?: context
        val target = File(app.filesDir, VocabMatcher.CUSTOM_VOCAB_FILE)
        return try {
            val buffer = ByteArrayOutputStream()
            val input = app.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取所选文件")
            input.use { stream ->
                val chunk = ByteArray(8192)
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    if (buffer.size() + read > MAX_VOCAB_BYTES) throw IOException("文件超过 512 KB")
                    buffer.write(chunk, 0, read)
                }
            }
            val bytes = buffer.toByteArray()
            val count = VocabMatcher.countEntries(ByteArrayInputStream(bytes))
            if (count == 0) throw IOException("没有解析出任何“单词<TAB>释义”行")
            FileOutputStream(target).use { out -> out.write(bytes) }
            Result.success(count)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** 读取内置 + 自定义词条，供词表管理页搜索与展示。 */
    fun entries(context: Context): List<Pair<String, List<String>>> =
        VocabMatcher(context).snapshotEntries()
}
