package com.example.autophigros;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int OVERLAY_PERMISSION_REQUEST = 100;
    private static final int SCREEN_CAPTURE_REQUEST = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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