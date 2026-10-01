package com.codex.wordpkhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

public class WordPkAccessibilityService extends AccessibilityService {
    private static volatile WordPkAccessibilityService instance;
    private WindowManager windowManager;
    private TextView overlay;
    private WindowManager.LayoutParams overlayParams;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showStatus("单词 PK 助手已就绪");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) { }
            overlay = null;
        }
        super.onDestroy();
    }

    public static void showStatus(String text) {
        WordPkAccessibilityService service = instance;
        if (service != null) service.runOnUiThread(() -> service.updateOverlay(text));
    }

    public static void tap(float x, float y) {
        WordPkAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < 24) return;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        service.dispatchGesture(gesture, null, null);
    }

    private void runOnUiThread(Runnable runnable) {
        new android.os.Handler(getMainLooper()).post(runnable);
    }

    private void updateOverlay(String text) {
        if (windowManager == null) return;
        if (overlay == null) {
            overlay = new TextView(this);
            overlay.setTextColor(Color.WHITE);
            overlay.setTextSize(14);
            overlay.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            overlay.setGravity(Gravity.CENTER);
            overlay.setPadding(dp(12), dp(7), dp(12), dp(7));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE6223444);
            bg.setCornerRadius(dp(12));
            overlay.setBackground(bg);
            int type = Build.VERSION.SDK_INT >= 22
                    ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            overlayParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            overlayParams.y = dp(28);
            try { windowManager.addView(overlay, overlayParams); } catch (Exception ignored) { }
        }
        overlay.setText(text);
        overlay.setVisibility(text == null || text.isEmpty() ? TextView.GONE : TextView.VISIBLE);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
