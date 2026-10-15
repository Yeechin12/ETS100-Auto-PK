package com.codex.wordpkhelper.core

import android.content.Context
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

/** 命中词表的题干单词，以及它在题干区里的行框。 */
class PromptMatch(val word: String, val box: Rect)

/**
 * 匹配出的答案。
 *
 * [word] 是命中词表的英文单词；[meaning] 是悬浮条上要显示的答案文本
 * （中文题干时是英文单词本身，英文题干时是命中的中文释义）；[box] 是整屏坐标下该选项的位置。
 */
class Choice(val word: String, val meaning: String, val box: Rect)

/**
 * 词表匹配：把 OCR 出来的题干/选项文字对照词表找出答案。
 *
 * 词表 = 内置 assets/vocab.tsv + 私有目录下的 vocab-extra.tsv，同名单词后者覆盖前者。
 * 打分规则沿用旧实现：完全包含 > 命中占整行比例 > LCS 重叠率，前两名分差过小则弃答。
 */
class VocabMatcher(context: Context) {

    companion object {
        /** 自定义词表文件名，放在应用私有目录下；格式与内置词表相同，可追加或覆盖同名单词。 */
        const val CUSTOM_VOCAB_FILE = "vocab-extra.tsv"

        /** 在线下载下来的词表文件名，与用户手动导入的分开存放，便于单独更新/清除。 */
        const val REMOTE_VOCAB_FILE = "vocab-remote.tsv"

        private const val BUILTIN_VOCAB_ASSET = "vocab.tsv"

        /** 分差小于这个值就认为两个选项一样可信，不猜。 */
        private const val AMBIGUOUS_SCORE_GAP = 10

        private val NON_CHINESE = Regex("[^\\u3400-\\u9FFF]")
        private val NON_LETTER = Regex("[^a-z]+")
        private val WHITESPACE = Regex("\\s+")

        /**
         * 释义分隔符。内置词表用手角分号，而在线词表（KyleBing/english-vocabulary）
         * 用的是全角分号，所以两种都得认 —— 否则整行释义会变成一个长串，
         * 丢掉「按义项精确命中」那一档得分。
         */
        private val MEANING_SEPARATOR = Regex("[;；]")

        /** 解析词表内容，返回可用条目数；0 表示格式不对。 */
        fun countEntries(input: InputStream): Int {
            val parsed = LinkedHashMap<String, List<String>>()
            return try {
                readEntries(input, parsed)
                parsed.size
            } catch (ignored: Exception) {
                0
            }
        }

        private fun readEntries(stream: InputStream, target: MutableMap<String, List<String>>) {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val row = reader.readLine() ?: break
                    if (row.startsWith("#")) continue
                    val parts = row.split("\t", limit = 2)
                    if (parts.size != 2) continue
                    val word = parts[0].trim().lowercase(Locale.ROOT)
                    val meanings = parts[1].trim()
                    if (word.isEmpty() || meanings.isEmpty()) continue
                    target[word] = MEANING_SEPARATOR.split(meanings)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                }
            }
        }
    }

    private val context: Context = context.applicationContext ?: context
    private val customFile = File(this.context.filesDir, CUSTOM_VOCAB_FILE)
    private val remoteFile = File(this.context.filesDir, REMOTE_VOCAB_FILE)

    private var builtinEntries: Map<String, List<String>> = emptyMap()
    private var remoteEntries: Map<String, List<String>> = emptyMap()
    private var entries: Map<String, List<String>> = emptyMap()

    /** 词表 key 预先归一化后的结果，避免每帧对每个选项重复跑正则。 */
    private var normalizedWords: Map<String, String> = emptyMap()

    private var loadedStamp = Long.MIN_VALUE

    init {
        reload()
    }

    /** 内置 + 在线 + 自定义词条总数。 */
    val size: Int get() = entries.size

    /** 内置词条数。 */
    val builtinSize: Int get() = builtinEntries.size

    /** 在线下载的词条数（0 表示还没下载过）。 */
    val remoteSize: Int get() = remoteEntries.size

    /** 自定义词条数（0 表示还没导入过）。 */
    val customSize: Int
        get() {
            if (!customFile.exists()) return 0
            val parsed = LinkedHashMap<String, List<String>>()
            return try {
                FileInputStream(customFile).use { readEntries(it, parsed) }
                parsed.size
            } catch (ignored: Exception) {
                0
            }
        }

    /** 自定义词表文件，导入时写这里。 */
    fun customFile(): File = customFile

    /** 内置 + 自定义词条的只读快照，按 word 排序，供词表管理页展示与搜索。 */
    fun snapshotEntries(): List<Pair<String, List<String>>> =
        entries.entries.sortedBy { it.key }.map { it.key to it.value }

    /** 词表文件被替换过就重新加载（导入后无需重启识别）。 */
    fun reloadIfChanged(): Boolean {
        if (stamp() == loadedStamp) return false
        reload()
        return true
    }

    /**
     * 词表文件被替换过就重新加载（导入或在线更新后无需重启识别）。
     * 在线词表与自定义词表任一变化都要算进去。
     */
    private fun stamp(): Long = fileStamp(customFile) * 31L + fileStamp(remoteFile)

    private fun fileStamp(file: File): Long =
        if (!file.exists()) 0L else file.lastModified() * 31L + file.length()

    /**
     * 词表优先级：内置 → 在线 → 自定义。
     * 后加载的覆盖同名条目，所以用户手动导入的词条优先级最高，
     * 在线词表又能盖掉内置那 198 条里不够准确的释义。
     */
    private fun reload() {
        val builtin = LinkedHashMap<String, List<String>>()
        try {
            context.assets.open(BUILTIN_VOCAB_ASSET).use { readEntries(it, builtin) }
        } catch (ignored: Exception) {
        }
        builtinEntries = builtin

        val remote = LinkedHashMap<String, List<String>>()
        if (remoteFile.exists()) {
            try {
                FileInputStream(remoteFile).use { readEntries(it, remote) }
            } catch (ignored: Exception) {
            }
        }
        remoteEntries = remote

        val merged = LinkedHashMap(builtin)
        merged.putAll(remote)
        if (customFile.exists()) {
            try {
                FileInputStream(customFile).use { readEntries(it, merged) }
            } catch (ignored: Exception) {
            }
        }
        entries = merged
        normalizedWords = merged.keys.associateWith { normalizeEnglish(it) }
        loadedStamp = stamp()
    }

    /**
     * 找英文题干。
     *
     * 不用行框面积（长行容易胜出），改为：命中词占整行的比例（题干通常是单独一行的
     * 单词/短语，而不是含该词的长句）+ 字号 + 是否水平居中。
     */
    fun findEnglishPromptWord(lines: List<Text.Line>, screenWidth: Int): PromptMatch? {
        var best: PromptMatch? = null
        var bestScore = 0.0
        var tied = false
        for (line in lines) {
            val bounds = line.boundingBox ?: continue
            val text = normalizeEnglish(line.text)
            if (text.isEmpty()) continue
            // 词表涨到三千条之后，这里不能再在循环里拼字符串：整行的「已加空格」形式
            // 只算一次，循环内只做 contains。
            val padded = padEnglish(text)
            for ((word, normalized) in normalizedWords) {
                if (!englishPhraseIn(padded, normalized)) continue
                if (normalized.isEmpty()) continue
                val coverage = normalized.length / text.length.toDouble()
                val score = coverage * 1000.0 + bounds.height() + centeredBonus(bounds, screenWidth)
                if (score > bestScore + 1e-6) {
                    bestScore = score
                    best = PromptMatch(word, bounds)
                    tied = false
                } else if (Math.abs(score - bestScore) <= 1e-6 && best != null && best.word != word) {
                    tied = true
                }
            }
        }
        return if (tied) null else best
    }

    /** 越靠近屏幕水平中心得分越高：题干一般在屏幕正中。 */
    private fun centeredBonus(bounds: Rect, screenWidth: Int): Double {
        if (screenWidth <= 0) return 0.0
        val offset = Math.abs(bounds.centerX() - screenWidth / 2.0) / (screenWidth / 2.0)
        return maxOf(0.0, 1 - offset * 2) * 200.0
    }

    /** 中文题干 + 英文选项：题干是中文释义，选项是英文单词。 */
    fun findBestEnglishOption(
        questionLines: List<Text.Line>,
        optionLines: List<Text.Line>,
        optionsTop: Int,
        seenOptions: MutableList<String>?
    ): Choice? {
        val prompt = questionLines
            .sortedBy { it.boundingBox?.top ?: 0 }
            .joinToString("") { normalizeChinese(it.text) }
        if (prompt.length < 3) return null

        val detectedOptions = LinkedHashMap<String, Rect>()
        val seen = HashSet<String>()
        for (line in optionLines) {
            val bounds = line.boundingBox ?: continue
            val raw = line.text.trim()
            val option = normalizeEnglish(raw)
            if (option.isEmpty()) continue
            seenOptions?.let { if (raw.isNotEmpty() && !it.contains(raw)) it.add(raw) }
            val key = option + "@" + (bounds.centerY() / maxOf(1, bounds.height() * 2))
            if (!seen.add(key)) continue

            // 一行 OCR 文本可能既包含完整短语、也包含更短的词表条目，
            // 同一个显示选项只保留最长的那个匹配。
            // 用显式循环而不是 filterValues：后者每个选项都要新建一个 Map。
            val padded = padEnglish(option)
            val matches = ArrayList<String>(4)
            var longest = 0
            for ((word, normalized) in normalizedWords) {
                if (normalized.isEmpty() || !englishPhraseIn(padded, normalized)) continue
                matches.add(word)
                if (word.length > longest) longest = word.length
            }
            if (longest == 0) continue
            for (word in matches) {
                if (word.length == longest) detectedOptions[word] = bounds
            }
        }

        val scored = ArrayList<ScoredOption>(detectedOptions.size)
        for ((word, box) in detectedOptions) {
            val meanings = entries[word].orEmpty()
            var score = 0
            val combined = StringBuilder()
            for (rawMeaning in meanings) {
                val meaning = normalizeChinese(rawMeaning)
                combined.append(meaning)
                if (meaning.length < 2) continue
                score = maxOf(
                    score,
                    when {
                        prompt.contains(meaning) -> 1000 + meaning.length * 10
                        meaning.contains(prompt) && prompt.length >= 4 -> 800 + prompt.length * 10
                        meaning.length >= 4 -> {
                            val overlap = longestCommonSubsequence(prompt, meaning)
                            if (overlap / meaning.length.toDouble() >= 0.75) 500 + overlap * 10 else 0
                        }
                        else -> 0
                    }
                )
            }
            // PK 界面会把多个近义释义串在一起显示，末尾用省略号截断
            //（例如“考虑；深思；想法；看法；...”），所以用可见前缀对比完整释义。
            if (prompt.length >= 3 && combined.contains(prompt)) {
                score = maxOf(score, 900 + prompt.length * 10)
            }
            if (score > 0) scored.add(ScoredOption(word, box, score))
        }

        scored.sortByDescending { it.score }
        val top = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        if (second != null && top.score - second.score < AMBIGUOUS_SCORE_GAP) return null
        return Choice(top.word, top.word, Rect(top.box).apply { offset(0, optionsTop) })
    }

    /** 英文题干 + 中文选项：题干是英文单词，选项是中文释义。 */
    fun findBestChineseOption(
        word: String,
        optionLines: List<Text.Line>,
        optionsTop: Int,
        seenOptions: MutableList<String>?
    ): Choice? {
        val meanings = entries[word.lowercase(Locale.ROOT)] ?: return null
        val scored = ArrayList<ScoredOption>(optionLines.size)
        val seen = HashSet<String>()
        for (line in optionLines) {
            val bounds = line.boundingBox ?: continue
            val option = normalizeChinese(line.text)
            if (option.isEmpty()) continue
            val raw = line.text.trim()
            seenOptions?.let { if (raw.isNotEmpty() && !it.contains(raw)) it.add(raw) }
            val key = option + "@" + (bounds.centerY() / maxOf(1, bounds.height() * 2))
            if (!seen.add(key)) continue

            var score = 0
            var selectedMeaning: String? = null
            for (rawMeaning in meanings) {
                val meaning = normalizeChinese(rawMeaning)
                if (meaning.isEmpty()) continue
                val current = when {
                    option.contains(meaning) -> 1000 + meaning.length * 10
                    meaning.contains(option) && option.length >= 2 -> 800 + option.length * 10
                    meaning.length >= 4 -> {
                        val overlap = longestCommonSubsequence(option, meaning)
                        if (overlap / meaning.length.toDouble() >= 0.75) 500 + overlap * 10 else 0
                    }
                    else -> 0
                }
                if (current > score) {
                    score = current
                    selectedMeaning = rawMeaning
                }
            }
            if (score > 0) scored.add(ScoredOption(selectedMeaning ?: word, bounds, score))
        }

        scored.sortByDescending { it.score }
        val top = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        if (second != null && top.score - second.score < AMBIGUOUS_SCORE_GAP) return null
        return Choice(word, top.word, Rect(top.box).apply { offset(0, optionsTop) })
    }

    private class ScoredOption(val word: String, val box: Rect, val score: Int)

    private fun longestCommonSubsequence(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    /** 只保留 CJK 字符：中英混排的释义与选项都先归一化再比较。 */
    private fun normalizeChinese(value: String?): String =
        if (value.isNullOrEmpty()) "" else NON_CHINESE.replace(value, "")

    /** 转小写、非字母字符折叠成单个空格，便于做整词匹配。 */
    private fun normalizeEnglish(value: String?): String =
        if (value.isNullOrEmpty()) "" else WHITESPACE
            .replace(NON_LETTER.replace(value.lowercase(Locale.ROOT), " "), " ")
            .trim()

    /** 整词匹配用的定界形式；一行只算一次，循环里复用。 */
    private fun padEnglish(normalizedText: String): String = " $normalizedText "

    /** 整词匹配：用空格定界，避免 art 命中 start。[paddedText] 来自 [padEnglish]。 */
    private fun englishPhraseIn(paddedText: String, normalizedPhrase: String): Boolean {
        if (normalizedPhrase.isEmpty()) return false
        return paddedText.contains(" $normalizedPhrase ")
    }
}
