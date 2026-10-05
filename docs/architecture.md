# 工作原理

> 下面的坐标数字全部是 **1404×1872** 屏上实测出来的。换机型要重新量。

## 1. 总览

```
蓝牙翻页器 (HID)
      │  触摸 / 按键事件
      ▼
┌──────────────────────────────────────────────┐
│ PageTurnerService（无障碍服务，同进程）       │
│                                              │
│  ① 隐形浮窗细带  y=928, 高 16px, 全透明       │  接住翻页器的触摸
│  ② onKeyEvent()                             │  接住翻页器的按键
│  ③ dispatchGesture()                        │  注入手势 → 阅读 App
└──────────────────────────────────────────────┘
      │
      ▼
微信读书墨水屏版 / KOReader
```

面板（`MainActivity`）和服务跑在**同一个进程**里，所以一个 `static boolean` 就能如实反映
「服务到底有没有被系统绑上」。

---

## 2. 为什么用「浮窗细带」而不是直接 `onKeyEvent`

蓝牙翻页器发的是标准 HID 事件。理想情况下无障碍服务的 `onKeyEvent()` 就能接住，
但实际测下来，这台机器上的翻页器**按键根本没走到 `onKeyEvent`**，
反而是以**触摸事件**的形式出现在屏幕中央。

实测规律（一个像素都不差）：

| 动作 | 事件特征 |
|---|---|
| 所有触摸 | Y 恒为 **935.06** |
| 短按 | 从 x534 向右滑 |
| 双击 | 在 x843 点两下，间隔 134~139 ms |

所以做法是：在 **y=928、高 16px** 的位置盖一条**横贯全屏的隐形浮窗**，
把翻页器的触摸全部接住。它只占一条极窄的横带，屏幕其余部分完全不受影响。

```java
static final int BAND_TOP = 928;
static final int BAND_H   = 16;
```

> **全透明也能接住**：浮窗是靠**窗口边界**接收触摸的，不靠像素可见性，
> 所以背景设成 `0x00000000` 照样工作，肉眼完全无感。

浮窗参数里三个 flag 都有用：

| flag | 作用 |
|---|---|
| `FLAG_NOT_FOCUSABLE` | 不抢焦点，不影响底下 App |
| `FLAG_NOT_TOUCH_MODAL` | 只截自己范围内的触摸，带外照常传给下层 |
| `FLAG_LAYOUT_NO_LIMITS` | 允许超出常规布局边界 |

---

## 3. 怎么区分「翻页器」和「真人手指」

细带是全屏宽的，手指划过时也会被接住 —— 得区分开。

```java
boolean isPageTurner = name.contains("Virtual") || ev.getDeviceId() < 0;
```

⭐ **这是个反直觉的点**：翻页器的触摸上报设备名是 `"Virtual"`、`deviceId = -1`，
**不是** `"ATG-SJL"` 这类蓝牙设备名。原因是它有两个 HID 节点、descriptor 相同，
被系统合并后走了**虚拟输入设备**通道。

真人手指则来自 `goodix_ts`（触摸屏驱动）。对这种事件，代码会**原样回放一次**，
尽量不打扰正常操作：

```java
void replayTap(float x, float y, String name) {
    float gy = y + BAND_TOP;         // 局部坐标 → 屏幕坐标
    // 在同一个位置注入一个 40ms 的"点"
    ...
}
```

---

## 4. 单双击判定

翻页器只发「点」和「连点两下」，所以需要自己判定：

```java
static final int DOUBLE_WINDOW_MS = 220;   // 实测双击间隔 ~140ms，取 220 留余量
```

每收到一次 UP 就 `tapCount++`，并用 `handler.removeCallbacksAndMessages(null)`
**重置**延时任务；220ms 内没有第二次点击，才按当前计数分发：

| 计数 | 动作 | 注入的手势 |
|---|---|---|
| 1 | 下一页 | 从 x1100 滑到 x300，y=1200 |
| ≥2 | 上一页 | 从 x300 滑到 x1100，y=1200 |

⭐ **手势参数是试出来的**，不是猜的 —— 微信读书对滑动很挑：

