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

    /**
     * v20.1：面板是否在前台（由 MainActivity.onResume/onPause 维护）。
     *
     * ★★ 关键发现（2026-10-10 实测坐实）：
     *   面板在前台时，band **完全接不到**翻页器的触摸。band 是
     *   `TYPE_APPLICATION_OVERLAY`（mBaseLayer=121000），层号远高于面板
     *   （`BASE_APPLICATION`，mBaseLayer=21000），**但层号高不等于能接到触摸**：
     *   当**同一个 UID** 既持有全屏焦点窗口（面板）又挂着 overlay 时，
     *   系统给 overlay 派发触摸前会先看"这块区域是不是被同 UID 的焦点窗口覆盖"——
     *   面板是全屏**不透明白底**，16px 细带完全落在面板内容里，触摸被面板自己吃掉。
     *
     *   实证（清缓冲后重抓 logcat）：
     *     20:54:20.103 test mode ON (panel foreground)
     *     → 之后只有 su / BT batt / self-heal，**一条 TOUCH 都没有**
     *   面板一销毁立刻恢复：
     *     20:53:35.096 wm_destroy_activity ... finish-imm:idle
     *     20:53:38.618 GEST DOWN x0=534.3896 ...   ✅
     *
     * 解法（v20.1 起）：**加高 band 没用**（band top 恒在 928，加高只会盖住下面的按钮、
     *   而且翻页器触摸也到不了高处）。正解是**让面板自己接** ——
     *   MainActivity 在 `dispatchTouchEvent` 里判别"虚拟设备"触摸，直接喂给
     *   `PageTurnerService.handleTouchFromPanel()`，真人手指照常走正常流程。
     *   band 保持 16px 原样不动，微信读书里的行为**完全不变**。
     */
    static volatile boolean sPanelMode = false;

    // 判定双击的等待窗口：实测间隔 ~140ms，取 220ms 留余量
    static final int DOUBLE_WINDOW_MS = 220;

    // v20：长按阈值。翻页器的短按约 100~200ms，长按会明显超过这个值。
    // 取 600ms：比任何"手抖的短按"都长，又比"刻意长按"短，实测区分明确。
    static final int LONG_PRESS_MS = 600;

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

    /**
     * v20.1：服务实例（同进程，MainActivity 与服务的 UID 都是 10115）。
     * 面板在前台时靠它把 dispatchTouchEvent 收到的翻页器触摸直接喂给 handleTouchFromPanel()。
     */
    static volatile PageTurnerService sInstance = null;

    // ---- v20：面板内的「Page Turner Test」支持 ----
    // 面板上有块测试区，翻页器按键时它自己会变。为了不影响正在读的书，
    // 面板在前台（onResume）时把 sTestMode 置 true → 本服务只记录、不注入手势。
    //
    // 面板要显示的正是这四行（迪迪指定，别的都不要）：
    //   1. Single click  #N  →  Next page   OK
    //   2. Double click  #N  →  Prev page   OK
    //   3. Long press    #N  →  Refresh     OK
    static volatile boolean sTestMode = false;

    // 四类动作各自累计的次数
    static volatile int sCntSingle = 0;
    static volatile int sCntDouble = 0;
    static volatile int sCntLong = 0;

    // 最近一条动作，供面板即时显示（形如 "Single click #3 -> Next page"）
    static volatile String sLastAction = "";
    static volatile long sLastActionAt = 0L;
    // 每次动作都自增，面板靠它判断"有新事件了"
    static volatile int sEventSeq = 0;

    // v19 旧字段，保留兼容（面板已不再用，避免其它地方编译报错）
    static volatile int sTestSingle = 0;
    static volatile int sTestDouble = 0;
    static volatile String sHint = "";
    static volatile long sHintAt = 0L;

    int tapCount = 0;
    long lastDispatchMs = 0;
    long lastDismissMs = 0;

    // v20 诊断用：最近一次翻页器手势的特征
    long gestDownMs = 0;
    float gestX0 = 0, gestY0 = 0, gestX1 = 0;
    int gestMoves = 0;

    // 微信读书的包名；更新弹窗是 QMUI 原生 Dialog，无障碍能读到文本
    static final String WEREAD_PKG = "com.tencent.weread.eink";
    static final String UPDATE_LATER = "稍后更新";

    @Override
    public void onServiceConnected() {
        sConnected = true;
        sConnectedAt = System.currentTimeMillis();
        sInstance = this;
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

    /**
     * v20.5：面板在前台时，由 MainActivity.dispatchTouchEvent() 把触摸喂进来，判断
     * **这一下是不是翻页器发的**。
     *
     * ⚠️⚠️ v20.1 的严重错误（迪迪实测报的：面板里所有按钮都点不动、点哪都计数）：
     *   当时的判据是 `name.contains("Virtual") || deviceId < 0` —— **完全错**。
     *   真相是：**这台设备的触摸屏本身就上报为 `dev=[Virtual]`、`deviceId=-1`**，
     *   所有触摸（包括手指）都走虚拟设备通道。于是我的判别把**手指也当成翻页器**，
     *   在 dispatchTouchEvent 里 return true 全吃掉 → 按钮全废、点哪都 +1。
     *   而"翻页器上报为 Virtual"这个当初的结论，是被 band 里的局部坐标（localY=7.06）
     *   误导得出的，**从来没有独立验证过**。
     *
     * ⭐ 真正唯一的可靠判据是**落点 Y**：
     *   翻页器的触摸恒定落在 y≈935 那一条带上（这正是 band 存在的理由），
     *   而手指点到哪就是哪。所以只接受 Y 落在 [PANEL_BAND_LO, PANEL_BAND_HI] 内的事件。
     *
     *   ⚠️ 而这个判定带**必须落在"面板里没有可点击控件"的那一段**，否则手指点按钮
     *   会被误判成翻页器。v20.4 面板实测布局：
     *     Fix 364~472 / WeRead 490~598 / StopSSH 616~724 /
     *     测试区 750~986 / 小按钮行 1012~1098 / footer 1124~1181
     *   → **920~1008 是安全的**（测试区底 986 之后、小按钮行 1012 之前）。取 920~1004 留余量。
     *
     * @return true = 这条事件判定为翻页器发、已被消费（面板不要再往下传）
     */
    static final int PANEL_BAND_LO = BAND_TOP - 8;    // 920
    static final int PANEL_BAND_HI = BAND_TOP + 76;   // 1004

    boolean handleTouchFromPanel(MotionEvent ev) {
        int act = ev.getActionMasked();

        // DOWN 时先看落点 Y：不在翻页器触摸带内 → 一律当手指，放行
        if (act == MotionEvent.ACTION_DOWN) {
            float y = ev.getY();
            panelDownInBand = (y >= PANEL_BAND_LO && y <= PANEL_BAND_HI);
            if (!panelDownInBand) {
                Log.i(TAG, "panel touch: finger (y=" + y + ") -> pass through");
                return false;
            }
            Log.i(TAG, "panel touch: in band (y=" + y + ") -> track as pager");
        }

        if (!panelDownInBand) {
            return false;
        }

        handleTouch(ev);
        return true;
    }

    /** 面板上前一次 DOWN 是否落在翻页器触摸带内 */
    boolean panelDownInBand = false;

    String devName(MotionEvent ev) {
        InputDevice d = ev.getDevice();
        return d == null ? "<null>" : String.valueOf(d.getName());
    }

    void handleTouch(MotionEvent ev) {
        String name = devName(ev);
        int act = ev.getActionMasked();

        // v20 诊断：翻页器是靠 (起点 x, 时长, 是否两下) 区分动作的，
        // 所以把 DOWN 起点、MOVE 轨迹、UP 终点/时长全部记下来。
        // v20.5：不再用"设备名 Virtual"判虚拟设备（那是错的，触摸屏自己就叫 Virtual），
        //        改由来源决定 —— band 收到的都是翻页器；面板喂进来的已由 handleTouchFromPanel 筛过。
        if (act == MotionEvent.ACTION_DOWN) {
            gestDownMs = System.currentTimeMillis();
            gestX0 = ev.getX();
            gestY0 = ev.getY();
            gestX1 = ev.getX();
            gestMoves = 0;
            Log.i(TAG, "GEST DOWN x0=" + gestX0 + " y0=" + gestY0);
        } else if (act == MotionEvent.ACTION_MOVE) {
            gestX1 = ev.getX();
            gestMoves++;
        } else if (act == MotionEvent.ACTION_UP) {
            long ms = System.currentTimeMillis() - gestDownMs;
            Log.i(TAG, "GEST UP   x0=" + gestX0 + " x1=" + gestX1
                    + " moves=" + gestMoves + " durMs=" + ms
                    + " dx=" + (gestX1 - gestX0));
        }

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

        // v20.5：能走到这里的触摸，要么来自 band（那条带只有翻页器会去），
        //        要么已被 handleTouchFromPanel 的 Y 判定筛过 → 直接当翻页器处理。
        //        （旧代码用 name.contains("Virtual") 判，而触摸屏本身就叫 Virtual，
        //          会导致真手指也被当翻页器 —— 已删除该判据。）
        onPageTurnerTap(ev);
    }

    /**
     * v20：翻页器手势 → 动作。三类，顺序是「先判长按，再判单击/双击」：
     *
     *   · 长按（按住 ≥ LONG_PRESS_MS）  → 刷新屏幕
     *   · 短按 1 下                     → 下一页
     *   · 短按 2 下（间隔 ≤ 220ms）     → 上一页
     *
     * ⚠️ v20 修掉一个老 bug：老代码每次都 `handler.removeCallbacksAndMessages(null)`，
     *    于是"连按几下"时，前面几次还没执行的动作会被后面的点击**取消掉** ——
     *    表现为"按了没反应"。现在长按走独立分支（不挂定时器），
     *    且只有「确实要走双击判定」时才取消上一次的待执行动作。
     */
    void onPageTurnerTap(MotionEvent ev) {
        long durMs = System.currentTimeMillis() - gestDownMs;
        float dx = Math.abs(gestX1 - gestX0);

        tapCount++;
        final int count = tapCount;
        Log.i(TAG, "ATG tap #" + count + " at x=" + ev.getX() + " y=" + ev.getY()
                + " durMs=" + durMs + " dx=" + dx);

        // ---- ① 长按：按住时间超过阈值 → 刷新（不进单击/双击定时器）----
        if (durMs >= LONG_PRESS_MS) {
            tapCount = 0;
            handler.removeCallbacksAndMessages(null);
            Log.i(TAG, "=> LONG PRESS : force refresh");
            if (sTestMode) {
                sCntLong++;
                sLastAction = "Long press #" + sCntLong + " -> Refresh";
                sLastActionAt = System.currentTimeMillis();
                sEventSeq++;
            } else {
                sendBroadcast(new Intent(REFRESH_ACTION));
            }
            return;
        }

        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (count >= 2) {
                    Log.i(TAG, "=> DOUBLE : prev page (swipe right)");
                    if (sTestMode) {
                        sCntDouble++;
                        sLastAction = "Double click #" + sCntDouble + " -> Prev page";
                        sLastActionAt = System.currentTimeMillis();
                        sEventSeq++;
                        sTestDouble++;
                    } else {
                        swipe(300, 1100, 1200);
                    }
                } else {
                    Log.i(TAG, "=> SINGLE : next page (swipe left)");
                    if (sTestMode) {
                        sCntSingle++;
                        sLastAction = "Single click #" + sCntSingle + " -> Next page";
                        sLastActionAt = System.currentTimeMillis();
                        sEventSeq++;
                        sTestSingle++;
                    } else {
                        swipe(1100, 300, 1200);
                    }
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
            // v20.1：面板的测试区也要能看到「长按」这一行 ——
            //   实测翻页器的长按发的**不是触摸长按，而是蓝牙音量键**（KEYCODE_VOLUME_DOWN），
            //   所以它走的是这条 onKeyEvent 快路，跟 handleTouch 里的长按分支是两回事。
            //   面板里如果只走触摸那条路，第三行永远是 `--`，没法验证。
            //   这里补一手：测试模式下一并计数（照旧全刷，行为不变）。
            if (sTestMode) {
                sCntLong++;
                sLastAction = "Long press #" + sCntLong + " -> Refresh";
                sLastActionAt = System.currentTimeMillis();
                sEventSeq++;
            }
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
        sInstance = null;
        try {
            if (band != null) {
                wm.removeView(band);
            }
        } catch (Throwable t) {
            Log.e(TAG, "remove band: " + t);
        }
    }
}
