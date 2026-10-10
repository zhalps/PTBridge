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
import android.view.MotionEvent;
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
 * v18.2（2026-10-08，迪迪报「一键清理后点 Fix 不生效，隔一会儿再点才生效」）：
 *  ⭐ 病根在第 2 个按钮的蓝牙分支，**跟无障碍无关**。见 BtHelper.reconnect() 的注释。
 *    取证结论（logcat 实证）：
 *      15:29:19 第 1 次点 Fix → 15:29:25 `band added OK`（无障碍这边是好的）
 *      → 15:29:28 报 `Bluetooth on · pager connected`，**但蓝牙侧一个动作都没做**
 *      → 15:29:28~15:30:21 整整 53 秒里 PTBridge **零条 TOUCH**（按键根本没到达系统）
 *      → 15:30:20 系统 HCI 层超时才 `reason=0x0008` 拆掉 HID
 *      → 15:30:21.517 `btif_hh_upstreams_evt: name = ATG-SJL`（输入设备这一刻才真正建立）
 *      → 15:30:21.909 第一个按键终于进来 ✅
 *    原因：`hidState()` 报的 CONNECTED 会「假在线」（底层 BLE 链路僵死、状态没更新），
 *      而老代码 `if (st == STATE_CONNECTED) return "pager connected";` 直接早退。
 *    改法（见 BtHelper）：状态报 CONNECTED 时不再早退 ——
 *      第 1 次点：发一次温和重连 + 如实提示 `(if it won't page, press a pager key)`；
 *      60 秒内第 2 次点且状态仍是 CONNECTED → 强制 `svc bluetooth disable/enable` 重建链路。
 *    代价：只在"状态正常却说不能翻页"这种场景下多花 10~15s；平时完全不受影响。
 *
 * v18.3（2026-10-08，方案 A 实测通过后收尾）：
 *  - 逻辑零改动，只补 footer：把「60 秒内再点一次 Fix 可强制重建蓝牙」写进面板，
 *    否则用户根本不知道有这个操作（footer 由 2 行改 3 行）。
 *  - 实测证据（logcat 实证）：
 *      第1次点：15:46:12.785 secondPress=false → 温和重连 + 如实提示 ✅
 *      15 秒后第2次点：15:46:28.001 secondPress=true
 *        → 15:46:28.002 「二次点按 + 状态仍 CONNECTED -> 强制重建链路」
 *        → 15:46:29.033/069 EventHub Removed device event4/event5（链路真被拆）
 *        → 15:46:29.119 su ok :: svc bluetooth disable
 *        → 15:46:31.436 su ok :: svc bluetooth enable
 *        → 15:46:35.136 bt_stack Added device AA:BB:CC:DD:EE:FF
 *        → 15:46:41.605 「Bluetooth on · pager offline (press a pager key to wake it)」
 *    ⚠️ 强制重建后翻页器**不会自动回连**（BLE HID 被断开后不主动广播），
 *       要按一下翻页器任意键唤醒 —— 提示文案已如实告知，属预期行为，不是 bug。
 *    ⚠️ 关键时序：Fix 全流程含无障碍修复的 sleep 3 + sleep 3，耗时约 9s；
 *       `lastFixPressAt` 是走到**蓝牙分支**（≈点击后第 9 秒）才置位的，
 *       所以两次点击的实际间隔应约 12~15s —— 更短会被 sBusy 直接丢弃。
 *
 * v19.0（2026-10-10，迪迪提的三件事）：
 *
 *  ① **Fix Bluetooth 改成"一次点击即强制重建"**（去掉 60 秒二次点击窗口）
 *     迪迪原话：「不需要在 60 秒之内第二次点击才强制修复了，第一次点击就直接强制修复吧，
 *     因为经常断掉，都是需要强制修复的。」
 *     → `BtHelper.reconnect()` 现在 read 完 hidState 就**直接** `forceRestart()`；
 *        `lastFixPressAt` / `RETRY_WINDOW_MS` 已删除。
 *     ⚠️ 代价：每次点 Fix 蓝牙都会短暂断开（约 10~15 秒），只在"真的不能翻页"时才按。
 *
 *  ② **三个功能按钮整体上移到 y&lt;860，避开翻页器的触摸带**（这是个大坑）
 *     翻页器的触摸**恒定落在 y≈935**（1404×1872 屏正中，占屏高 49.7%）。
 *     v18 的设计是"在 935 那条线上开一条隐形细带接住它"，副作用是：
 *     面板一打开，翻页器默认那些点就直接把
 *     【Fix Bluetooth】(y861~977) 和【Stop SSH】(y1137~1253) 按了一串 ——
 *     用户「想看看有没有修成功」，眼睛还没扫到，按钮已经被按完了。
 *     → v19 反过来做：**把三个按钮全部移到 860 以上**，让 y935 那条线在面板里
 *       **没有任何控件**，触摸就落回下面 App 自己的窗口（微信读书里 = 正常翻页）。
 *       底部小按钮行（y1284~1330）和测试区（y~1100）本来就离得远，保持不动。
 *       `root.setGravity(CENTER_VERTICAL → TOP)`：否则整块内容在屏高上浮动，位置不可预测。
 *     ⚠️ 随之删掉 `PageTurnerService.addBand()`：面板与书的布局不同，
 *       950 那条带在面板里接不到、在书里又多余，**没有它反而两边都对**。
 *
 *  ③ **面板内新增「翻页测试区」**：不用切到微信读书就能验证修好没有。
 *     大字 `123 翻页` + 计数行 `next: N   prev: M   ✅ keys are reaching the system`。
 *     实现要点：面板在前台时把 `PageTurnerService.sTestMode = true` →
 *       **服务只记录计数、不注入翻页手势**（否则在面板上按一下会误翻后面那本书）。
 *     测试区只负责"显示"，**不接收**触摸（否则又占用 935 那条线）。
 *     计数每 400ms 轮询一次，数字变了才 setText（墨水屏少重绘）。
 *
 * v16：
 *  - 打开面板时**自动确保「按 SSID 切网关/DNS」的守护进程在跑**，并回读当前生效的网关
 *    显示在左上角（`SSH：开 ｜ 网关：3.6`）。守护本身按 SSID 决定用 3.6 还是 3.99：
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
    static final String VERSION = "v20.5";

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

    // ---- v19：面板「翻页测试区」配色（浅色主题，墨水屏友好）----
    static final int TEST_FILL = 0xFFF2F2EF;
    static final int TEST_BORDER = 0xFF9A9A94;
    static final int TEST_OK = 0xFF1B5E20;      // 收到按键 = 深绿
    static final int TEST_IDLE = 0xFF9A9A94;    // 还没收到 = 灰

    /**
     * v19 的关键常量：**所有按钮必须完全落在 y &lt; SAFE_BOTTOM**。
     *
     * 为什么：翻页器的触摸**恒定落在 y≈935**（1404×1872 屏的正中，占屏高 49.7%），
     * 系统认为它属于屏幕**中部的浮窗堆叠区** —— 只有当我们的窗口里有控件
     * 覆盖住那一片时，触摸才会被我们接住（这正是 v18 那条 y928~944 隐形细带的由来）。
     * 实测：面板一打开，翻页器默认那些点就直接按在了
     * 【Fix Bluetooth】(y861~977) 和【Stop SSH】(y1137~1253) 上 ——
     * 所以用户「想看看有没有修成功」时，眼睛还没扫到，按钮已经被按了一串。
     *
     * 解法：把三个功能按钮全部移到 860 以下，让 y935 那一条**没有我们的控件**，
     * 触摸就落到下面 App 自己的窗口里（在微信读书里就是正常翻页），面板完全不受影响。
     */
    static final int SAFE_BOTTOM = 860;

    int shownSeq = -1;      // 上一次渲染的事件序号，变了才 setText（墨水屏少重绘）

    final Handler ui = new Handler(Looper.getMainLooper());

    TextView statusLine;
    TextView resultLine;
    TextView sshTag;
    TextView netTag;
    TextView bBt;
    TextView bWeread;
    TextView bSsh;
    TextView bRefresh;

    // v20：Page Turner Test 区的文本
    LinearLayout testBox;
    TextView testTitle;
    TextView[] testLines;          // 每行的第 1 列（动作名），兼容旧引用
    TextView[][] testCols;         // [行][列] —— 0=动作名 1=次数 2=效果

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

    /**
     * v19：面板在前台时打开「测试模式」——服务只记录按键、不注入翻页手势。
     * 这样在面板上按翻页器不会误翻后面那本书，测试区又能如实反映"按键到没到"。
     */
    @Override
    protected void onResume() {
        super.onResume();
        PageTurnerService.sTestMode = true;
        PageTurnerService.sCntSingle = 0;
        PageTurnerService.sCntDouble = 0;
        PageTurnerService.sCntLong = 0;
        PageTurnerService.sEventSeq = 0;
        shownSeq = -1;
        testPolling = true;
        ui.post(testPoll);
        Log.i(TAG, "test mode ON (panel foreground)");
    }

    @Override
    protected void onPause() {
        super.onPause();
        testPolling = false;
        ui.removeCallbacks(testPoll);
        PageTurnerService.sTestMode = false;
        Log.i(TAG, "test mode OFF (panel left foreground)");
    }

    /**
     * v20.1：**面板自己接翻页器的触摸**（这是「面板里按没反应」的最终修法）。
     *
     * 根因（2026-10-10 清缓冲后实测坐实）：
     *   面板是 `BASE_APPLICATION` 的全屏焦点窗口，band 是 `TYPE_APPLICATION_OVERLAY`。
     *   虽然 band 的 mBaseLayer(121000) 远高于面板(21000)，但**层号高不等于能接到触摸**——
     *   当**同一个 UID** 既持有全屏焦点窗口又挂着 overlay 时，系统给 overlay 派发触摸前
     *   会先判断"这块区域是不是被同 UID 的焦点窗口覆盖"，面板是全屏不透明白底 →
     *   16px 细带完全落在面板内容里 → 触摸被面板自己吃掉。
     *   实证：`20:54:20.103 test mode ON (panel foreground)` 之后**一条 TOUCH 都没有**；
     *   面板一销毁（20:53:35 wm_destroy_activity），20:53:38 立刻恢复正常。
     *
     * 解法：面板在前台时，由面板自己分发 —— 只把**虚拟设备**（翻页器）的事件转给服务，
     *   真人手指、按钮点击照常走正常流程，一点不受影响。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (PageTurnerService.sConnected) {
            try {
                PageTurnerService svc = PageTurnerService.sInstance;
                if (svc != null && svc.handleTouchFromPanel(ev)) {
                    return true;   // 翻页器的触摸已被消费，不往下传（否则会误触按钮）
                }
            } catch (Throwable t) {
                Log.e(TAG, "dispatchTouchEvent: " + t);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    volatile boolean testPolling = false;

    /**
     * v20：Page Turner Test 刷新。每 350ms 看一次事件序号，
     * 有新事件才重绘（墨水屏上每次无谓重绘都是一次闪）。
     *
     * 三行永远是这三行，只把中间的 `--` 换成 `#N`：
     *   Single click   #2   Next page     ← OK 用颜色表示（深绿 = 通了）
     */
    final Runnable testPoll = new Runnable() {
        @Override
        public void run() {
            if (!testPolling) {
                return;
            }
            final int seq = PageTurnerService.sEventSeq;
            if (seq == shownSeq) {
                ui.postDelayed(this, 350);
                return;
            }
            shownSeq = seq;

            final int c1 = PageTurnerService.sCntSingle;
            final int c2 = PageTurnerService.sCntDouble;

            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (testLines == null) {
                        return;
                    }
                    setLine(0, "Single click", c1, "Next page");
                    setLine(1, "Double click", c2, "Prev page");
                    einkRefresh();
                }
            });
            ui.postDelayed(this, 350);
        }
    };

    /**
     * 渲染一行：第 1 列动作名不变，第 2 列 `#N`（未触发时 `--`），第 3 列效果名不变。
     * 次数 > 0 时三列一起转深绿 —— 一眼就能看出"这个动作通了"。
     */
    void setLine(int idx, String action, int cnt, String effect) {
        if (testCols == null || idx >= testCols.length) {
            return;
        }
        TextView[] cols = testCols[idx];
        if (cols == null || cols.length < 3) {
            return;
        }
        setTextIfChanged(cols[0], action);
        setTextIfChanged(cols[1], cnt > 0 ? ("#" + cnt) : "--");
        setTextIfChanged(cols[2], effect);

        int want = cnt > 0 ? TEST_OK : INK3;
        for (TextView c : cols) {
            if (c != null && c.getCurrentTextColor() != want) {
                c.setTextColor(want);
            }
        }
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
        // v19：改成 TOP —— 按钮要整体压到 y<860（避开翻页器的 y≈935 触摸带），
        // CENTER_VERTICAL 会让整块内容在屏高上浮动，位置不可预测。
        root.setGravity(Gravity.TOP);
        int padH = dp(34);
        root.setPadding(padH, dp(18), padH, dp(24));

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
        slp.bottomMargin = dp(10);
        root.addView(tagRow, slp);

        // ---- 标题行：标题 + 版本放在同一行，省出纵向空间给按钮 ----
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.BOTTOM);

        TextView title = new TextView(this);
        title.setText("PTBridge");
        title.setTextColor(INK);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title);

        TextView sub = new TextView(this);
        sub.setText("   " + VERSION);
        sub.setTextColor(INK3);
        sub.setTextSize(13);
        titleRow.addView(sub);

        root.addView(titleRow);

        statusLine = new TextView(this);
        statusLine.setText("Checking…");
        statusLine.setTextColor(INK2);
        statusLine.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        root.addView(statusLine, lp);

        resultLine = new TextView(this);
        resultLine.setText("");
        resultLine.setTextColor(INK3);
        resultLine.setTextSize(13);
        lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        root.addView(resultLine, lp);

        View d1 = new View(this);
        d1.setBackgroundColor(LINE);
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        root.addView(d1, lp);

        // ===================== v19：三个功能按钮（全部落在 y<860） =====================
        bBt = mkBtn("Fix Bluetooth", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actionBt();
            }
        });
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        root.addView(bBt, lp);

        bWeread = mkBtn("Restart WeRead", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actionWeread();
            }
        });
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        lp.topMargin = dp(10);
        root.addView(bWeread, lp);

        bSsh = mkBtn("Toggle SSH", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSsh();
            }
        });
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        lp.topMargin = dp(10);
        root.addView(bSsh, lp);

        // ===================== v20.3：Page Turner Test =====================
        // 目的：不用切到微信读书，在这个面板上就能看出"修好了没有"。
        //
        // v20.3：**只留两行，字号加大**（迪迪：「操作写不下就算了，删掉一行排版居中好看点」+
        //   「下面显示的字都太小了，看不出效果，稍微放大点」）。
        //   长按那行删掉 —— 翻页器的长按实际发的是蓝牙音量键（KEYCODE_VOLUME_DOWN），
        //   走的是 onKeyEvent 那条快路，跟触摸手势是两回事，混在一起反而让人困惑。
        //   保留的两行正是最常用来验证"通没通"的两个动作：
        //     Single click  #N  ->  Next page
        //     Double click  #N  ->  Prev page
        testBox = new LinearLayout(this);
        testBox.setOrientation(LinearLayout.VERTICAL);
        testBox.setBackground(shape2(TEST_FILL, TEST_BORDER, dp(10), dp(2)));
        testBox.setPadding(dp(20), dp(16), dp(20), dp(16));

        testTitle = new TextView(this);
        testTitle.setText("Page Turner Test");
        testTitle.setTextSize(20);
        testTitle.setTypeface(Typeface.DEFAULT_BOLD);
        testTitle.setTextColor(INK);
        testTitle.setGravity(Gravity.CENTER);
        testBox.addView(testTitle);

        // ===================== v20.4：三列独立 TextView =====================
        // v20.3 的写法是「一整行字符串 + 等宽字体 + 空格补齐」，实测对齐不好看：
        //   两行 action 词长不同（Single/Double），补空格后左边起点不齐，
        //   居中时又把整块往左推。改成**三列各一个 TextView、横向并排、各自居中**，
        //   第 1 列和第 3 列定宽、第 2 列窄一点放 `#N`，天然对齐，且放大字号也不会错位。
        testLines = new TextView[2];
        testCols = new TextView[2][3];
        String[][] seed = {
                {"Single click", "--", "Next page"},
                {"Double click", "--", "Prev page"},
        };
        for (int i = 0; i < testLines.length; i++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            for (int c = 0; c < 3; c++) {
                TextView t = new TextView(this);
                t.setText(seed[i][c]);
                t.setTextSize(20);
                t.setTextColor(INK3);
                t.setGravity(Gravity.CENTER);
                // 第 1 列（动作名）固定权重略大、第 2 列（次数）窄、第 3 列（效果）与第 1 列等宽
                float wt = (c == 1) ? 0.55f : 1.0f;
                line.addView(t, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, wt));
                testCols[i][c] = t;
            }

            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(i == 0 ? 12 : 8);
            testBox.addView(line, tlp);
            testLines[i] = testCols[i][0];   // 兼容旧引用
        }

        lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        root.addView(testBox, lp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(14);
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
        foot.setText("Fix Bluetooth restarts Bluetooth on the FIRST tap "
                + "(brief disconnect; press a pager key to wake it afterwards).\n"
                + "Buttons sit above the middle so the pager can't tap them.");
        foot.setTextColor(INK3);
        foot.setTextSize(12);
        lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        root.addView(foot, lp);

        setContentView(root);
    }

    /** v19：带独立描边的圆角背景（测试区用，需要比按钮更明显的框） */
    GradientDrawable shape2(int fill, int stroke, int radius, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(strokeW, stroke);
        return g;
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
     * 取最后一列拼成 3.6 / 3.99；"-" 表示 SSID 不在名单里、规则已撤（走直连）。
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
                return "3." + last;
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
                    int want = gw.startsWith("3.") ? INK : INK3;
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
