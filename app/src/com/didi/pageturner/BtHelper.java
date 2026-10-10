package com.didi.pageturner;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 蓝牙恢复助手。
 *
 * 为什么需要它（2026-10-04 定位）：
 *   多看桌面的「清理加速」（下拉栏，调用方记录为 com.android.systemui）
 *   会把蓝牙总开关一并关掉。10-02~10-03 的 dumpsys bluetooth_manager 里
 *   有 8 条 `Disabled due to APPLICATION_REQUEST by com.android.systemui`。
 *   所以"点 PTBridge 启动不了蓝牙"的前提是：蓝牙真的被桌面关掉了。
 *
 *   剩下的问题在原实现：
 *     a) 开蓝牙的代码和「写无障碍设置」挤在同一个 try 里 —— 后者一抛异常，
 *        开蓝牙就被整段跳过；
 *     b) 兜底用 Runtime.exec("su")，而本机 su 不在 PATH；
 *     c) su 未授权时 magiskd 会弹窗等待，主线程 p.waitFor() 直接卡死。
 *   本类负责把 a/b/c 全部绕开：先 API、后 root 绝对路径、全程带超时。
 */
public class BtHelper {

    static final String TAG = "PTBridge";

    /** BluetoothProfile.HID_HOST 是 @hide 常量，只能写字面量 */
    static final int HID_HOST = 4;
    static final int STATE_CONNECTED = 2;

    static final String PAGER_NAME_PART = "ATG";

    // v19 起不再需要「二次点击窗口」：
    //   按钮本身就是「我确定现在不能翻页了」的语义，一次点击直接强制重建。
    //   原来的 lastFixPressAt / RETRY_WINDOW_MS(60s) 已删除。