| 参数 | 结果 |
|---|---|
| 时长 130ms | ✅ 有效 |
| 时长 350ms | ❌ 太慢，无效 |
| y = 1200 | ✅ 有效 |
| y = 600 | ❌ 无效 |

---

## 5. 防回环：自己注入的事件会再被自己接住

`dispatchGesture()` 注入的手势，**同样会经过那条细带** —— 不处理就会无限循环。

```java
static final int SELF_QUIET_MS = 400;   // 注入后 400ms 内的触摸一律忽略
```

---

## 6. 长按 = 全屏刷新

翻页器的**长按**（音量减）不是触摸，它走 `onKeyEvent()`：

```java
if (name.contains("ATG")
        && code == KeyEvent.KEYCODE_VOLUME_DOWN
        && act == KeyEvent.ACTION_UP) {
    sendBroadcast(new Intent("android.eink.force.refresh"));
    return true;             // 吞掉，别让系统去调音量
}
```

`android.eink.force.refresh` 是这台 ROM 用来触发**墨水屏全刷**的广播，刷新波形由 ROM 自己选。

---

## 7. 两个"顺手做掉"的自动化

**① 自动开蓝牙（事件驱动，不新增轮询进程）**

解锁 / 切窗口 / 打开微信读书都会产生无障碍事件，借这个时机顺路查一次蓝牙总开关，
30 秒节流：

```java
if (now - sLastBtCheckMs < 30000) return;
```

关着就 `adapter.enable()`；`enable()` 被系统拒绝时（Android 11 上很常见）再走 root：
`su -c "svc bluetooth enable"`。

> ⚠️ 这里必须用 **su 的绝对路径** —— 本机 `su` 只在 `/system_ext/bin` 和 `/debug_ramdisk`，
> `PATH` 里没有，写相对名 `su` 必然失败。

**② 自动关掉微信读书的更新弹窗**

微信读书的更新提示是原生 QMUI Dialog，无障碍能读到文本，所以可以替用户点掉：

```java
static final String UPDATE_LATER = "稍后更新";   // ⚠️ 这是中文文案匹配，别翻译！
```

流程：`findAccessibilityNodeInfosByText("稍后更新")` → 文本节点本身通常不可点，
**往上找可点击的父节点** → `ACTION_CLICK`。1.5 秒节流。

---

## 8. 「服务有没有真的被系统绑上」

```java
static volatile boolean sConnected = false;   // onServiceConnected / onDestroy 里维护
```

面板打开时读它（同进程，静态标志可靠），没绑上就跑一次修复序列。

**为什么不能只看 `accessibility_enabled`**：服务崩过之后，AMS 会把它记进
`Crashed services` 黑名单，此后**重新绑定时直接跳过**——开关显示是开的，实际没绑。
完整修法见 [troubleshooting.md](troubleshooting.md#一蓝牙连着但按键映射没了)。

---

## 9. 网络守护（可选组件）

面板左上角显示的「网关」来自两层脚本：

```
/data/adb/service.d/duo2-net.sh   ← net-supervisor.sh，Magisk 开机拉起，负责保活
        │ setsid 拉起
        ▼
/data/adb/duo2-net.sh             ← net-autoswitch.sh，每 30s reconcile 一次
        │ 写状态
        ▼
/data/adb/duo2-net.state          ← 内容形如 "HomeWiFi 192.168.1.2"，面板读它显示网关
```

设计要点：

- **为什么要有「监管」这一层**：守护会被系统 / 清理工具干掉，进程一死默认路由就没人维护了。
  监管每 60 秒查一次 pid，不在就重新拉起。
- **幂等**：两层都有单实例守卫（pid 文件里的进程还活着就直接 exit），
  所以面板重复调用是安全的、很便宜。
- **自己提权**：被普通 App 拉起时会继承很高的 `oom_score_adj`，脚本自己把自己写到 `-900`，
  免得被"一键清理"顺手带走。
- **reconcile 条件**（⭐ 这里踩过大坑）：判断依据是「**实际网关** ≠ 我们设过的网关」，
  **不是**「想要的配置变了」。后者在 SSID 不变时永远不会触发，
  于是 WiFi 一重连把路由刷回主路由，就再也改不回来了。

细节和防火墙规则见 [scripts/net-autoswitch.sh](../scripts/net-autoswitch.sh) 顶部注释。
