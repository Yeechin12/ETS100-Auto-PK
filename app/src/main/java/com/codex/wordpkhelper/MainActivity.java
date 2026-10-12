package com.codex.wordpkhelper;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 41;
    private static final int REQUEST_NOTIFICATIONS = 42;
    private RadioButton autoMode;
    private TextView state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFFF6F8FA);
        getWindow().setNavigationBarColor(0xFFF6F8FA);
        buildUi();
    }

    private void buildUi() {
        int pad = dp(22);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, dp(26), pad, pad);
        page.setBackgroundColor(0xFFF6F8FA);

        TextView title = new TextView(this);
        title.setText("单词 PK 助手");
        title.setTextSize(28);
        title.setTextColor(0xFF14283A);
        title.setTypeface(null, 1);
        page.addView(title);

        TextView intro = new TextView(this);
        intro.setText("自动判断题目方向，在中英选项中定位答案。识别数据仅在本机处理。\n\n词表：上海教育出版社 Unit 1–6");
        intro.setTextSize(15);
        intro.setTextColor(0xFF566675);
        intro.setPadding(0, dp(12), 0, dp(18));
        page.addView(intro);

        TextView modeTitle = new TextView(this);
        modeTitle.setText("运行模式");
        modeTitle.setTextSize(17);
        modeTitle.setTextColor(0xFF14283A);
        modeTitle.setTypeface(null, 1);
        page.addView(modeTitle);

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(LinearLayout.VERTICAL);
        autoMode = new RadioButton(this);
        autoMode.setText("自动识别并点击正确答案");
        autoMode.setTextSize(16);
        RadioButton showMode = new RadioButton(this);
        showMode.setText("只识别并显示答案，不点击");
        showMode.setTextSize(16);
        modes.addView(autoMode);
        modes.addView(showMode);
        boolean auto = getPreferences(MODE_PRIVATE).getBoolean("auto", true);
        autoMode.setChecked(auto);
        showMode.setChecked(!auto);
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isAuto = checkedId == autoMode.getId();
            getPreferences(MODE_PRIVATE).edit().putBoolean("auto", isAuto).apply();
            // 正在识别时立刻把新的运行模式同步给服务，不必重新授权录屏
            if (ScreenCaptureService.isRunning()) {
                Intent update = new Intent(this, ScreenCaptureService.class);
                update.putExtra(ScreenCaptureService.EXTRA_AUTO, isAuto);
                startService(update);
                state.setText(isAuto ? "已切换为自动点击。" : "已切换为仅显示答案。");
            }
        });
        page.addView(modes);

        Button accessButton = button("第 1 步：开启无障碍服务");
        accessButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        page.addView(accessButton, buttonParams());

        Button startButton = button("第 2 步：开始识别");
        startButton.setOnClickListener(v -> beginCapture());
        page.addView(startButton, buttonParams());

        Button stopButton = button("停止识别");
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            state.setText("已停止");
        });
        page.addView(stopButton, buttonParams());

        state = new TextView(this);
        state.setText("准备就绪。开始后切换到 e听说中学的单词 PK 页面。");
        state.setTextSize(14);
        state.setTextColor(0xFF566675);
        state.setPadding(0, dp(18), 0, 0);
        page.addView(state);
        setContentView(page);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.topMargin = dp(10);
        return p;
    }

    private void beginCapture() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先在无障碍设置中启用“单词 PK 助手”", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        // 已经在识别时不重新申请录屏授权，只更新运行模式：重复授权会带来多余的
        // MediaProjection 会话，而旧会话并不会因此被释放。
        if (ScreenCaptureService.isRunning()) {
            Intent update = new Intent(this, ScreenCaptureService.class);
            update.putExtra(ScreenCaptureService.EXTRA_AUTO, autoMode.isChecked());
            startService(update);
            state.setText("识别已在运行，已更新运行模式。");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // 没有通知权限时前台服务通知会被静默，用户就看不到状态栏里的“停止识别”入口
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            // 通知被拒绝不影响识别本身，只是通知栏里没有停止入口
            Toast.makeText(this, "未授予通知权限：仍可识别，但通知栏不会显示停止入口", Toast.LENGTH_LONG).show();
        }
        requestScreenCapture();
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String ours = new ComponentName(this, WordPkAccessibilityService.class).flattenToString();
        return enabled != null && enabled.toLowerCase().contains(ours.toLowerCase());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            state.setText("屏幕捕获未获授权。");
            return;
        }
        Intent service = new Intent(this, ScreenCaptureService.class);
        service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        service.putExtra(ScreenCaptureService.EXTRA_AUTO, autoMode.isChecked());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
        state.setText("正在识别。可切换到 PK 页面；通知栏或“停止识别”按钮可结束。");
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
