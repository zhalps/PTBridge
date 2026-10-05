package com.didi.pageturner;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * su 调用助手。
 *
 * 血泪教训（2026-10-04）：
 *  1) 本机 su **不在 PATH 里**（PATH=/sbin:/system/sbin:/system/bin:/system/xbin，
 *     而 su 只存在于 /system_ext/bin/su 与 /debug_ramdisk/su）→ 原代码用 "su" 相对名
 *     调用，在部分进程环境里直接 IOException。这里改为按绝对路径逐个探测。
 *  2) su 若未被 Magisk 授权，magiskd 会弹窗等用户点"允许"，
 *     进程会一直阻塞 —— 所以**必须带超时**，且绝不能在主线程调用。
 *  3) 关闭子进程 stdin，避免 magiskd 反过来等待我们输入。
 */
public class Su {

    private static final String TAG = "PTBridge";

    private static final String[] CANDIDATES = {
            "/system_ext/bin/su",
            "/debug_ramdisk/su",
            "/system/bin/su",
            "/system/xbin/su",
            "su"
    };

    private static String sPath = null;
    private static Boolean sAvailable = null;

    /** root 是否可用（已授权）。结果缓存，避免每次都起一个 su 进程。 */
    public static boolean available() {
        if (sAvailable == null) {
            sAvailable = run("id", 6000).contains("uid=0");
        }
        return sAvailable.booleanValue();
    }

    public static synchronized String path() {
        if (sPath != null) {
            return sPath;
        }
        for (int i = 0; i < CANDIDATES.length; i++) {
            if ("su".equals(CANDIDATES[i])) {
                break;
            }
            if (new java.io.File(CANDIDATES[i]).exists()) {
                sPath = CANDIDATES[i];
                Log.i(TAG, "su path = " + sPath);
                return sPath;
            }
        }
        sPath = "su";
        Log.w(TAG, "su path fallback to PATH lookup");
        return sPath;
    }

    /**
     * 以 root 执行命令。**调用方必须在子线程调用。**
     *
     * @return 命令的 stdout（超时或失败返回已收到的部分 / 空串）
     */
    public static String run(String cmd, long timeoutMs) {
        Process p = null;
        final StringBuilder sb = new StringBuilder();
        try {
            p = Runtime.getRuntime().exec(new String[]{path(), "-c", cmd});
            try {
                p.getOutputStream().close();
            } catch (Throwable ignored) {
            }
            drain(p.getInputStream(), sb);
            drain(p.getErrorStream(), sb);
            boolean done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                Log.e(TAG, "su TIMEOUT(" + timeoutMs + "ms): " + cmd);
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
                return sb.toString();
            }
            Log.i(TAG, "su ok exit=" + p.exitValue() + " :: " + cmd);
            return sb.toString();
        } catch (Throwable t) {
            Log.e(TAG, "su FAILED :: " + cmd + " :: " + t);
            return sb.toString();
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void drain(final InputStream in, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (sb) {
                            sb.append(line).append('\n');
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
