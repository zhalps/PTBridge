package com.didi.pageturner;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * PTBridge 控制面板（v17，2026-10-05）
 *
 * v17：
 *  - 第一个按钮【恢复蓝牙连接】→【修复蓝牙连接】。它现在按"修复"来办事：
 *      ① 修无障碍服务（真正的病根）  ② 再重连蓝牙
 *  - 新增「无障碍绑定自检」：面板一打开就检查 PageTurnerService 有没有真被系统绑上，
 *    没绑上就自动执行修复序列（打开面板即自愈，通常不用再点按钮）
 *
 * 病根（2026-10-05 用日志坐实的）：
 *    无障碍服务被杀 / 崩之后，AMS 会把它记进 **Crashed services** 黑名单，
 *    此后 **re-bind 时直接跳过它**。于是浮窗细带（y=928~944）没加上，
 *    翻页器的原始手势直接漏给微信读书 → 症状就是"蓝牙明明连着，但映射没了：
 *    单击变上一页、长按变下一页"。
 *    ★ 光把 `accessibility_enabled` 置 1 **没用**，必须把服务从
 *      `enabled_accessibility_services` 里**摘掉再放回**，才能清掉黑名单。
 *      而且「摘掉」**必须在无障碍确实开着的时候做**（先写名单 → 再开总开关 →
 *      等真的 enabled=1 → 才摘掉），否则等于白摘。详细对比见 repairAccessibility()。
 *    （实测：摘掉后 Crashed services 从 {PageTurnerService} 变 {}，
 *      放回后 Bound services 从 {} 变 {Service[label=PTBridge...]}）
 *    重启也能修 —— 因为重启会把黑名单清空，这也解释了"重启就好"的现象。
 *  - 另注：`am force-stop com.didi.pageturner`（一键清理的招式）会把
 *    enabled_accessibility_services 直接清成 null + 服务进黑名单，
 *    而且 **每次 `pm install -r` 重装本 App 也会杀掉进程、触发同样的黑名单** ——
 *    所以重装完记得开一次面板让它自愈。
 *
 * v18（2026-10-05）：
 *  - **整个界面改英文**（互联网软件常用词，按钮文案尽量短）：Fix Bluetooth / Restart WeRead /
 *    Start|Stop SSH / BT Settings / Refresh / Close；状态统一 `SSH: ON`、`Gateway: 3.6`。
 *    类注释与日志仍保留中文，方便对照。
 *  - **去掉黑底弹窗**：面板自己的 Toast 在 BtBattery 里，本来就是死代码，已移除；
 *    真正每次点按钮都冒出来的那条黑条是 **Magisk 的 root 授权提示**
 *    （`pkg=com.topjohnwu.magisk`）—— 已把 uid 10115 的 `policies.notification` 置 0 关掉。
 *  - **探测合并**：SSH 状态 + 网关原来要走两次 su，现在合成一次，且不再无条件 `sleep 3`
 *    （只在 state 文件缺失时才等一下）→ 点【Refresh】快很多。
 *
 * v18b（2026-10-05，迪迪反馈「点 Refresh 后那三个大按钮变成纯黑」）：
 *  ⭐ **墨水屏铁律：不要为了"变灰/禁用"去改大控件的 enabled / 文本**。
 *    实测（逐帧采样 17 张截图）三个大按钮的填充色**从没进过黑**：常态 #E9E9E5、
 *    busy 禁用态反而更浅 #F1F1EF。也就是说黑色**不在 framebuffer 里** ——
 *    screencap 拍不到，眼睛却看得到。
 *    真正原因：`setEnabled(false)` 让这三块**大面积填充**外观发生变化 →
 *    墨水屏对这片区域发起局部重绘 → 该区域的刷新波形把整块**闪成黑**，
 *    直到下一次全刷才恢复。小按钮面积小，所以不明显。
 *  处理：
 *    ① `busy()` 不再 `setEnabled(...)`，只挂一个 `sBusy` 重入标志；
 *       点击入口各自 `if (sBusy) return;` —— 外观恒定 = 不重绘 = 不闪黑。
 *       反馈不丢：状态行本来就在显示 "Refreshing… / detecting pager…"。
 *    ② 所有 `setText` 改成**内容真的变了才写**（`setStatus/setResult/setSshState/setNetState`）。
 *       墨水屏上每一次无谓重绘都是一次闪，能不画就不画。
 *
 * v18c（2026-10-05，迪迪反馈「点 Refresh / 修复蓝牙 时按钮还是变黑」）：
 *  ⭐ **控件从 `Button` 换成自己画背景的 `TextView`**。
 *    本机 ROM 对系统 `Button` 的 pressed / disabled 有自己的一套处理
 *    （很可能是 ROM 替换过的 eink 版 Button 样式，或主题给 Button 挂的
 *      ripple / stateListAnimator），在墨水屏上表现为整块区域反色发黑。
 *    换成 TextView、背景与按压态全部自己画，就绕开了 ROM 的控件样式。
 *    实测（按住 Refresh 逐帧采样）：framebuffer 里按下色 = #EFEFEC（浅灰），
 *    **从来不是黑色** —— 再次印证"黑"是面板级现象，不是我们画出来的颜色。
 *
 * v18d（2026-10-05，迪迪：「我希望变色，但不要那么深色」）：
 *  - 按下色拆成两档、全部取浅灰：大按钮 #EDEDEA→#E0E0DB（Δ13）、
 *    小按钮 #FFFFFF→#EFEFEC（Δ16）。既有可见的变色，又远离"深色"。
 *
 * v16：
 *  - 打开面板时**自动确保「按 SSID 切网关/DNS」的守护进程在跑**，并回读当前生效的网关
 *    显示在左上角（`SSH: ON | Gateway: 3.6`）。守护本身按 SSID 决定用哪个旁路由：
 *      HomeWiFi -> 192.168.3.2 ；OfficeWiFi -> 192.168.3.3 ；其它 SSID -> 撤规则、走直连
 *    为什么需要：守护由 /data/adb/service.d 的监管拉起，正常情况下不会被清理掉
 *    （oom_score_adj = -1000，和 magiskd 同级）；但万一 service.d 没执行、或将来被
 *    清掉，这里就是一个人肉可控的兜底入口 —— 点开面板即恢复。
 *    两条启动命令都带单实例守卫，重复执行是幂等的。
 *
 * v15：
 *  - 左上角加 SSH 状态栏：`SSH：开` / `SSH：关`（开=深黑、关=浅灰，扫一眼就知道）
 *  - SSH 按钮文案跟着状态走：在线显示「关闭 SSH」，离线显示「启动 SSH」
 *    状态条与按钮由 `setSshState()` 一处统一更新；随面板打开、【刷新】、SSH 动作三处自动刷新
 *
 * v14：
 *  - 【启动 SSH】→【启动/关闭 SSH】：先探 2222 端口，在线就关、离线就开（开关式）
 *    关闭走 SimpleSSHD 官方的 org.galexander.sshd.STOP 广播；广播不管用才 `pkill -x sshd`
 *    兜底 —— 刻意**不用** force-stop，免得把 BootReceiver 开机自启一起废掉
 *
 * v13：
 *  - 底部按钮行加【刷新】：原地重查蓝牙 / 翻页器 / 电量，不用退出面板再点图标
 *  - 底部三个按钮并列：蓝牙设置 | 刷新 | 关闭
 *
 * 变化：
 *  - 从 Theme.NoDisplay「点一下就消失」改为一个真正的面板：点图标 → 自愈 + 读电量 + 三个按钮
 *  - 【修复蓝牙连接】v17 起：先修无障碍服务（清崩溃黑名单）→ 再确保总开关 ON →
 *    请系统 HID 模块重连翻页器 → 还不行就重启蓝牙
 *  - 【重启微信读书】微信读书吃下去的内存它自己吐不掉，直接 force-stop 再拉回阅读页
 *  - 【启动/关闭 SSH】探 2222 端口 → 在线发 STOP 广播 / 离线发 START 广播，并回读确认
 *  - 自愈（写无障碍开关）与开蓝牙**拆成两个独立的 try**：以前挤在一起，
 *    前者一抛异常，开蓝牙就被整段跳过 —— 这是"点图标开不了蓝牙"的元凶之一
 *
 * 界面刻意做得很素：纯代码布局、无图片、无动画，白底黑字，e-ink 上最清晰。
 */
