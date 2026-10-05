package com.didi.pageturner;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

public class PageTurnerService extends AccessibilityService {

    static final String TAG = "PTBridge";
    static final String REFRESH_ACTION = "android.eink.force.refresh";

    // 实测：翻页器所有触摸的 Y 恒为 935.06（1404x1872 屏的正中），一个像素都不差
    // 短按 = 从 x534 右滑到 x1404；双击 = 在 x843 点两下，间隔 134~139ms
    // → 只要一条包含 y=935 的极窄横线即可，屏幕其余部分完全不受影响
    static final int BAND_TOP = 928;
    static final int BAND_H = 16;

    // 判定双击的等待窗口：实测间隔 ~140ms，取 220ms 留余量
    static final int DOUBLE_WINDOW_MS = 220;

    // 自己注入手势后的静默期：防止注入的事件又被自己的窄带接住，形成回环
    static final int SELF_QUIET_MS = 400;

    WindowManager wm;
    View band;
    Handler handler;

    /**
     * v17：本服务是否真的被系统绑上了。
     *
     * 为什么需要：无障碍服务一旦被杀 / 崩，AMS 会把它记进「Crashed services」黑名单，
     * 之后**重新绑定时直接跳过它**（光把 accessibility_enabled 置 1 没用）。
     * 症状就是：翻页器蓝牙连着，但按键映射全没了，退化成翻页器的原始手势。
     * 修法只有一条：把服务从 enabled_accessibility_services 里**摘掉再放回**。
     *
     * MainActivity 与本服务同进程，所以一个静态标志就能如实反映绑定状态。
     */
    static volatile boolean sConnected = false;
    static volatile long sConnectedAt = 0;

    int tapCount = 0;
    long lastDispatchMs = 0;
    long lastDismissMs = 0;

    // 微信读书的包名；更新弹窗是 QMUI 原生 Dialog，无障碍能读到文本
    static final String WEREAD_PKG = "com.tencent.weread.eink";
    static final String UPDATE_LATER = "稍后更新";

    @Override
    public void onServiceConnected() {
        sConnected = true;
        sConnectedAt = System.currentTimeMillis();
        Log.i(TAG, "=== service connected ===");
        handler = new Handler(Looper.getMainLooper());
        try {
            addBand();
            Log.i(TAG, "band added OK");
        } catch (Throwable t) {
            Log.e(TAG, "band FAILED: " + t);
        }
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent e) {
        try {
            ensureBluetoothOn();
        } catch (Throwable t) {
            Log.e(TAG, "ensureBt err: " + t);
        }
        try {
            autoDismissUpdateDialog(e);
        } catch (Throwable t) {
            Log.e(TAG, "dismiss err: " + t);
        }
    }

    // v10: 事件驱动自动开蓝牙。解锁/切窗口/开微信读书都会产生无障碍事件，
    // 30 秒节流查一次蓝牙总开关，发现被关就自动打开 —— 不新增任何轮询进程。
    static long sLastBtCheckMs = 0;

