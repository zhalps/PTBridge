package com.didi.pageturner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Set;
import java.util.UUID;

/**
 * 读翻页器电量，并把结果写进下拉栏通知。
 *
 * v18：
 *  - 全部文案改英文（通知标题 / 状态文本）。
 *  - **删掉 Toast**。原来有一段 `Toast.makeText(...)` 的黑底长条，
 *    但两个调用点都传 showTip=false，属于永不执行的死代码；
 *    而且墨水屏上"面板里的状态行"本来就比弹窗更合适 → 整段移除。
 */
public class BtBattery {

    private static final String CH_ID = "ptbridge_status_v2";
    private static final String CH_NAME = "Pager status";
    private static final String TARGET_NAME = "ATG";
    private static final UUID SVC_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID CHR_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    private static int lastLevel = -1;
    private static BluetoothGatt gatt;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static boolean done;

    /** v12：回调，供 PTBridge 面板把电量和状态直接显示在界面上 */
    public interface Cb {
        void onBatt(String text, int level);
    }

    private static Cb sCb;

    /** 不带回调：只更新下拉栏通知 */
    public static void read(final Context ctx) {
        read(ctx, null);
    }

    public static synchronized void read(final Context ctx, Cb callback) {
        sCb = callback;
        done = false;
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice dev = null;
            Set<BluetoothDevice> bonded = (ad == null) ? null : ad.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String n = d.getName();
                    if (n != null && n.contains(TARGET_NAME)) { dev = d; break; }
                }
            }
            if (dev == null) {
                done(ctx, -1, "Pager: not paired or connected");
                return;
            }
            // v11: 进程重启后从持久化恢复上次电量，避免"电量未知"
            if (lastLevel < 0) {
                try {
                    lastLevel = ctx.getSharedPreferences("ptbridge", Context.MODE_PRIVATE)
                            .getInt("batt", -1);
                } catch (Throwable ignored) {}
            }
            // v11: 删除 v8 的休眠预判 —— HID 每 2~5 分钟有约 0.5 秒断连抖动，
            // 预判撞上抖动窗口会误杀（翻页器明明在线却提示休眠）。改为总是直接尝试
            // GATT：HID 在线时共享同一条链路秒连；真休眠时 8 秒快速失败并回退缓存值。
            int hidState = (ad == null) ? -1 : ad.getProfileConnectionState(3); // 仅用于日志诊断
            Log.i("PTBridge", "BT batt: hidState=" + hidState + " (try gatt anyway)");
            final BluetoothDevice target = dev;
            H.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!done) {
                        done = true;
                        close();
                        done(ctx, lastLevel, lastLevel >= 0
                                ? "Pager battery " + lastLevel + "% (cached · no response)"
                                : "Pager: read timeout — press a pager key and retry");
                    }
                }
            }, 8000);
            target.connectGatt(ctx, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    gatt = g;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i("PTBridge", "BT batt: gatt connected");
                        g.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !done) {
                        done = true;
                        H.removeCallbacksAndMessages(null);
                        close();
                        done(ctx, lastLevel, lastLevel >= 0
                                ? "Pager battery " + lastLevel + "% (cached · link lost)"
                                : "Pager: link lost — press a pager key and retry");
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    BluetoothGattService s = g.getService(SVC_BATTERY);
                    if (s == null || s.getCharacteristic(CHR_LEVEL) == null) {
                        if (!done) {
                            done = true;
                            H.removeCallbacksAndMessages(null);
                            close();
                            done(ctx, -1, "Pager does not report battery");
                        }
                        return;
                    }
                    g.readCharacteristic(s.getCharacteristic(CHR_LEVEL));
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    if (done) return;
                    done = true;
                    H.removeCallbacksAndMessages(null);
                    int v = (c.getValue() != null && c.getValue().length > 0) ? (c.getValue()[0] & 0xff) : -1;
                    Log.i("PTBridge", "BT batt: " + v + "%");
                    close();
                    done(ctx, v, "Pager battery: " + v + "%");
                }
            });
        } catch (Throwable t) {
            Log.e("PTBridge", "BtBattery failed: " + t);
            done(ctx, lastLevel, "Pager battery: read failed");
        }
    }

    private static void close() {
        if (gatt != null) {
            try { gatt.close(); } catch (Throwable ignored) {}
            gatt = null;
        }
    }

    private static void done(Context ctx, int level, String notifText) {
        if (level >= 0) {
            lastLevel = level;
            // v11: 持久化电量，进程被杀/重启后仍能显示最近一次的值
            try {
                ctx.getApplicationContext()
                        .getSharedPreferences("ptbridge", Context.MODE_PRIVATE)
                        .edit().putInt("batt", level).apply();
            } catch (Throwable ignored) {}
        }
        post(ctx, notifText);
        Cb c = sCb;
        if (c != null) {
            try {
                c.onBatt(notifText, level);
            } catch (Throwable ignored) {
            }
        }
        // v18: 这里原本会弹一个黑底 Toast，已移除（见类注释）
    }

    private static void post(Context ctx, String text) {
        Log.i("PTBridge", "notify: " + text);
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH_ID, CH_NAME,
                    NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(ctx, CH_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_power_off)
                    .setContentTitle("Pager status")
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();
            nm.notify(1001, n);
        } catch (Throwable t) {
            Log.e("PTBridge", "notify failed: " + t);
        }
    }
}
