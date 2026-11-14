package com.bzmxs.autophigros;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 把崩溃信息写到文件里。
 * <p>
 * 为什么要有这个：这个 App 会跑在别的游戏上面、还有录屏和无障碍在后台，
 * 一旦崩溃，Logcat 又不好弄出来。所以崩溃时自己把堆栈写进 App 私有目录，
 * 下次打开 App 直接弹窗给你看，你截个图我就能定位。
 */
public final class CrashLogger {

    private static final String TAG = "AutoPhigrosCrash";
    private static final String DIR = "crash";

    private CrashLogger() {
    }

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                write(app, t, e);
            } catch (Throwable ignored) {
                // 记录崩溃的过程本身崩溃了就没办法了，别把原来的崩溃掩盖掉
            }
            if (previous != null) {
                previous.uncaughtException(t, e);
            }
        });
    }

    private static synchronized void write(Context app, Thread thread, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date()));
        pw.println("thread: " + (thread == null ? "?" : thread.getName()));
        e.printStackTrace(pw);
        pw.flush();

        File dir = new File(app.getExternalFilesDir(null), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "can not create crash dir");
            return;
        }
        String name = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".txt";
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(sw.toString().getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "write failed", t);
            return;
        }
        Log.e(TAG, "saved: " + f.getAbsolutePath());
    }

    /** 删掉所有崩溃记录（看完、发完截图之后点“清空”） */
    public static void clear(Context context) {
        File dir = new File(context.getExternalFilesDir(null), DIR);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** 最近一次崩溃的内容；没有崩溃过返回 null */    public static String lastCrash(Context context) {
        File dir = new File(context.getExternalFilesDir(null), DIR);
        File[] files = dir.listFiles((d, n) -> n.startsWith("crash_") && n.endsWith(".txt"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        File newest = files[files.length - 1];
        try {
            byte[] buf = new byte[(int) Math.min(newest.length(), 8192)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(newest)) {
                int n = in.read(buf);
                if (n <= 0) return null;
                return "文件：" + newest.getAbsolutePath() + "\n\n"
                        + new String(buf, 0, n, "UTF-8");
            }
        } catch (Exception e) {
            return null;
        }
    }
}