    void ensureBluetoothOn() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastBtCheckMs < 30000) {
            return;
        }
        sLastBtCheckMs = now;
        android.bluetooth.BluetoothAdapter ad =
                android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (ad == null || ad.isEnabled()) {
            return;
        }
        boolean ok = false;
        try { ok = ad.enable(); } catch (Throwable t) {}
        if (!ok) {
            // v12: 改走 Su 助手（绝对路径 + 超时）。原来用相对名 "su"，
            // 而本机 su 只在 /system_ext/bin 与 /debug_ramdisk，PATH 里没有 → 必然失败
            new Thread(new Runnable() {
                public void run() {
                    Su.run("svc bluetooth enable", 8000);
                }
            }).start();
        }
        Log.i(TAG, "BT was off -> auto enable (adapter=" + ok + ")");
    }

    /**
     * 微信读书的更新弹窗（"2.1.1更新 / 稍后更新 / 立即更新"）是原生 QMUI Dialog，
     * 无障碍能读到文本，所以可以在它一出现就自动替你点「稍后更新」。
     */
    void autoDismissUpdateDialog(android.view.accessibility.AccessibilityEvent e) {
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !WEREAD_PKG.equals(pkg.toString())) {
            return;
        }
        if (System.currentTimeMillis() - lastDismissMs < 1500) {
            return;
        }

        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }

        java.util.List<android.view.accessibility.AccessibilityNodeInfo> hits =
                root.findAccessibilityNodeInfosByText(UPDATE_LATER);
        if (hits == null || hits.isEmpty()) {
            return;
        }

        android.view.accessibility.AccessibilityNodeInfo n = hits.get(0);
        // 文本节点本身可能不可点，往上找可点击的父节点（Button）
        while (n != null && !n.isClickable()) {
            android.view.accessibility.AccessibilityNodeInfo p = n.getParent();
            if (p == null) {
                break;
            }
            n = p;
        }
        boolean ok = n != null && n.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        lastDismissMs = System.currentTimeMillis();
        Log.i(TAG, ">>> auto-dismissed weread update dialog (" + UPDATE_LATER + ") ok=" + ok);
    }

    @Override
    public void onInterrupt() {
    }

    void addBand() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int w = dm.widthPixels;

        LayoutParams p = new LayoutParams();
        p.type = LayoutParams.TYPE_APPLICATION_OVERLAY;
        p.flags = LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_NOT_TOUCH_MODAL
                | LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        p.format = PixelFormat.TRANSLUCENT;
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = 0;
        p.y = BAND_TOP;
        p.width = w;
        p.height = BAND_H;

        band = new View(this);
        // 完全透明：浮窗靠"窗口边界"接收触摸，不靠像素，所以全透明照样能接住，肉眼完全无感
        band.setBackgroundColor(0x00000000);
        band.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                handleTouch(ev);
                return true;
            }
        });
        wm.addView(band, p);
        Log.i(TAG, "band rect = x:0 y:" + BAND_TOP + " w:" + w + " h:" + BAND_H);
    }

    String devName(MotionEvent ev) {
        InputDevice d = ev.getDevice();
        return d == null ? "<null>" : String.valueOf(d.getName());
    }

    void handleTouch(MotionEvent ev) {
        String name = devName(ev);
        int act = ev.getActionMasked();

        // 诊断：无条件记录每一个触摸，看清到底有没有进来、是谁发的
        Log.i(TAG, "TOUCH act=" + MotionEvent.actionToString(act)
                + " rawX=" + ev.getRawX() + " rawY=" + ev.getRawY()
                + " localX=" + ev.getX() + " localY=" + ev.getY()
                + " devId=" + ev.getDeviceId() + " dev=[" + name + "]"
                + " src=" + Integer.toHexString(ev.getSource()));

        // 自己刚注入过手势 → 静默，防回环
        if (System.currentTimeMillis() - lastDispatchMs < SELF_QUIET_MS) {
            Log.i(TAG, "quiet: ignore (self-injected echo)");
            return;
        }

        if (act != MotionEvent.ACTION_UP) {
            return;
        }

        // ★ 关键：翻页器的触摸上报设备名是 "Virtual"、deviceId=-1，不是 "ATG-SJL"
        //   （它的两个节点 descriptor 相同，被系统合并后走虚拟输入设备通道）
        //   真触摸屏是 "goodix_ts"，设备 id 正常
        boolean isPageTurner = (name != null && name.length() > 0
                && (name.contains("Virtual") || ev.getDeviceId() < 0));

        if (isPageTurner) {
            onPageTurnerTap(ev);
        } else if (name != null && name.contains("goodix")) {
            // 真人手指正好点到这条隐形细线上，原样回放一次，尽量不打扰阅读
            replayTap(ev.getX(), ev.getY(), name);
        } else {
            Log.i(TAG, "ignore unknown dev: " + name);
        }
    }

    void onPageTurnerTap(MotionEvent ev) {
        tapCount++;
        final int count = tapCount;
        Log.i(TAG, "ATG tap #" + count + " at x=" + ev.getX() + " y=" + ev.getY());

        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (count >= 2) {
                    Log.i(TAG, "=> DOUBLE : prev page (swipe right)");
                    swipe(300, 1100, 1200);
                } else {
                    Log.i(TAG, "=> SINGLE : next page (swipe left)");
                    swipe(1100, 300, 1200);
                }
                tapCount = 0;
            }
        }, DOUBLE_WINDOW_MS);
    }

    void replayTap(float x, float y, String name) {
        Log.i(TAG, "replay finger tap from " + name + " at " + x + "," + y);
        float gy = y + BAND_TOP;
        Path p = new Path();
        p.moveTo(x, gy);
        p.lineTo(x, gy);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 40));
        lastDispatchMs = System.currentTimeMillis();
        dispatchGesture(b.build(), null, null);
    }

    void swipe(final int x0, final int x1, final int y) {
        Path p = new Path();
        p.moveTo(x0, y);
        p.lineTo(x1, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        // 实测：微信读书只认 y≈1200、约 130ms 的快速滑动（350ms 太慢无效，y=600 无效）
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 130));
        lastDispatchMs = System.currentTimeMillis();
        boolean ok = dispatchGesture(b.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gd) {
                Log.i(TAG, "gesture COMPLETED " + x0 + "->" + x1 + " @" + y);
            }

            @Override
            public void onCancelled(GestureDescription gd) {
                Log.i(TAG, "gesture CANCELLED " + x0 + "->" + x1 + " @" + y);
            }
        }, null);
        Log.i(TAG, "dispatchGesture returned " + ok);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        InputDevice d = event.getDevice();
        String name = d == null ? "" : String.valueOf(d.getName());
        int code = event.getKeyCode();
        int act = event.getAction();

        Log.i(TAG, "key " + code + " act=" + act + " from=" + name);

        if (name != null && name.contains("ATG")
                && code == KeyEvent.KEYCODE_VOLUME_DOWN
                && act == KeyEvent.ACTION_UP) {
            Log.i(TAG, "=> VOLUME_DOWN from ATG : FULL REFRESH broadcast");
            Intent i = new Intent(REFRESH_ACTION);
            sendBroadcast(i);
            return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sConnected = false;
        try {
            if (band != null) {
                wm.removeView(band);
            }
        } catch (Throwable t) {
            Log.e(TAG, "remove band: " + t);
        }
    }
}
