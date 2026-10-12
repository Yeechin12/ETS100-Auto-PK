package com.codex.wordpkhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenCaptureService extends Service {
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    static final String EXTRA_AUTO = "auto";
    static final String ACTION_STOP = "com.codex.wordpkhelper.action.STOP";
    private static final String CHANNEL = "wordpk_capture";
    private static final int NOTIFICATION_ID = 19;
    /** 两次点击之间的最短间隔，避免同一帧被反复点击。 */
    private static final long MIN_TAP_INTERVAL_MS = 1800;
    /** 同一答案同一位置的冷却时间；冷却结束后允许再点，避免同一个答案被永久拉黑。 */
    private static final long SAME_ANSWER_COOLDOWN_MS = 6000;
    /** 答案框位移超过这个像素数就视为换了新题，可以立即再次点击。 */
    private static final int TAP_MOVE_TOLERANCE_PX = 24;

    // 默认识别区域：按屏幕高度比例切两条带（旧行为）。长屏、平板、横屏这类比例明显不同的
    // 设备上会错位，所以它只是默认值；默认区域认不出题面时改用版面检测出来的区域。
    private static final float LEGACY_QUESTION_TOP = 0.22f;
    private static final float LEGACY_QUESTION_BOTTOM = 0.33f;
    private static final float LEGACY_OPTIONS_TOP = 0.34f;
    private static final float LEGACY_OPTIONS_BOTTOM = 0.70f;
    /** 检测出的区域要和默认区域差得够多才启用，保证原本工作正常的机型行为不变。 */
    private static final float LAYOUT_ADAPT_MIN_SHIFT = 0.06f;
    /** 版面探测的最小间隔、次数上限；自适应区域连续失败的次数上限。 */
    private static final long LAYOUT_PROBE_COOLDOWN_MS = 3000;
    private static final int MAX_LAYOUT_PROBES = 8;
    private static final int MAX_OVERRIDE_FAILURES = 3;

    private static volatile ScreenCaptureService instance;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection projection;
    private TextRecognizer latinRecognizer;
    private TextRecognizer chineseRecognizer;
    private Task<Void> recognizersReady;
    private VocabMatcher matcher;
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private boolean autoMode;
    private long lastFrameAt;
    private long lastTapAt;
    private String lastTappedQuestion = "";
    private int lastTappedX = -1;
    private int lastTappedY = -1;

    private volatile ScreenLayout.Model regionsOverride;
    private boolean probeRequested;
    private boolean probeInFlight;
    private int overrideFailures;
    private int layoutProbes;
    private long lastProbeAt;
    private long layoutScreenKey = -1;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            // 用户在系统状态栏停止共享屏幕时由系统回调
            releaseSession();
            stopSelf();
        }
    };

    /** 服务当前是否已经持有一个可用的录屏会话。 */
    static boolean isRunning() {
        ScreenCaptureService service = instance;
        return service != null && service.projection != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        captureThread = new HandlerThread("wordpk-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        latinRecognizer = com.google.mlkit.vision.text.TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        chineseRecognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
        warmUpRecognizers();
        matcher = new VocabMatcher(this);
    }

    private void warmUpRecognizers() {
        Bitmap sample = Bitmap.createBitmap(192, 96, Bitmap.Config.ARGB_8888);
        sample.eraseColor(Color.WHITE);
        InputImage input = InputImage.fromBitmap(sample, 0);
        Task<Text> chineseWarmup = chineseRecognizer.process(input);
        Task<Text> latinWarmup = latinRecognizer.process(input);
        recognizersReady = Tasks.whenAll(chineseWarmup, latinWarmup);
        recognizersReady.addOnCompleteListener(task -> sample.recycle());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }

        autoMode = intent.getBooleanExtra(EXTRA_AUTO, autoMode);

        Intent resultData = getIntentExtra(intent, EXTRA_RESULT_DATA);
        if (resultData == null) {
            // 没有新的录屏授权：会话还在就只更新运行模式，否则收工
            if (projection != null) {
                WordPkAccessibilityService.showStatus(autoMode ? "已切换为自动点击" : "已切换为仅显示答案");
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        if (projection != null) {
            // 已经有一个会话在跑：丢掉这次重复的授权，不要在同一个服务里同时存在两个
            // MediaProjection 或 VirtualDisplay（旧的那个从此没人释放）。
            WordPkAccessibilityService.showStatus(autoMode ? "已切换为自动点击" : "已切换为仅显示答案");
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            final MediaProjection session = manager.getMediaProjection(resultCode, resultData);
            projection = session;
            session.registerCallback(projectionCallback, captureHandler);
            WordPkAccessibilityService.showStatus("正在准备识别");
            recognizersReady.addOnCompleteListener(task -> {
                // 只有仍是同一个会话时才建立虚拟显示
                if (projection == session && virtualDisplay == null) {
                    startCapture(session);
                    WordPkAccessibilityService.showStatus("正在识别中文释义");
                }
            });
        } catch (Exception e) {
            WordPkAccessibilityService.showStatus("无法开始屏幕识别");
            releaseSession();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getIntentExtra(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(key, Intent.class);
        return intent.getParcelableExtra(key);
    }

    private void startCapture(MediaProjection session) {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null || System.currentTimeMillis() - lastFrameAt < 700) return;
                if (ocrBusy.get()) return;
                lastFrameAt = System.currentTimeMillis();
                Bitmap bitmap = imageToBitmap(image);
                if (bitmap == null) return;
                if (ocrBusy.compareAndSet(false, true)) {
                    try {
                        analyze(bitmap, height);
                    } catch (Exception error) {
                        // analyze 里抛异常也必须放锁，否则后续帧会被一直跳过
                        releaseFrame(bitmap, null, null);
                        WordPkAccessibilityService.showStatus("识别失败，请重试");
                    }
                } else {
                    bitmap.recycle();
                }
            } catch (Exception ignored) {
            } finally {
                if (image != null) image.close();
            }
        }, captureHandler);
        virtualDisplay = session.createVirtualDisplay("wordpk-screen", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, captureHandler);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void analyze(Bitmap bitmap, int screenHeight) {
        int screenWidth = bitmap.getWidth();
        long screenKey = ((long) screenWidth << 32) | (screenHeight & 0xFFFFFFFFL);
        if (screenKey != layoutScreenKey) {
            // 分辨率或方向变了：之前探测出来的区域不再可信
            layoutScreenKey = screenKey;
            regionsOverride = null;
            overrideFailures = 0;
            layoutProbes = 0;
            probeRequested = false;
        }
        if (matcher.reloadIfChanged()) {
            WordPkAccessibilityService.showStatus("词表已更新，共 " + matcher.size() + " 条");
        }
        if (probeRequested && !probeInFlight && layoutProbes < MAX_LAYOUT_PROBES
                && System.currentTimeMillis() - lastProbeAt >= LAYOUT_PROBE_COOLDOWN_MS) {
            probeRequested = false;
            probeLayout(bitmap, screenWidth, screenHeight);
            return;
        }

        ScreenLayout.Model override = regionsOverride;
        Rect questionRegion;
        Rect optionsRegion;
        if (override != null) {
            questionRegion = override.questionRegion;
            optionsRegion = override.optionsRegion;
        } else {
            questionRegion = new Rect(0, Math.round(screenHeight * LEGACY_QUESTION_TOP), screenWidth,
                    Math.round(screenHeight * LEGACY_QUESTION_BOTTOM));
            optionsRegion = new Rect(0, Math.round(screenHeight * LEGACY_OPTIONS_TOP), screenWidth,
                    Math.round(screenHeight * LEGACY_OPTIONS_BOTTOM));
        }
        analyzeRegions(bitmap, questionRegion, optionsRegion, screenWidth, override != null);
    }

    /**
     * 用整屏 OCR 探一次版面结构，成功后后续帧改用检测出来的区域。探测这一帧不答题
     * （多花的开销只有这一帧），结构不明显时继续沿用默认比例区域。
     */
    private void probeLayout(Bitmap bitmap, int screenWidth, int screenHeight) {
        probeInFlight = true;
        lastProbeAt = System.currentTimeMillis();
        layoutProbes++;
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        Task<Text> chineseTask = chineseRecognizer.process(input);
        Task<Text> latinTask = latinRecognizer.process(input);
        Tasks.whenAll(chineseTask, latinTask)
                .addOnSuccessListener(ignored -> {
                    List<Text.Line> lines = new ArrayList<>(flatten(chineseTask.getResult()));
                    lines.addAll(flatten(latinTask.getResult()));
                    ScreenLayout.Model model = ScreenLayout.detect(lines, screenWidth, screenHeight);
                    boolean adapt = model != null && differsFromDefault(model, screenHeight);
                    regionsOverride = adapt ? model : null;
                    overrideFailures = 0;
                    WordPkAccessibilityService.showStatus(adapt ? "已按实际版面定位题目区域" : "识别区域正常");
                })
                .addOnFailureListener(error -> regionsOverride = null)
                .addOnCompleteListener(task -> {
                    probeInFlight = false;
                    releaseFrame(bitmap, null, null);
                });
    }

    /** 检测出的选项区与默认比例区域差得够多才值得改，否则保持原行为。 */
    private boolean differsFromDefault(ScreenLayout.Model model, int screenHeight) {
        int tolerance = Math.round(screenHeight * LAYOUT_ADAPT_MIN_SHIFT);
        int defaultTop = Math.round(screenHeight * LEGACY_OPTIONS_TOP);
        int defaultBottom = Math.round(screenHeight * LEGACY_OPTIONS_BOTTOM);
        return Math.abs(model.optionsRegion.top - defaultTop) > tolerance
                || Math.abs(model.optionsRegion.bottom - defaultBottom) > tolerance;
    }

    private void analyzeRegions(Bitmap bitmap, Rect questionRegion, Rect optionsRegion,
                                int screenWidth, boolean adapted) {
        Bitmap questionCrop = crop(bitmap, questionRegion);
        if (questionCrop == null) {
            releaseFrame(bitmap, null, null);
            return;
        }
        try {
            Task<Text> chineseQuestionTask = chineseRecognizer.process(
                    InputImage.fromBitmap(questionCrop, 0));
            Task<Text> englishQuestionTask = latinRecognizer.process(
                    InputImage.fromBitmap(questionCrop, 0));
            Tasks.whenAll(chineseQuestionTask, englishQuestionTask)
                    .addOnSuccessListener(ignored -> {
                        List<Text.Line> chineseQuestionLines = flatten(chineseQuestionTask.getResult());
                        List<Text.Line> englishQuestionLines = flatten(englishQuestionTask.getResult());
                        VocabMatcher.Match englishTarget =
                                matcher.findEnglishPromptWord(englishQuestionLines, screenWidth);
                        // The prompt's language is more reliable than OCR results from
                        // Latin text near the answer area; Chinese questions must win.
                        if (hasChineseQuestion(chineseQuestionLines)) {
                            recognizeEnglishOptions(bitmap, questionCrop, optionsRegion,
                                    chineseQuestionLines, adapted);
                        } else if (englishTarget != null) {
                            recognizeChineseOptions(bitmap, questionCrop, optionsRegion,
                                    englishTarget.word, adapted);
                        } else {
                            WordPkAccessibilityService.showStatus("未识别到中英文题干");
                            onStructuralMiss(adapted);
                            releaseFrame(bitmap, questionCrop, null);
                        }
                    })
                    .addOnFailureListener(error -> {
                        WordPkAccessibilityService.showStatus("题干识别失败，请重试");
                        releaseFrame(bitmap, questionCrop, null);
                    });
        } catch (Exception error) {
            releaseFrame(bitmap, questionCrop, null);
            WordPkAccessibilityService.showStatus("识别失败，请重试");
        }
    }

    private boolean hasChineseQuestion(List<Text.Line> lines) {
        int count = 0;
        for (Text.Line line : lines) {
            String text = line.getText();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch >= '\u3400' && ch <= '\u9FFF') count++;
            }
        }
        return count >= 3;
    }

    private void recognizeEnglishOptions(Bitmap bitmap, Bitmap questionCrop, Rect optionsRegion,
                                         List<Text.Line> questionLines, boolean adapted) {
        Bitmap optionsCrop = crop(bitmap, optionsRegion);
        if (optionsCrop == null) {
            releaseFrame(bitmap, questionCrop, null);
            onStructuralMiss(adapted);
            return;
        }
        try {
            latinRecognizer.process(InputImage.fromBitmap(optionsCrop, 0))
                    .addOnSuccessListener(text -> {
                        List<String> seenOptions = new ArrayList<>();
                        VocabMatcher.Choice choice = matcher.findBestEnglishOption(
                                questionLines, flatten(text), optionsRegion.top, seenOptions);
                        showChoice(choice, seenOptions, adapted);
                    })
                    .addOnFailureListener(error -> WordPkAccessibilityService.showStatus("英文选项识别失败"))
                    .addOnCompleteListener(task -> releaseFrame(bitmap, questionCrop, optionsCrop));
        } catch (Exception error) {
            releaseFrame(bitmap, questionCrop, optionsCrop);
            WordPkAccessibilityService.showStatus("英文选项识别失败");
        }
    }

    private void recognizeChineseOptions(Bitmap bitmap, Bitmap questionCrop, Rect optionsRegion,
                                         String englishTarget, boolean adapted) {
        Bitmap optionsCrop = crop(bitmap, optionsRegion);
        if (optionsCrop == null) {
            releaseFrame(bitmap, questionCrop, null);
            onStructuralMiss(adapted);
            return;
        }
        try {
            chineseRecognizer.process(InputImage.fromBitmap(optionsCrop, 0))
                    .addOnSuccessListener(text -> {
                        List<String> seenOptions = new ArrayList<>();
                        VocabMatcher.Choice choice = matcher.findBestChineseOption(
                                englishTarget, flatten(text), optionsRegion.top, seenOptions);
                        showChoice(choice, seenOptions, adapted);
                    })
                    .addOnFailureListener(error -> WordPkAccessibilityService.showStatus("中文选项识别失败"))
                    .addOnCompleteListener(task -> releaseFrame(bitmap, questionCrop, optionsCrop));
        } catch (Exception error) {
            releaseFrame(bitmap, questionCrop, optionsCrop);
            WordPkAccessibilityService.showStatus("中文选项识别失败");
        }
    }

    private void showChoice(VocabMatcher.Choice choice, List<String> seenOptions, boolean adapted) {
        if (choice == null) {
            if (seenOptions.isEmpty()) {
                // 选项区里一个字都没读到：多半是识别区域不对
                WordPkAccessibilityService.showStatus("选项区没有识别到文字");
                onStructuralMiss(adapted);
            } else {
                // 读到了选项但词表里没有：换区域也没用，直接把缺口显示出来
                WordPkAccessibilityService.showStatus("词表未覆盖：" + summarize(seenOptions));
            }
            return;
        }
        overrideFailures = 0;
        WordPkAccessibilityService.showStatus("答案：" + choice.meaning);
        if (autoMode) maybeTap(choice);
    }

    /** 把识别到的选项压成一行短提示，方便按需补词表。 */
    private String summarize(List<String> options) {
        List<String> parts = new ArrayList<>();
        for (String option : options) {
            if (parts.size() >= 4) break;
            String text = option.length() > 12 ? option.substring(0, 12) + "…" : option;
            if (!parts.contains(text)) parts.add(text);
        }
        return TextUtils.join(" / ", parts);
    }

    /**
     * 题面结构没认出来。默认区域失败就请求一次版面探测；自适应区域连续失败就放弃它重新探测。
     */
    private void onStructuralMiss(boolean adapted) {
        if (!adapted) {
            probeRequested = true;
            return;
        }
        if (++overrideFailures >= MAX_OVERRIDE_FAILURES) {
            regionsOverride = null;
            overrideFailures = 0;
            probeRequested = true;
        }
    }

    private Bitmap crop(Bitmap bitmap, Rect region) {
        int left = Math.max(0, region.left);
        int top = Math.max(0, region.top);
        int right = Math.min(bitmap.getWidth(), region.right);
        int bottom = Math.min(bitmap.getHeight(), region.bottom);
        if (right - left <= 0 || bottom - top <= 0) return null;
        try {
            return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
        } catch (Exception error) {
            return null;
        }
    }

    private void releaseFrame(Bitmap bitmap, Bitmap questionCrop, Bitmap optionsCrop) {
        if (optionsCrop != null && !optionsCrop.isRecycled()) optionsCrop.recycle();
        if (questionCrop != null && !questionCrop.isRecycled()) questionCrop.recycle();
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        ocrBusy.set(false);
    }

    private List<Text.Line> flatten(Text text) {
        List<Text.Line> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) lines.addAll(block.getLines());
        return lines;
    }

    private void maybeTap(VocabMatcher.Choice choice) {
        if (choice == null || choice.box == null) return;
        String signature = choice.word + "|" + choice.meaning;
        int x = choice.box.centerX();
        int y = choice.box.centerY();
        long now = System.currentTimeMillis();
        if (now - lastTapAt < MIN_TAP_INTERVAL_MS) return;
        boolean sameAnswer = signature.equals(lastTappedQuestion);
        boolean samePlace = Math.abs(x - lastTappedX) <= TAP_MOVE_TOLERANCE_PX
                && Math.abs(y - lastTappedY) <= TAP_MOVE_TOLERANCE_PX;
        // 同一个答案只有在“位置也没变、并且还在冷却期内”时才跳过。新一题给出同样的
        // 正确选项、或者选项被洗到别的位置时，都要照常点击，否则这个答案会被永久漏掉。
        if (sameAnswer && samePlace && now - lastTapAt < SAME_ANSWER_COOLDOWN_MS) return;
        lastTappedQuestion = signature;
        lastTappedX = x;
        lastTappedY = y;
        lastTapAt = now;
        WordPkAccessibilityService.tap(x, y);
    }

    /** 释放录屏会话相关的全部资源，可以重复调用。 */
    private void releaseSession() {
        VirtualDisplay display = virtualDisplay;
        virtualDisplay = null;
        if (display != null) {
            try { display.release(); } catch (Exception ignored) { }
        }
        ImageReader reader = imageReader;
        imageReader = null;
        if (reader != null) {
            try {
                reader.setOnImageAvailableListener(null, null);
                reader.close();
            } catch (Exception ignored) { }
        }
        MediaProjection session = projection;
        projection = null;
        if (session != null) {
            // 先摘掉回调再 stop()，避免 stop() 触发的 onStop 又回调回来
            try { session.unregisterCallback(projectionCallback); } catch (Exception ignored) { }
            try { session.stop(); } catch (Exception ignored) { }
        }
        probeInFlight = false;
        probeRequested = false;
        regionsOverride = null;
        overrideFailures = 0;
        layoutProbes = 0;
        layoutScreenKey = -1;
        ocrBusy.set(false);
        lastFrameAt = 0;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "屏幕识别", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Intent stopIntent = new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder.setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("单词 PK 助手正在识别")
                .setContentText("识别仅在本机进行，可从这里停止")
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止识别", stopPending)
                .setOngoing(true).build();
    }

    @Override
    public void onDestroy() {
        releaseSession();
        if (latinRecognizer != null) { latinRecognizer.close(); latinRecognizer = null; }
        if (chineseRecognizer != null) { chineseRecognizer.close(); chineseRecognizer = null; }
        if (captureThread != null) { captureThread.quitSafely(); captureThread = null; }
        if (instance == this) instance = null;
        WordPkAccessibilityService.showStatus("");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
