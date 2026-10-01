package com.codex.wordpkhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
    private static final String CHANNEL = "wordpk_capture";
    private static final int NOTIFICATION_ID = 19;

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

    @Override
    public void onCreate() {
        super.onCreate();
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
        autoMode = intent.getBooleanExtra(EXTRA_AUTO, true);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = getIntentExtra(intent, EXTRA_RESULT_DATA);
        if (resultData == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, captureHandler);
            WordPkAccessibilityService.showStatus("正在准备识别");
            recognizersReady.addOnCompleteListener(task -> {
                if (projection != null && virtualDisplay == null) {
                    startCapture();
                    WordPkAccessibilityService.showStatus("正在识别中文释义");
                }
            });
        } catch (Exception e) {
            WordPkAccessibilityService.showStatus("无法开始屏幕识别");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getIntentExtra(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(key, Intent.class);
        return intent.getParcelableExtra(key);
    }

    private void startCapture() {
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
                if (bitmap != null && ocrBusy.compareAndSet(false, true)) analyze(bitmap, height);
                else if (bitmap != null) bitmap.recycle();
            } catch (Exception ignored) {
            } finally {
                if (image != null) image.close();
            }
        }, captureHandler);
        virtualDisplay = projection.createVirtualDisplay("wordpk-screen", width, height, density,
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
        // Keep the question crop clear of the first answer row. On some devices
        // that row begins near 35% of screen height and was misread as an English prompt.
        int questionTop = Math.round(screenHeight * 0.22f);
        int questionBottom = Math.round(screenHeight * 0.33f);
        int optionsTop = Math.round(screenHeight * 0.34f);
        int optionsBottom = Math.round(screenHeight * 0.70f);
        Bitmap questionCrop = Bitmap.createBitmap(bitmap, 0, questionTop, bitmap.getWidth(), questionBottom - questionTop);
        try {
            Task<Text> chineseQuestionTask = chineseRecognizer.process(
                    InputImage.fromBitmap(questionCrop, 0));
            Task<Text> englishQuestionTask = latinRecognizer.process(
                    InputImage.fromBitmap(questionCrop, 0));
            Tasks.whenAll(chineseQuestionTask, englishQuestionTask)
                    .addOnSuccessListener(ignored -> {
                        List<Text.Line> chineseQuestionLines = flatten(chineseQuestionTask.getResult());
                        List<Text.Line> englishQuestionLines = flatten(englishQuestionTask.getResult());
                        VocabMatcher.Match englishTarget = matcher.findEnglishPromptWord(englishQuestionLines);
                        // The prompt's language is more reliable than OCR results from
                        // Latin text near the answer area; Chinese questions must win.
                        if (hasChineseQuestion(chineseQuestionLines)) {
                            recognizeEnglishOptions(bitmap, questionCrop, optionsTop, optionsBottom,
                                    chineseQuestionLines);
                        } else if (englishTarget != null) {
                            recognizeChineseOptions(bitmap, questionCrop, optionsTop, optionsBottom,
                                    englishTarget.word);
                        } else {
                            WordPkAccessibilityService.showStatus("未识别到中英文题干");
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

    private void recognizeEnglishOptions(Bitmap bitmap, Bitmap questionCrop, int optionsTop,
                                         int optionsBottom, List<Text.Line> questionLines) {
        Bitmap optionsCrop = Bitmap.createBitmap(bitmap, 0, optionsTop, bitmap.getWidth(), optionsBottom - optionsTop);
        try {
            latinRecognizer.process(InputImage.fromBitmap(optionsCrop, 0))
                    .addOnSuccessListener(text -> {
                        VocabMatcher.Choice choice = matcher.findBestEnglishOption(
                                questionLines, flatten(text), optionsTop);
                        showChoice(choice);
                    })
                    .addOnFailureListener(error -> WordPkAccessibilityService.showStatus("英文选项识别失败"))
                    .addOnCompleteListener(task -> releaseFrame(bitmap, questionCrop, optionsCrop));
        } catch (Exception error) {
            releaseFrame(bitmap, questionCrop, optionsCrop);
            WordPkAccessibilityService.showStatus("英文选项识别失败");
        }
    }

    private void recognizeChineseOptions(Bitmap bitmap, Bitmap questionCrop, int optionsTop,
                                         int optionsBottom, String englishTarget) {
        Bitmap optionsCrop = Bitmap.createBitmap(bitmap, 0, optionsTop, bitmap.getWidth(), optionsBottom - optionsTop);
        try {
            chineseRecognizer.process(InputImage.fromBitmap(optionsCrop, 0))
                    .addOnSuccessListener(text -> {
                        VocabMatcher.Choice choice = matcher.findBestChineseOption(
                                englishTarget, flatten(text), optionsTop);
                        showChoice(choice);
                    })
                    .addOnFailureListener(error -> WordPkAccessibilityService.showStatus("中文选项识别失败"))
                    .addOnCompleteListener(task -> releaseFrame(bitmap, questionCrop, optionsCrop));
        } catch (Exception error) {
            releaseFrame(bitmap, questionCrop, optionsCrop);
            WordPkAccessibilityService.showStatus("中文选项识别失败");
        }
    }

    private void showChoice(VocabMatcher.Choice choice) {
        if (choice == null) {
            WordPkAccessibilityService.showStatus("未匹配到 Unit 1–6 词表中的选项");
            return;
        }
        WordPkAccessibilityService.showStatus("答案：" + choice.meaning);
        if (autoMode) maybeTap(choice);
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
        long now = System.currentTimeMillis();
        if (signature.equals(lastTappedQuestion) || now - lastTapAt < 1800) return;
        RectHolder box = new RectHolder(choice.box.centerX(), choice.box.centerY());
        lastTappedQuestion = signature;
        lastTapAt = now;
        WordPkAccessibilityService.tap(box.x, box.y);
    }

    private static final class RectHolder {
        final float x, y;
        RectHolder(float x, float y) { this.x = x; this.y = y; }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "屏幕识别", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("单词 PK 助手正在识别")
                .setContentText("回到应用并点“停止识别”以结束")
                .setOngoing(true).build();
    }

    @Override
    public void onDestroy() {
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        if (latinRecognizer != null) latinRecognizer.close();
        if (chineseRecognizer != null) chineseRecognizer.close();
        if (captureThread != null) captureThread.quitSafely();
        WordPkAccessibilityService.showStatus("");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
