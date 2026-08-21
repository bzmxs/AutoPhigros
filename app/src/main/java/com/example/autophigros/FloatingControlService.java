package com.example.autophigros;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;

public class FloatingControlService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private boolean isRunning = false;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("resultCode")) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = (Intent) intent.getParcelableExtra("data");
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                initScreenCapture(resultCode, data);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_control, null);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 200;

        windowManager.addView(floatingView, params);

        Button btnStart = floatingView.findViewById(R.id.btn_start);
        Button btnStop = floatingView.findViewById(R.id.btn_stop);

        btnStart.setOnClickListener(v -> {
            if (!isRunning) {
                startAutoPlay();
            } else {
                Toast.makeText(FloatingControlService.this, "已在运行中", Toast.LENGTH_SHORT).show();
            }
        });

        btnStop.setOnClickListener(v -> {
            if (isRunning) {
                stopAutoPlay();
            } else {
                Toast.makeText(FloatingControlService.this, "未在运行", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void initScreenCapture(int resultCode, Intent data) {
        // 启动前台服务（Android 10+ 必须）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification());
        }

        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null && isRunning) {
                processImage(image);
                image.close();
            }
        }, null);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
        );
        Toast.makeText(this, "录屏已启动，分辨率 " + screenWidth + "x" + screenHeight, Toast.LENGTH_SHORT).show();
    }

    private Notification createNotification() {
        String channelId = "screen_capture_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "录屏服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }

        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("自动打歌工具")
                .setContentText("正在运行录屏服务...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void processImage(Image image) {
        Bitmap bitmap = imageToBitmap(image);
        if (bitmap == null) return;

        int centerY = bitmap.getHeight() / 2 + 100; // 判定线位置（可调）
        int startX = bitmap.getWidth() / 4;
        int endX = bitmap.getWidth() * 3 / 4;

        for (int x = startX; x < endX; x += 10) {
            int pixel = bitmap.getPixel(x, centerY);
            int red = (pixel >> 16) & 0xFF;
            int green = (pixel >> 8) & 0xFF;
            int blue = pixel & 0xFF;

            // 检测蓝色音符 (B > 150, R < 80, G < 80)
            if (blue > 150 && red < 80 && green < 80) {
                AutoClickService.performClick(x, centerY);
                break;
            }
            // 检测红色音符 (R > 150, B < 80, G < 80)
            if (red > 150 && blue < 80 && green < 80) {
                AutoClickService.performClick(x, centerY);
                break;
            }
        }
        bitmap.recycle();
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        Image.Plane plane = planes[0];
        int width = image.getWidth();
        int height = image.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        java.nio.ByteBuffer buffer = plane.getBuffer();
        bitmap.copyPixelsFromBuffer(buffer);
        return bitmap;
    }

    private void startAutoPlay() {
        if (mediaProjection == null) {
            Toast.makeText(this, "请先授权录屏！", Toast.LENGTH_SHORT).show();
            return;
        }
        isRunning = true;
        Toast.makeText(this, "开始自动识别并打歌！", Toast.LENGTH_SHORT).show();
    }

    private void stopAutoPlay() {
        isRunning = false;
        Toast.makeText(this, "已停止识别", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (imageReader != null) imageReader.close();
        if (floatingView != null) windowManager.removeView(floatingView);
        stopForeground(true);
    }
}