    public static BluetoothAdapter adapter() {
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isOn() {
        BluetoothAdapter ad = adapter();
        return ad != null && ad.isEnabled();
    }

    /** 配对的翻页器（名字含 ATG） */
    public static BluetoothDevice pager() {
        BluetoothAdapter ad = adapter();
        if (ad == null) {
            return null;
        }
        try {
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String n;
                    try {
                        n = d.getName();
                    } catch (Throwable t) {
                        continue;
                    }
                    if (n != null && n.contains(PAGER_NAME_PART)) {
                        return d;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "pager(): " + t);
        }
        return null;
    }

    /** 打开蓝牙总开关：先标准 API，失败/超时再降级 root。带超时，绝不无限等。 */
    public static boolean turnOn(long waitMs) {
        if (isOn()) {
            return true;
        }
        BluetoothAdapter ad = adapter();
        boolean apiOk = false;
        if (ad != null) {
            try {
                apiOk = ad.enable();
            } catch (Throwable t) {
                Log.e(TAG, "ad.enable: " + t);
            }
        }
        Log.i(TAG, "BT turnOn: adapter.enable() -> " + apiOk);
        if (waitOn(waitMs)) {
            return true;
        }

        Log.i(TAG, "BT turnOn: api 未生效 -> root svc bluetooth enable");
        Su.run("svc bluetooth enable", 8000);
        return waitOn(waitMs);
    }

    static boolean waitOn(long ms) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) {
            if (isOn()) {
                return true;
            }
            sleep(250);
        }
        return isOn();
    }

    /**
     * 恢复蓝牙连接：确保总开关 ON → 请求系统 HID 模块重连翻页器 → 还不行就重启蓝牙。
     *
     * @return 一行结果（英文），直接显示在面板上
     */
    public static String reconnect(Context ctx) {
        boolean on = turnOn(6000);
        if (!on) {
            return "Failed to enable Bluetooth (open BT Settings manually)";
        }

        BluetoothDevice dev = pager();
        if (dev == null) {
            return "Bluetooth on · pager not paired";
        }

        int st = hidState(ctx, dev);

        // ---- v19：一次点击就强制重建 ----
        // v18.2 曾用"60 秒内点第二次才强制重建"来避免误伤，
        // 但迪迪的实际使用反馈是「经常断掉，都是需要强制修复的」——
        // 那个窗口只带来困惑（"为什么点了没用"），没有带来保护。
        // 现在：只要按钮被按下，就直接走强制重建（关总开关 → 开 → 等翻页器回来）。
        // 代价是每次点 Fix 蓝牙都会短暂断开，所以只在"真的不能翻页"时才按。
        Log.i(TAG, "BT reconnect: hid=" + st + " -> 强制重建链路（v19 单次点击即强制）");
        return forceRestart(ctx);
    }

    /**
     * 强制重建蓝牙链路：关总开关 → 等一下 → 再开 → 等翻页器回来。
     * 这是唯一能把"假在线"那层陈旧状态彻底清掉的办法（HCI 层的 ACL 会重新建立）。
     */
    static String forceRestart(Context ctx) {
        Su.run("svc bluetooth disable", 8000);
        sleep(1200);
        Su.run("svc bluetooth enable", 8000);
        if (!waitOn(7000)) {
            return "Bluetooth restart failed (open BT Settings manually)";
        }
        BluetoothDevice d2 = pager();
        if (d2 != null && hidConnected(ctx, d2, 6000)) {
            return "Bluetooth restarted · pager connected";
        }
        return "Bluetooth on · pager offline (press a pager key to wake it)";
    }

    // ---------------- HID profile proxy ----------------

    /**
     * 打开 HID profile proxy。
     *
     * v18 修了两处（原来会报 `ServiceConnectionLeaked` / 泄漏 proxy）：
     *  1) 用 **Application context** 去 bind —— 传 Activity 的话，面板一关，
     *     这条还没回来的连接就成了"泄漏的 ServiceConnection"（StrictMode 会报）。
     *  2) 4 秒超时后返回 null 时打个 `abandoned` 标记：万一回调**迟到**，
     *     就在回调里就地 closeProfileProxy，而不是把它丢掉。
     */
    private static BluetoothProfile openProxy(Context ctxIn) {
        final BluetoothAdapter ad = adapter();
        if (ad == null) {
            return null;
        }
        // 用 Application context bind，避免 Activity 销毁时 ServiceConnectionLeaked
        Context ctx = ctxIn;
        if (ctxIn != null && ctxIn.getApplicationContext() != null) {
            ctx = ctxIn.getApplicationContext();
        }
        final BluetoothProfile[] holder = new BluetoothProfile[1];
        final CountDownLatch latch = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean abandoned =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (abandoned.get()) {
                    // 调用方已经超时放弃了 —— 就地关掉，否则这条 proxy 永远泄漏
                    try {
                        ad.closeProfileProxy(profile, proxy);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                holder[0] = proxy;
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(int profile) {
            }
        };
        try {
            boolean ok = ad.getProfileProxy(ctx, listener, HID_HOST);
            if (!ok) {
                return null;
            }
            if (!latch.await(4, TimeUnit.SECONDS)) {
                abandoned.set(true);
                return null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "openProxy: " + t);
            return null;
        }
        return holder[0];
    }

    private static void closeProxy(BluetoothProfile p) {
        BluetoothAdapter ad = adapter();
        if (ad == null || p == null) {
            return;
        }
        try {
            ad.closeProfileProxy(HID_HOST, p);
        } catch (Throwable ignored) {
        }
    }

    static int hidState(Context ctx, BluetoothDevice dev) {
        BluetoothProfile hid = openProxy(ctx);
        if (hid == null) {
            return -1;
        }
        try {
            return hid.getConnectionState(dev);
        } catch (Throwable t) {
            return -1;
        } finally {
            closeProxy(hid);
        }
    }

    private static boolean hidConnected(Context ctx, BluetoothDevice dev, long waitMs) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < waitMs) {
            if (hidState(ctx, dev) == STATE_CONNECTED) {
                return true;
            }
            sleep(400);
        }
        return false;
    }

    /** 用系统 HID 模块主动发起重连（反射调用 @hide 的 BluetoothHidHost.connect） */
    static void requestHidConnect(Context ctx, BluetoothDevice dev) {
        BluetoothProfile hid = openProxy(ctx);
        if (hid == null) {
            Log.w(TAG, "requestHidConnect: HID proxy unavailable");
            return;
        }
        try {
            Method m = hid.getClass().getMethod("connect", BluetoothDevice.class);
            Object r = m.invoke(hid, dev);
            Log.i(TAG, "HID connect() -> " + r);
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            Log.w(TAG, "HID connect() 不可用: " + cause);
        } finally {
            closeProxy(hid);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
        }
    }

    /** 给面板用的简短状态文案（英文，e-ink 上一行放得下） */
    public static String shortState(Context ctx) {
        if (!isOn()) {
            return "Bluetooth off";
        }
        BluetoothDevice dev = pager();
        if (dev == null) {
            return "Bluetooth on · pager not paired";
        }
        int st = hidState(ctx, dev);
        if (st == STATE_CONNECTED) {
            return "Bluetooth on · pager online";
        }
        return "Bluetooth on · pager not connected";
    }
}
