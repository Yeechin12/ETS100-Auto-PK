package com.codex.wordpkhelper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.codex.wordpkhelper.MainActivity
import com.codex.wordpkhelper.core.Choice
import com.codex.wordpkhelper.core.ScreenLayout
import com.codex.wordpkhelper.core.ScreenRegions
import com.codex.wordpkhelper.core.VocabMatcher
import com.codex.wordpkhelper.settings.RunMode
import com.codex.wordpkhelper.settings.SettingsStore
import com.codex.wordpkhelper.state.CaptureBus
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 截屏 + OCR 主循环。
 *
 * 线程模型（旧实现的主要缺陷来源，这里做了收敛）：
 * - `ImageReader` 回调、所有 ML Kit 监听器、以及所有识别状态的读写，**全部**跑在
 *   `captureHandler` 这一个线程上。做法是给 `addOnCompleteListener` 传
 *   [captureExecutor]（把 `Handler.post` 包成 `Executor`），而不是用默认的主线程执行器。
 *   这样 `probeInFlight` / `overrideFailures` 这类字段天然没有竞态。
 * - 每帧用一个 [Frame] 承载三张位图，释放是幂等的，任何异常路径都保证 `ocrBusy` 被放掉，
 *   不会出现旧代码里「识别静默卡死、必须杀进程」的情况。
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.codex.wordpkhelper.action.STOP"

        private const val TAG = "WordPkCapture"
        private const val CHANNEL = "wordpk_capture"
        private const val NOTIFICATION_ID = 19

        // 默认识别区域：按屏幕高度比例切两条带（旧行为）。长屏、平板、横屏这类比例明显不同的
        // 设备上会错位，所以它只是默认值；默认区域认不出题面时改用版面检测出来的区域。
        private const val LEGACY_QUESTION_TOP = 0.22f
        private const val LEGACY_QUESTION_BOTTOM = 0.33f
        private const val LEGACY_OPTIONS_TOP = 0.34f
        private const val LEGACY_OPTIONS_BOTTOM = 0.70f

        /** 检测出的区域要和默认区域差得够多才启用，保证原本工作正常的机型行为不变。 */
        private const val LAYOUT_ADAPT_MIN_SHIFT = 0.06f

        /** 版面探测的最小间隔、次数上限；自适应区域连续失败的次数上限。 */
        private const val LAYOUT_PROBE_COOLDOWN_MS = 3000L
        private const val MAX_LAYOUT_PROBES = 8
        private const val MAX_OVERRIDE_FAILURES = 3

        @Volatile
        private var instance: ScreenCaptureService? = null

        /** 服务当前是否已经持有一个可用的录屏会话。 */
        val isRunning: Boolean get() = instance?.projection != null
    }

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    /** 把 Handler 包成 Executor，交给 ML Kit 的 Task 当回调执行器。 */
    private val captureExecutor = Executor { command -> captureHandler.post(command) }

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var latinRecognizer: TextRecognizer? = null
    private var chineseRecognizer: TextRecognizer? = null
    private var recognizersReady: Task<Void>? = null
    private var matcher: VocabMatcher? = null

    private val ocrBusy = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var lastTapAt = 0L
    private var lastTappedSignature = ""
    private var lastTappedX = -1
    private var lastTappedY = -1

    /** 版面探测得出的区域；只被 capture 线程写，读也在 capture 线程。 */
    private var regionsOverride: ScreenRegions? = null
    private var probeRequested = false
    private var probeInFlight = false
    private var overrideFailures = 0
    private var layoutProbes = 0
    private var lastProbeAt = 0L
    private var layoutScreenKey = -1L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户在系统状态栏停止共享屏幕时由系统在主线程回调
            captureHandler.post {
                releaseSession()
                stopSelf()
            }
        }
    }

    /**
     * 一帧的三张位图。
     *
     * [release] 幂等：旧实现里某条异常路径漏掉释放就会让 `ocrBusy` 永远为 true，
     * 之后每一帧都被丢弃、识别彻底静默。这里用 CAS 保证只释放一次、也只放一次锁。
     */
    private inner class Frame(val bitmap: Bitmap) {
        var questionCrop: Bitmap? = null
        var optionsCrop: Bitmap? = null

        private val handedOff = AtomicBoolean(false)
        private val released = AtomicBoolean(false)

        /** 交给选项识别阶段，由那个阶段负责释放。 */
        fun handOff() {
            handedOff.set(true)
        }

        fun isHandedOff(): Boolean = handedOff.get()

        fun release() {
            if (!released.compareAndSet(false, true)) return
            optionsCrop?.let { if (!it.isRecycled) it.recycle() }
            questionCrop?.let { if (!it.isRecycled) it.recycle() }
            if (!bitmap.isRecycled) bitmap.recycle()
            ocrBusy.set(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SettingsStore.init(this)
        createNotificationChannel()
        startForegroundCompat()
        captureThread = HandlerThread("wordpk-capture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
        latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        chineseRecognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        warmUpRecognizers()
        matcher = VocabMatcher(this)
    }

    /**
     * API 34 起媒体投影型前台服务必须显式声明类型（清单里已声明 mediaProjection），
     * 否则部分 ROM 会抛 MissingForegroundServiceTypeException。
     */
    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 预热两个识别器，避免第一帧 OCR 要等模型加载。 */
    private fun warmUpRecognizers() {
        val sample = Bitmap.createBitmap(192, 96, Bitmap.Config.ARGB_8888)
        sample.eraseColor(Color.WHITE)
        val input = InputImage.fromBitmap(sample, 0)
        val chinese = chineseRecognizer!!.process(input)
        val latin = latinRecognizer!!.process(input)
        recognizersReady = Tasks.whenAll(chinese, latin).also { task ->
            task.addOnCompleteListener(captureExecutor) { sample.recycle() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ACTION_STOP == intent.action) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultData = intentParcelable(intent, EXTRA_RESULT_DATA)
        if (resultData == null) {
            // 没有新的录屏授权：会话还在就什么都不用做（运行模式每帧从设置里读），否则收工
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) {
            // 已经有一个会话在跑：丢掉这次重复的授权，不要在同一个服务里同时存在两个
            // MediaProjection 或 VirtualDisplay（旧的那个从此没人释放）。
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val session = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("getMediaProjection 返回了 null")
            projection = session
            session.registerCallback(projectionCallback, captureHandler)
            CaptureBus.publishStatus("正在准备识别")
            recognizersReady?.addOnCompleteListener(captureExecutor) {
                // 只有仍是同一个会话时才建立虚拟显示
                if (projection === session && virtualDisplay == null) {
                    startCapture(session)
                    CaptureBus.publishStatus("正在识别，切到 PK 页面即可")
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "无法开始屏幕识别", error)
            CaptureBus.publishStatus("无法开始屏幕识别")
            captureHandler.post { releaseSession() }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun intentParcelable(intent: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Intent::class.java)
        else intent.getParcelableExtra(key)

    private fun startCapture(session: MediaProjection) {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        if (width <= 0 || height <= 0) {
            CaptureBus.publishStatus("无法获取屏幕尺寸")
            releaseSession()
            stopSelf()
            return
        }

        val reader = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        } catch (error: Exception) {
            Log.w(TAG, "ImageReader 创建失败", error)
            CaptureBus.publishStatus("无法创建屏幕缓冲区")
            releaseSession()
            stopSelf()
            return
        }
        imageReader = reader
        reader.setOnImageAvailableListener(
            object : ImageReader.OnImageAvailableListener {
                override fun onImageAvailable(source: ImageReader) {
                    var image: Image? = null
                    try {
                        image = source.acquireLatestImage() ?: return
                        val now = System.currentTimeMillis()
                        if (now - lastFrameAt < SettingsStore.current.frameIntervalMs) return
                        if (ocrBusy.get()) return
                        lastFrameAt = now
                        val bitmap = imageToBitmap(image) ?: return
                        if (ocrBusy.compareAndSet(false, true)) {
                            val frame = Frame(bitmap)
                            try {
                                analyze(frame, height)
                            } catch (error: Exception) {
                                Log.w(TAG, "analyze 失败", error)
                                frame.release()
                                CaptureBus.publishStatus("识别失败，请重试")
                            }
                        } else {
                            bitmap.recycle()
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "处理帧失败", error)
                    } finally {
                        image?.close()
                    }
                }
            },
            captureHandler
        )

        virtualDisplay = try {
            session.createVirtualDisplay(
                "wordpk-screen", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, captureHandler
            )
        } catch (error: Exception) {
            Log.w(TAG, "createVirtualDisplay 失败", error)
            null
        }
        if (virtualDisplay == null) {
            CaptureBus.publishStatus("无法开始屏幕镜像")
            releaseSession()
            stopSelf()
            return
        }
        CaptureBus.setRunning(true)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val width = image.width
        val height = image.height
        val rowPadding = rowStride - pixelStride * width
        return try {
            val padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            if (cropped !== padded) padded.recycle()
            cropped
        } catch (error: Exception) {
            Log.w(TAG, "位图转换失败", error)
            null
        }
    }

    private fun analyze(frame: Frame, screenHeight: Int) {
        val screenWidth = frame.bitmap.width
        val screenKey = (screenWidth.toLong() shl 32) or (screenHeight.toLong() and 0xFFFFFFFFL)
        if (screenKey != layoutScreenKey) {
            // 分辨率或方向变了：之前探测出来的区域不再可信
            layoutScreenKey = screenKey
            regionsOverride = null
            overrideFailures = 0
            layoutProbes = 0
            probeRequested = false
        }
        matcher?.let { m ->
            if (m.reloadIfChanged()) {
                CaptureBus.publishStatus("词表已更新，共 ${m.size} 条")
            }
        }
        if (probeRequested && !probeInFlight && layoutProbes < MAX_LAYOUT_PROBES &&
            System.currentTimeMillis() - lastProbeAt >= LAYOUT_PROBE_COOLDOWN_MS
        ) {
            probeRequested = false
            probeLayout(frame, screenWidth, screenHeight)
            return
        }

        val override = regionsOverride
        val questionRegion: Rect
        val optionsRegion: Rect
        if (override != null) {
            questionRegion = override.question
            optionsRegion = override.options
        } else {
            questionRegion = Rect(
                0, Math.round(screenHeight * LEGACY_QUESTION_TOP), screenWidth,
                Math.round(screenHeight * LEGACY_QUESTION_BOTTOM)
            )
            optionsRegion = Rect(
                0, Math.round(screenHeight * LEGACY_OPTIONS_TOP), screenWidth,
                Math.round(screenHeight * LEGACY_OPTIONS_BOTTOM)
            )
        }
        analyzeRegions(frame, questionRegion, optionsRegion, screenWidth, override != null)
    }

    /**
     * 用整屏 OCR 探一次版面结构，成功后后续帧改用检测出来的区域。探测这一帧不答题
     * （多花的开销只有这一帧），结构不明显时继续沿用默认比例区域。
     */
    private fun probeLayout(frame: Frame, screenWidth: Int, screenHeight: Int) {
        probeInFlight = true
        lastProbeAt = System.currentTimeMillis()
        layoutProbes++
        val input = InputImage.fromBitmap(frame.bitmap, 0)
        val chineseTask = chineseRecognizer!!.process(input)
        val latinTask = latinRecognizer!!.process(input)
        Tasks.whenAll(chineseTask, latinTask).addOnCompleteListener(captureExecutor) { task ->
            val model = if (task.isSuccessful) {
                val lines = ArrayList<Text.Line>()
                lines.addAll(flatten(chineseTask.result))
                lines.addAll(flatten(latinTask.result))
                ScreenLayout.detect(lines, screenWidth, screenHeight)
            } else {
                null
            }
            val adapt = model != null && differsFromDefault(model, screenHeight)
            regionsOverride = if (adapt) model else null
            overrideFailures = 0
            probeInFlight = false
            CaptureBus.publishStatus(if (adapt) "已按实际版面定位题目区域" else "识别区域正常")
            frame.release()
        }
    }

    /** 检测出的选项区与默认比例区域差得够多才值得改，否则保持原行为。 */
    private fun differsFromDefault(model: ScreenRegions, screenHeight: Int): Boolean {
        val tolerance = Math.round(screenHeight * LAYOUT_ADAPT_MIN_SHIFT)
        val defaultTop = Math.round(screenHeight * LEGACY_OPTIONS_TOP)
        val defaultBottom = Math.round(screenHeight * LEGACY_OPTIONS_BOTTOM)
        return abs(model.options.top - defaultTop) > tolerance ||
            abs(model.options.bottom - defaultBottom) > tolerance
    }

    private fun analyzeRegions(
        frame: Frame,
        questionRegion: Rect,
        optionsRegion: Rect,
        screenWidth: Int,
        adapted: Boolean
    ) {
        val questionCrop = crop(frame.bitmap, questionRegion)
        if (questionCrop == null) {
            frame.release()
            return
        }
        frame.questionCrop = questionCrop
        try {
            val chineseTask = chineseRecognizer!!.process(InputImage.fromBitmap(questionCrop, 0))
            val englishTask = latinRecognizer!!.process(InputImage.fromBitmap(questionCrop, 0))
            Tasks.whenAll(chineseTask, englishTask).addOnCompleteListener(captureExecutor) { task ->
                try {
                    if (!task.isSuccessful) {
                        CaptureBus.publishStatus("题干识别失败，请重试")
                        onStructuralMiss(adapted)
                        return@addOnCompleteListener
                    }
                    val chineseLines = flatten(chineseTask.result)
                    val englishLines = flatten(englishTask.result)
                    val englishTarget =
                        matcher?.findEnglishPromptWord(englishLines, screenWidth)
                    // 题干的语种比选项区附近的拉丁文字更可靠，所以中文题干优先。
                    when {
                        hasChineseQuestion(chineseLines) -> {
                            frame.handOff()
                            recognizeEnglishOptions(frame, optionsRegion, chineseLines, adapted)
                        }
                        englishTarget != null -> {
                            frame.handOff()
                            recognizeChineseOptions(
                                frame, optionsRegion, englishTarget.word, adapted
                            )
                        }
                        else -> {
                            CaptureBus.publishStatus("未识别到中英文题干")
                            onStructuralMiss(adapted)
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "题干处理失败", error)
                    CaptureBus.publishStatus("识别失败，请重试")
                } finally {
                    // 没有交给下一阶段就在这里释放；release 幂等，交给下一阶段时它是空操作。
                    if (!frame.isHandedOff()) frame.release()
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "题干 OCR 派发失败", error)
            frame.release()
            CaptureBus.publishStatus("识别失败，请重试")
        }
    }

    private fun hasChineseQuestion(lines: List<Text.Line>): Boolean {
        var count = 0
        for (line in lines) {
            for (ch in line.text) {
                if (ch in '\u3400'..'\u9FFF') count++
            }
        }
        return count >= 3
    }

    /** 中文题干 + 英文选项。 */
    private fun recognizeEnglishOptions(
        frame: Frame,
        optionsRegion: Rect,
        questionLines: List<Text.Line>,
        adapted: Boolean
    ) {
        val optionsCrop = crop(frame.bitmap, optionsRegion)
        if (optionsCrop == null) {
            frame.release()
            onStructuralMiss(adapted)
            return
        }
        frame.optionsCrop = optionsCrop
        try {
            latinRecognizer!!.process(InputImage.fromBitmap(optionsCrop, 0))
                .addOnSuccessListener(captureExecutor) { text ->
                    val seenOptions = ArrayList<String>()
                    val choice = matcher?.findBestEnglishOption(
                        questionLines, flatten(text), optionsRegion.top, seenOptions
                    )
                    showChoice(choice, seenOptions, adapted)
                }
                .addOnFailureListener(captureExecutor) {
                    CaptureBus.publishStatus("英文选项识别失败")
                }
                .addOnCompleteListener(captureExecutor) { frame.release() }
        } catch (error: Exception) {
            Log.w(TAG, "英文选项 OCR 派发失败", error)
            frame.release()
            CaptureBus.publishStatus("英文选项识别失败")
        }
    }

    /** 英文题干 + 中文选项。 */
    private fun recognizeChineseOptions(
        frame: Frame,
        optionsRegion: Rect,
        englishTarget: String,
        adapted: Boolean
    ) {
        val optionsCrop = crop(frame.bitmap, optionsRegion)
        if (optionsCrop == null) {
            frame.release()
            onStructuralMiss(adapted)
            return
        }
        frame.optionsCrop = optionsCrop
        try {
            chineseRecognizer!!.process(InputImage.fromBitmap(optionsCrop, 0))
                .addOnSuccessListener(captureExecutor) { text ->
                    val seenOptions = ArrayList<String>()
                    val choice = matcher?.findBestChineseOption(
                        englishTarget, flatten(text), optionsRegion.top, seenOptions
                    )
                    showChoice(choice, seenOptions, adapted)
                }
                .addOnFailureListener(captureExecutor) {
                    CaptureBus.publishStatus("中文选项识别失败")
                }
                .addOnCompleteListener(captureExecutor) { frame.release() }
        } catch (error: Exception) {
            Log.w(TAG, "中文选项 OCR 派发失败", error)
            frame.release()
            CaptureBus.publishStatus("中文选项识别失败")
        }
    }

    private fun showChoice(choice: Choice?, seenOptions: List<String>, adapted: Boolean) {
        if (choice == null) {
            if (seenOptions.isEmpty()) {
                // 选项区里一个字都没读到：多半是识别区域不对
                CaptureBus.publishStatus("选项区没有识别到文字")
                onStructuralMiss(adapted)
            } else {
                // 读到了选项但词表里没有：换区域也没用，直接把缺口显示出来
                CaptureBus.publishStatus("词表未覆盖：" + summarize(seenOptions))
            }
            return
        }
        overrideFailures = 0
        val text = "答案：" + choice.meaning
        CaptureBus.publishAnswer(text)
        if (SettingsStore.current.runMode == RunMode.Auto) maybeTap(choice)
    }

    /** 把识别到的选项压成一行短提示，方便按需补词表。 */
    private fun summarize(options: List<String>): String {
        val parts = ArrayList<String>(4)
        for (option in options) {
            if (parts.size >= 4) break
            val text = if (option.length > 12) option.substring(0, 12) + "…" else option
            if (!parts.contains(text)) parts.add(text)
        }
        return TextUtils.join(" / ", parts)
    }

    /**
     * 题面结构没认出来。默认区域失败就请求一次版面探测；自适应区域连续失败就放弃它重新探测。
     */
    private fun onStructuralMiss(adapted: Boolean) {
        if (!adapted) {
            probeRequested = true
            return
        }
        if (++overrideFailures >= MAX_OVERRIDE_FAILURES) {
            regionsOverride = null
            overrideFailures = 0
            probeRequested = true
        }
    }

    private fun crop(bitmap: Bitmap, region: Rect): Bitmap? {
        val left = maxOf(0, region.left)
        val top = maxOf(0, region.top)
        val right = minOf(bitmap.width, region.right)
        val bottom = minOf(bitmap.height, region.bottom)
        if (right - left <= 0 || bottom - top <= 0) return null
        return try {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        } catch (error: Exception) {
            Log.w(TAG, "裁剪失败", error)
            null
        }
    }

    private fun flatten(text: Text): List<Text.Line> {
        val lines = ArrayList<Text.Line>()
        for (block in text.textBlocks) lines.addAll(block.lines)
        return lines
    }

    private fun maybeTap(choice: Choice) {
        val settings = SettingsStore.current
        val signature = choice.word + "|" + choice.meaning
        val x = choice.box.centerX()
        val y = choice.box.centerY()
        val now = System.currentTimeMillis()
        if (now - lastTapAt < settings.tapIntervalMs) return
        val sameAnswer = signature == lastTappedSignature
        val samePlace = abs(x - lastTappedX) <= settings.moveTolerancePx &&
            abs(y - lastTappedY) <= settings.moveTolerancePx
        // 同一个答案只有在「位置也没变、并且还在冷却期内」时才跳过。新一题给出同样的
        // 正确选项、或者选项被洗到别的位置时，都要照常点击，否则这个答案会被永久漏掉。
        if (sameAnswer && samePlace && now - lastTapAt < settings.sameAnswerCooldownMs) return
        lastTappedSignature = signature
        lastTappedX = x
        lastTappedY = y
        lastTapAt = now

        val dispatched = WordPkAccessibilityService.tap(x.toFloat(), y.toFloat()) { ok ->
            if (!ok) {
                // 手势被系统拦截（输入法、权限弹窗、别的悬浮窗抢占）：清掉冷却允许重试
                captureHandler.post {
                    lastTapAt = 0L
                    CaptureBus.publishStatus("点击被系统拦截，请确认无障碍服务仍在运行")
                }
            }
        }
        if (!dispatched) {
            lastTapAt = 0L
            CaptureBus.publishStatus("无障碍服务未连接，无法自动点击")
        }
    }

    /** 释放录屏会话相关的全部资源，可以重复调用；只在 capture 线程上执行。 */
    private fun releaseSession() {
        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null
        imageReader?.let { reader ->
            runCatching {
                reader.setOnImageAvailableListener(null, null)
                reader.close()
            }
        }
        imageReader = null
        projection?.let { session ->
            // 先摘掉回调再 stop()，避免 stop() 触发的 onStop 又回调回来
            runCatching { session.unregisterCallback(projectionCallback) }
            runCatching { session.stop() }
        }
        projection = null
        probeInFlight = false
        probeRequested = false
        regionsOverride = null
        overrideFailures = 0
        layoutProbes = 0
        layoutScreenKey = -1L
        ocrBusy.set(false)
        lastFrameAt = 0L
        CaptureBus.setRunning(false)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "屏幕识别", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("单词 PK 助手正在识别")
            .setContentText("识别仅在本机进行，可从这里停止")
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "停止识别", stopPending
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (::captureHandler.isInitialized) {
            captureHandler.removeCallbacksAndMessages(null)
        }
        releaseSession()
        latinRecognizer?.close()
        latinRecognizer = null
        chineseRecognizer?.close()
        chineseRecognizer = null
        if (::captureThread.isInitialized) captureThread.quitSafely()
        if (instance === this) instance = null
        CaptureBus.resetSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
