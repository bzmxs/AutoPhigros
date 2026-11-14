package com.bzmxs.autophigros;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int OVERLAY_PERMISSION_REQUEST = 100;
    private static final int SCREEN_CAPTURE_REQUEST = 101;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 先把“崩溃自动记录”挂上，之后任何线程崩了都会写进文件
        CrashLogger.install(this);

        setContentView(R.layout.activity_main);

        showLastCrashIfAny();

        // 提前申请通知权限：Android 13+ 没有这个权限，前台服务的通知不会显示
        requestNotificationPermissionIfNeeded();

        Button btnStart = findViewById(R.id.btn_start_service);
        btnStart.setOnClickListener(v -> {
            // 1. 检查悬浮窗权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
                return;
            }

            // 2. 启动悬浮窗服务（先启动，后续再传录屏结果）
            startFloatingService();

            // 3. 请求录屏权限
            requestScreenCapture();
        });

        // 两个快捷启动按钮：一键打开 Phigros / Phira
        Button btnPhigros = findViewById(R.id.btn_open_phigros);
        btnPhigros.setOnClickListener(v -> openGame("phigros", "Phigros"));

        Button btnPhira = findViewById(R.id.btn_open_phira);
        btnPhira.setOnClickListener(v -> openGame("phira", "Phira"));
    }

    /**
     * 上次如果崩过，就把崩溃内容弹出来给你看（截图发我就行）。
     * 加了这个之后，就算拿不到 adb 日志也能知道崩在哪。
     */
    private void showLastCrashIfAny() {
        String crash = CrashLogger.lastCrash(this);
        if (crash == null) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("上次崩溃的日志")
                .setMessage(crash)
                .setPositiveButton("知道了", null)
                .setNeutralButton("清空", (d, w) -> CrashLogger.clear(this))
                .show();
    }

    /**
     * 打开游戏。
     * <p>
     * 分两步找：先试几个已知包名（最快最准），找不到再遍历所有能启动的 App，
     * 拿 App 名字/包名去模糊匹配。这样就算游戏换了包名、或者你装的是魔改版，
     * 只要图标名字里带 “Phigros” / “Phira” 就能打开。
     */
    private void openGame(String keyword, String showName) {
        Intent intent = findLaunchIntent(keyword);
        if (intent == null) {
            Toast.makeText(this, "没找到 " + showName + "，请确认已安装", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "打不开 " + showName + "：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Intent findLaunchIntent(String keyword) {
        PackageManager pm = getPackageManager();

        // 1) 已知包名
        String[] known;
        if ("phigros".equals(keyword)) {
            known = new String[]{"com.PigeonGames.Phigros", "com.pigeongames.phigros"};
        } else {
            known = new String[]{"org.rhythmdr.phira", "com.phira", "org.phira"};
        }
        for (String pkg : known) {
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i;
        }

        // 2) 按 App 名字 / 包名模糊找（需要清单里的 QUERY_ALL_PACKAGES 权限）
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo == null) continue;
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
            if (label.contains(keyword) || pkg.contains(keyword)) {
                return new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            }
        }
        return null;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void requestScreenCapture() {
        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(intent, SCREEN_CAPTURE_REQUEST);
    }

    private void startFloatingService() {
        Intent intent = new Intent(this, FloatingControlService.class);
        startService(intent);
        Toast.makeText(this, "悬浮窗已启动，请授权录屏", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingService();
                requestScreenCapture();
            } else {
                Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show();
            }
        }

        if (requestCode == SCREEN_CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK) {
                // 把录屏授权结果传递给悬浮窗服务
                Intent intent = new Intent(this, FloatingControlService.class);
                intent.putExtra("resultCode", resultCode);
                intent.putExtra("data", data);
                startService(intent);
                Toast.makeText(this, "录屏授权成功！", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "录屏授权被拒绝", Toast.LENGTH_SHORT).show();
            }
        }
    }
}