public class MainActivity extends Activity {

    static final String TAG = "PTBridge";
    static final String VERSION = "v18.1";

    static final String SVC = "com.didi.pageturner/.PageTurnerService";
    static final String WEREAD = "com.tencent.weread.eink";
    static final String WEREAD_ACT = "com.tencent.weread.eink/com.tencent.weread.ReaderFragmentActivity";
    static final String WEREAD_LAUNCH = "com.tencent.weread.eink/com.tencent.weread.LauncherActivity";
    static final String SSH_PKG = "org.galexander.sshd";
    static final String SSH_START = "org.galexander.sshd.START";
    static final String SSH_START_RCVR = "org.galexander.sshd.StartReceiver";
    static final String SSH_STOP = "org.galexander.sshd.STOP";
    static final String SSH_STOP_RCVR = "org.galexander.sshd.StopReceiver";
    static final String REFRESH_ACTION = "android.eink.force.refresh";

    // v16：按 SSID 自动切网关的守护（脚本在设备上，由 service.d 监管拉起）
    static final String NET_SUP = "/data/adb/service.d/duo2-net.sh";
    static final String NET_DAEMON = "/data/adb/duo2-net.sh";
    static final String NET_STATE = "/data/adb/duo2-net.state";   // 内容形如 "HomeWiFi 6"

