package com.didi.pageturner;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RadioWakeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent i) {
        String a = i.getAction();
        Log.i("PTBridge", "RadioWake: " + a);
        if (a == null) return;

        // 解锁瞬间 / 开机完成 → 恢复蓝牙 + 确保 SSH 服务在跑 + 刷新下拉栏电量通知
        if (Intent.ACTION_USER_PRESENT.equals(a) || Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            restoreBluetooth();
            wakeSshd(ctx);
            BtBattery.read(ctx);
        }
    }

    private void restoreBluetooth() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) {
                Log.e("PTBridge", "RadioWake: no bt adapter");
                return;
            }
            if (!ad.isEnabled()) {
                boolean ok = ad.enable();
                Log.i("PTBridge", "RadioWake: BT enable() -> " + ok);
                if (!ok) {
                    // 标准 API 失败（可能被 rfkill 挡），走 root 兜底
                    execSu("svc bluetooth enable");
                }
            } else {
                Log.i("PTBridge", "RadioWake: BT already on");
            }
        } catch (Throwable t) {
            Log.e("PTBridge", "RadioWake bt failed: " + t);
            execSu("svc bluetooth enable");
        }
    }

    // SimpleSSHD 自带 StartReceiver，广播一下就会把 dropbear 拉起来
    private void wakeSshd(Context ctx) {
        try {
            Intent s = new Intent("org.galexander.sshd.START");
            s.setClassName("org.galexander.sshd", "org.galexander.sshd.StartReceiver");
            ctx.sendBroadcast(s);
            Log.i("PTBridge", "RadioWake: sshd START broadcast sent");
        } catch (Throwable t) {
            Log.e("PTBridge", "RadioWake sshd failed: " + t);
        }
    }

    // v12: 走 Su 助手（绝对路径 + 超时），并放到子线程 ——
    // onReceive 跑在主线程，原来的 p.waitFor() 一旦 su 弹窗就会把广播接收器卡死
    private void execSu(final String cmd) {
        new Thread(new Runnable() {
            public void run() {
                Su.run(cmd, 8000);
            }
        }).start();
    }
}