    // 网关显示前缀：state 文件里只存最后一段（如 "6"），这里补全成 "3.6" 显示。
    // ⚠️ 网段不一样要改这里 —— 例如网段是 192.168.10.x 就写成 "10."
    static final String GW_PREFIX = "3.";

    // 灰度配色：e-ink 上没有色彩抖动，最耐看
    static final int BG = 0xFFFFFFFF;
    static final int INK = 0xFF111111;
    static final int INK2 = 0xFF3C3C3C;
    static final int INK3 = 0xFF8C8C8C;
    static final int LINE = 0xFFE2E2DE;
    static final int BTN_FILL = 0xFFEDEDEA;
    // v18d：按下态。墨水屏上「大面积填充变色」本身就会触发该区域的局部重绘，
    //   刷新波形容易把整块反色 → 看起来发黑。所以按下色一律取**浅灰**（>=0xDB），
    //   绝不发黑。大按钮 237→224（Δ13）、小按钮 255→239（Δ16），
    //   两档都能一眼看出"变色"，又都不刺眼。
    static final int BTN_PRESS_BIG = 0xFFE0E0DB;
    static final int BTN_PRESS_SMALL = 0xFFEFEFEC;
    static final int BTN_LINE = 0xFFC6C6C1;

    final Handler ui = new Handler(Looper.getMainLooper());

    TextView statusLine;
    TextView resultLine;
    TextView sshTag;
    TextView netTag;
    TextView bBt;
    TextView bWeread;
    TextView bSsh;
    TextView bRefresh;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i(TAG, "MainActivity opened (" + VERSION + ")");
        buildUi();
        selfHeal();
        autoReadout();
        // 面板刚画出来时做一次全刷，清掉上一屏的残影
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    sendBroadcast(new Intent(REFRESH_ACTION));
                } catch (Throwable ignored) {
                }
            }
        }, 450);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.i(TAG, "MainActivity onNewIntent -> 重新检测");
        autoReadout();
    }

    // ===================== 界面 =====================

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    GradientDrawable shape(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), BTN_LINE);
        return g;
    }

    /**
     * v18c：**用 TextView 而不是 Button**。
     *
     * 本机 ROM 会对系统 `Button` 的 pressed / disabled 状态自己做处理，
     * 在墨水屏上表现为整块区域**反色发黑**（照片实证：framebuffer 里是浅灰 `#F1F1EF`，
     * 面板上却是深灰块、文字反而更浅）。
     * 换成 TextView、背景和按压态全部自己画，就绕开了 ROM 的控件样式，
     * 按下去只会变成我们指定的**浅灰**，不会整块黑。
     */
    TextView mkBtn(String text, boolean big, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(big ? 18 : 15);
        b.setTextColor(big ? INK : INK2);
        b.setTypeface(big ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed},
                shape(big ? BTN_PRESS_BIG : BTN_PRESS_SMALL));
        sl.addState(new int[]{android.R.attr.state_enabled}, shape(big ? BTN_FILL : BG));
        sl.addState(new int[]{}, shape(0xFFF4F4F2));
        b.setBackground(sl);

        b.setPadding(dp(18), 0, dp(18), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        try {
            b.setStateListAnimator(null);   // e-ink：不要按下缩放/阴影动画
        } catch (Throwable ignored) {
        }
        // v18e：把主题可能挂上来的 tint / 前景一并清掉。
        //  ★ 这是「按下变纯黑」最可疑的根因：主题若给控件配了带 state_pressed 的
        //    backgroundTint（ColorStateList），它会**叠加在我们自绘的背景之上** ——
        //    那样不论我们把按钮底色改成多浅，按下去都会被 tint 盖成深色甚至纯黑。
        //    清空之后，按下色就 100% 由我们的 StateListDrawable 决定。
        try {
            b.setBackgroundTintList(null);
        } catch (Throwable ignored) {
        }
        try {
            b.setForeground(null);
        } catch (Throwable ignored) {
        }
        b.setOnClickListener(l);
        return b;
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(34);
        root.setPadding(padH, dp(24), padH, dp(24));

        // v15：左上角 SSH 状态栏。开=深黑（一眼看到），关=浅灰（不碍眼）
        // v16：同一行右侧再挂一个「网关：3.6 / 3.99 / 直连」
        LinearLayout tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);

        sshTag = new TextView(this);
        sshTag.setText("SSH: …");
        sshTag.setTextColor(INK3);
        sshTag.setTextSize(15);
        tagRow.addView(sshTag);

        TextView sep = new TextView(this);
        sep.setText("   |   ");
        sep.setTextColor(LINE);
        sep.setTextSize(15);
        tagRow.addView(sep);

        netTag = new TextView(this);
        netTag.setText("Gateway: …");
        netTag.setTextColor(INK3);
        netTag.setTextSize(15);
        tagRow.addView(netTag);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(16);
        root.addView(tagRow, slp);

        TextView title = new TextView(this);
        title.setText("PTBridge");
        title.setTextColor(INK);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Bluetooth page turner · key bridge · " + VERSION);
        sub.setTextColor(INK3);
        sub.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        root.addView(sub, lp);

        statusLine = new TextView(this);
        statusLine.setText("Checking…");
        statusLine.setTextColor(INK2);
        statusLine.setTextSize(16);
        lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        root.addView(statusLine, lp);

        resultLine = new TextView(this);
        resultLine.setText("");
        resultLine.setTextColor(INK3);
        resultLine.setTextSize(13);
        lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        root.addView(resultLine, lp);

        View d1 = new View(this);
        d1.setBackgroundColor(LINE);
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(22);
        root.addView(d1, lp);

        bBt = mkBtn("Fix Bluetooth", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actionBt();
            }
        });
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62));
        root.addView(bBt, lp);

        bWeread = mkBtn("Restart WeRead", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actionWeread();
            }
        });
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62));
        lp.topMargin = dp(12);
        root.addView(bWeread, lp);

        bSsh = mkBtn("Toggle SSH", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSsh();
            }
        });
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62));
        lp.topMargin = dp(12);
        root.addView(bSsh, lp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(22);
        root.addView(row, lp);

        TextView bSet = mkBtn("BT Settings", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    Log.i(TAG, "opened bluetooth settings");
                } catch (Throwable t) {
                    setResult("Cannot open BT settings: " + t);
                }
            }
        });
        row.addView(bSet, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        View gap1 = new View(this);
        row.addView(gap1, new LinearLayout.LayoutParams(dp(12), LinearLayout.LayoutParams.MATCH_PARENT));

        bRefresh = mkBtn("Refresh", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sBusy) return;
                setResult("Refreshing Bluetooth / pager status…");
                autoReadout(true);
            }
        });
        row.addView(bRefresh, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        View gap2 = new View(this);
        row.addView(gap2, new LinearLayout.LayoutParams(dp(12), LinearLayout.LayoutParams.MATCH_PARENT));

        TextView bClose = mkBtn("Close", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        row.addView(bClose, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView foot = new TextView(this);
        foot.setText("Fix Bluetooth = heal accessibility + reconnect pager. "
                + "Refresh re-reads status. Top-left shows SSH / gateway.");
        foot.setTextColor(INK3);
        foot.setTextSize(12);
        lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        root.addView(foot, lp);

        setContentView(root);
    }

    /** v18b：只在文本真的变化时才 setText —— 墨水屏上每次无谓重绘都是一次闪 */
    static void setTextIfChanged(TextView tv, String s) {
        if (tv == null) {
            return;
        }
        CharSequence cur = tv.getText();
        if (cur == null || !s.contentEquals(cur)) {
            tv.setText(s);
        }
    }

    void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                setTextIfChanged(statusLine, s);
            }
        });
    }

    void setResult(final String s) {
        Log.i(TAG, "result: " + s);
        ui.post(new Runnable() {
            @Override
            public void run() {
                setTextIfChanged(resultLine, s);
            }
        });
    }

    /**
     * v15：SSH 状态的唯一出口 —— 左上角状态条 + SSH 按钮文案一起更新，避免两处不一致。
     * @param state 1=开，0=关，-1=未知（探测不出来）
     */
    void setSshState(final int state) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (sshTag != null) {
                    setTextIfChanged(sshTag, state == 1 ? "SSH: ON" : (state == 0 ? "SSH: OFF" : "SSH: …"));
                    int want = state == 1 ? INK : INK3;   // 开着才用深黑，扫一眼就能看到
                    if (sshTag.getCurrentTextColor() != want) {
                        sshTag.setTextColor(want);
                    }
                }
                if (bSsh != null) {
                    // 按钮文案跟着状态走：在线→"Stop SSH"，离线→"Start SSH"
                    // v18b：**真的变了才写** —— 这条大按钮每重绘一次，墨水屏就闪一下
                    setTextIfChanged(bSsh, state == 1 ? "Stop SSH" : (state == 0 ? "Start SSH" : "Toggle SSH"));
                }
            }
        });
    }

    // ===== v16：按 SSID 自动切网关的守护 =====

    /**
     * v18：把「SSH 状态」和「网关」合并成**一次 su 调用**（原来要两次，每次都得起一个 su 进程），
     * 并且**不再无条件 `sleep 3`** —— 只在 state 文件确实缺失时才等一下。
     * 正常情况（守护已经在跑）几乎立刻返回，点【Refresh】明显变快。
     *
     * 守护（/data/adb/duo2-net.sh）自己会按 SSID 判断用哪个旁路由：
     *   HomeWiFi -> 192.168.3.2 ；OfficeWiFi -> 192.168.3.3 ；其它 -> 撤规则走直连。
     * 这里把「监管」和「守护」都顺手拉一次 —— 两者都有单实例守卫
     * （pid 文件里的进程还活着就立刻 exit），所以重复调用是幂等的、很便宜。
     *
     * 输出协议（便于解析）：`SSH 0|1` 一行 + `NET <state 原文>` 一行。
     */
    void probeState() {
        if (!Su.available()) {
            setSshState(-1);
            setNetState("?");
            return;
        }
        String out = Su.run(
                "setsid sh " + NET_SUP + " >/dev/null 2>&1 </dev/null &\n"
                        + "setsid sh " + NET_DAEMON + " >/dev/null 2>&1 </dev/null &\n"
                        + "if netstat -tln 2>/dev/null | grep -q ':2222' || pgrep -x sshd >/dev/null 2>&1; "
                        + "then echo 'SSH 1'; else echo 'SSH 0'; fi\n"
                        + "if [ -s " + NET_STATE + " ]; then :; else sleep 3; fi\n"
                        + "sed 's/^/NET /' " + NET_STATE + " 2>/dev/null",
                20000);

        int ssh = -1;
        String net = null;
        for (String line : out.split("\\n")) {
            String s = line.trim();
            if (s.startsWith("SSH ")) {
                ssh = s.endsWith("1") ? 1 : 0;
            } else if (s.startsWith("NET ")) {
                net = s.substring(4).trim();
            }
        }
        setSshState(ssh);
        setNetState(net == null ? "?" : gwFromState(net));
    }

    /**
     * state 文件内容形如 "HomeWiFi 6" / "OfficeWiFi 99" / "某SSID -"。
     * 取最后一列拼成 GW_PREFIX+N；"-" 表示 SSID 不在名单里、规则已撤（走直连）。
     */
    static String gwFromState(String out) {
        if (out == null) {
            return "-";
        }
        for (String line : out.split("\\n")) {
            String s = line.trim();
            if (s.length() == 0) {
                continue;
            }
            String[] f = s.split("\\s+");
            String last = f[f.length - 1];
            if ("-".equals(last)) {
                return "Direct";
            }
            if (last.matches("\\d+")) {
                return GW_PREFIX + last;
            }
        }
        return "-";
    }

    void setNetState(final String gw) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (netTag != null) {
                    setTextIfChanged(netTag, "Gateway: " + gw);
                    // 真的走了旁路由才用深黑；直连/未知用浅灰
                    int want = gw.startsWith(GW_PREFIX) ? INK : INK3;
                    if (netTag.getCurrentTextColor() != want) {
                        netTag.setTextColor(want);
                    }
                }
            }
        });
    }

    /**
     * v18b：**不再 `setEnabled(false)`**。
     *
     * 墨水屏上把大填充按钮切成"禁用"会改变它整片区域的外观，触发局部重绘，
     * 而该区域的重绘波形会把整块闪成黑色（screencap 抓不到，眼睛看得到）。
     * 所以这里只挂一个重入标志，外观完全不动 —— 不重绘就不闪。
     * 反馈靠状态行（"Refreshing… / detecting pager…"）承担。
     */
    volatile boolean sBusy = false;

    void busy(boolean b) {
        sBusy = b;
    }

    // ===================== 自愈 =====================

    /**
     * v17 自检 + 自愈：确认无障碍服务**真的被系统绑上**了，没绑上就修。
     *
     * ⚠️ 关键教训：光写 `accessibility_enabled = 1` **修不了**。
     *    服务一旦进过 AMS 的「Crashed services」黑名单，之后 re-bind 会被直接跳过 ——
     *    必须把服务名从 `enabled_accessibility_services` 里**摘掉再放回**。
     *    所以这里**只在检测到没绑上时**才动手，避免每次开面板都把服务拆一遍。
     */
    void selfHeal() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // sConnected 是本进程的静态标志；新起的进程里若 1.2s 还没绑上，就是真没绑上
                BtHelper.sleep(1200);
                if (PageTurnerService.sConnected) {
                    Log.i(TAG, "self-heal skip: 无障碍服务已绑定");
                    return;
                }
                Log.w(TAG, "self-heal: 无障碍服务未绑定 -> 执行修复序列");
                String r = repairAccessibility();
                Log.i(TAG, "self-heal result: " + r);
                if (PageTurnerService.sConnected) {
                    setResult(r);
                    einkRefresh();
                }
            }
        }).start();
    }

    /**
     * 强制清掉 AMS 的无障碍崩溃黑名单，让服务重新绑上。
     * 这是修「蓝牙连着但按键映射没了」的唯一有效手段；重启能修也是因为顺手清了这个黑名单。
     *
     * ⚠️⚠️ 顺序非常讲究，实测对比过 5 种序列，只有这一条能成：
     *   1) 先把服务名写回 enabled_accessibility_services
     *   2) 再把 accessibility_enabled 置 1  ← 名单为空时置 1 会被系统立刻弹回 0
     *   3) **等它真的变成 enabled=1**（约 3 秒）之后，才「摘掉」服务
     *      ★ 这一步是关键：只有在"无障碍确实开着"的时候摘掉，AMS 才认为这是
     *        用户主动停用，进而清空 mCrashedServices；在 enabled=0 时摘掉等于白摘。
     *   4) 放回服务 + 再开总开关
     *   （失败的那些写法：先开总开关再摘、摘掉→开→放回、放回→0→1 —— 都不行）
     */
    String repairAccessibility() {
        if (!Su.available()) {
            // 无 root 只能尽力重写设置：能修"总开关被关"，修不了"崩溃黑名单"
            try {
                Settings.Secure.putString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, SVC);
                Settings.Secure.putInt(getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                return "Accessibility: settings rewritten (no root)";
            } catch (Throwable t) {
                return "Accessibility: failed — allow PTBridge in Magisk";
            }
        }
        Su.run("settings put secure enabled_accessibility_services " + SVC + "\n"
                + "settings put secure accessibility_enabled 1\n"
                + "sleep 3\n"
                + "settings put secure enabled_accessibility_services ''\n"
                + "sleep 3\n"
                + "settings put secure enabled_accessibility_services " + SVC + "\n"
                + "settings put secure accessibility_enabled 1", 30000);
        BtHelper.sleep(2500);
        return PageTurnerService.sConnected
                ? "Accessibility: fixed, service rebound"
                : "Accessibility: not bound — reboot once";
    }

    // ===================== 打开面板时的自动检测 =====================

    /**
     * 打开面板时：顺手把蓝牙打开（自愈），再读电量。
     */
    void autoReadout() {
        autoReadout(false);
    }

    /**
     * @param fromButton true = 点【刷新】触发：**只读不动系统**。
     *                   蓝牙若本来就是关的，就如实显示"蓝牙 关闭"，不去强开 ——
     *                   刷新就该是"看一眼现在什么样"，要开蓝牙请点【修复蓝牙连接】。
     */
    void autoReadout(final boolean fromButton) {
        if (fromButton) {
            busy(true);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                probeState();         // v18：SSH 状态 + 网关，合并成一次 su

                if (fromButton && !BtHelper.isOn()) {
                    setStatus("Bluetooth off");
                    setResult("Pager unreachable — Bluetooth is off. Tap \"Fix Bluetooth\".");
                    BtHelper.sleep(250);
                    einkRefresh();
                    busy(false);
                    return;
                }

                final boolean on = BtHelper.turnOn(6000);
                setStatus(on ? "Bluetooth on · detecting pager…" : "Bluetooth off (enable failed)");

                // 等蓝牙栈把配对列表读出来
                long t0 = System.currentTimeMillis();
                while (BtHelper.pager() == null && System.currentTimeMillis() - t0 < 5000) {
                    BtHelper.sleep(300);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        BtBattery.read(MainActivity.this, new BtBattery.Cb() {
                            @Override
                            public void onBatt(String text, int level) {
                                setStatus(BtHelper.shortState(MainActivity.this) + "  |  " + text);
                                if (fromButton) {
                                    setResult(level >= 0
                                            ? "Refreshed " + now()
                                            : "Refreshed " + now() + " · " + text);
                                    einkRefresh();
                                    busy(false);
                                }
                            }
                        });
                    }
                });
            }
        }).start();
    }

    void einkRefresh() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    sendBroadcast(new Intent(REFRESH_ACTION));
                } catch (Throwable ignored) {
                }
            }
        });
    }

    static String now() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss");
        return f.format(new java.util.Date());
    }

    // ===================== 三个按钮 =====================

    void actionBt() {
        if (sBusy) return;
        busy(true);
        setResult("Fixing Bluetooth…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                // ① 先修无障碍 ——「翻页器连上了但按键映射没了」的病根就在这，
                //    老版本只重连蓝牙，所以点它永远修不好
                String fix = repairAccessibility();
                // ② 再重连蓝牙（原有逻辑，负责"连不上 / 断连"那一类）
                String r = BtHelper.reconnect(MainActivity.this);
                setStatus(BtHelper.shortState(MainActivity.this));
                setResult(fix + "  ｜  " + r);
                busy(false);
                einkRefresh();
            }
        }).start();
    }

    void actionWeread() {
        if (sBusy) return;
        busy(true);
        setResult("Restarting WeRead…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!Su.available()) {
                    setResult("Root required — allow PTBridge in Magisk");
                    busy(false);
                    return;
                }
                String kill = Su.run("am force-stop " + WEREAD, 10000);
                Log.i(TAG, "force-stop weread: " + kill);
                BtHelper.sleep(900);

                String start = Su.run("am start -W -n " + WEREAD_ACT, 20000);
                boolean ok = start.contains("Status: ok") || start.contains("Complete");
                if (!ok) {
                    Log.w(TAG, "ReaderFragmentActivity 启动失败，改用 LauncherActivity");
                    start = Su.run("am start -W -n " + WEREAD_LAUNCH, 20000);
                    ok = start.contains("Status: ok") || start.contains("Complete");
                }
                Log.i(TAG, "am start weread: " + start);
                setResult(ok ? "WeRead restarted (process rebuilt)"
                        : "Start failed: " + firstLine(start));
                busy(false);
            }
        }).start();
    }

    // ===== SSH：启动 / 关闭 开关 =====

    /** 2222 有没有人在听。netstat 读不到时用进程名兜底。 */
    boolean sshOnline() {
        String p = Su.run("netstat -tln 2>/dev/null | grep ':2222'", 8000);
        if (p != null && p.trim().length() > 0) {
            return true;
        }
        String g = Su.run("pgrep -x sshd 2>/dev/null", 6000);
        return g != null && g.trim().length() > 0;
    }

    void sshSend(boolean on) {
        try {
            Intent i = new Intent(on ? SSH_START : SSH_STOP);
            i.setClassName(SSH_PKG, on ? SSH_START_RCVR : SSH_STOP_RCVR);
            sendBroadcast(i);
            Log.i(TAG, "sshd " + (on ? "START" : "STOP") + " broadcast sent");
        } catch (Throwable t) {
            Log.e(TAG, "sshd broadcast failed: " + t);
        }
    }

    /**
     * 开关式：先探状态，在线就关、离线就开。
     * 关闭优先用 SimpleSSHD 自带的 STOP 广播；广播无效才 `pkill -x sshd` 掐进程。
     * **不用 force-stop**：那会把 SimpleSSHD 打进 stopped 状态，BootReceiver 的开机自启就废了。
     */
    void toggleSsh() {
        if (sBusy) return;
        busy(true);
        setResult("Checking SSH…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!Su.available()) {
                    setSshState(-1);
                    setResult("Root required — allow PTBridge in Magisk");
                    busy(false);
                    return;
                }

                boolean wasOn = sshOnline();
                Log.i(TAG, "ssh probe: " + (wasOn ? "ONLINE" : "OFFLINE"));

                if (wasOn) {
                    setResult("SSH online → stopping…");
                    sshSend(false);
                    BtHelper.sleep(1200);
                    if (sshOnline()) {
                        Log.w(TAG, "STOP 广播没关掉，改用 pkill -x sshd");
                        Su.run("pkill -x sshd", 6000);
                        BtHelper.sleep(900);
                    }
                    boolean still = sshOnline();
                    setSshState(still ? 1 : 0);
                    setResult(still
                            ? "Cannot stop: port 2222 still listening — open SimpleSSHD and stop it"
                            : "SSH stopped (port 2222 released)");
                } else {
                    setResult("SSH offline → starting…");
                    sshSend(true);
                    BtHelper.sleep(1600);
                    boolean on = sshOnline();
                    if (!on) {
                        sshSend(true);   // 兜底：再广播一次
                        BtHelper.sleep(1800);
                        on = sshOnline();
                    }
                    setSshState(on ? 1 : 0);
                    setResult(on ? "SSH started, port 2222 listening"
                            : "SSH did not start — open SimpleSSHD and retry");
                }
                busy(false);
            }
        }).start();
    }

    static String firstLine(String s) {
        if (s == null || s.length() == 0) {
            return "(no output)";
        }
        int i = s.indexOf('\n');
        String r = (i < 0) ? s : s.substring(0, i);
        return r.trim();
    }
}
