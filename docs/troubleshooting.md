# 踩坑手册

这个项目最大的成本不是写代码，是**搞明白为什么坏了**。下面每一条都是实测出来的，
按"症状 → 原因 → 怎么办"组织。

---

## 一、「蓝牙连着，但按键映射没了」

**症状**：翻页器明明连着，翻页却失灵了 —— 短按变成了「上一页」，长按变成「下一页」，
全都是**翻页器的原始手势**，好像 PTBridge 根本没在拦。

**原因**：无障碍服务崩过 / 被杀过，AMS 把它记进了 **`Crashed services` 黑名单**。
进黑名单之后，**重新绑定时系统直接跳过它** —— 所以设置里无障碍开关看着是开的，
服务实际上没绑上。

> 这也解释了为什么「重启一下就好了」—— 重启把黑名单清空了。

### ⚠️ 光把开关置 1 是没用的

```bash
# 这条命令修不好它
settings put secure accessibility_enabled 1
```

必须把服务从名单里**摘掉再放回**。但顺序非常讲究 ——
实测对比过 5 种写法，**只有下面这一条能成**：

```bash
settings put secure enabled_accessibility_services  com.didi.pageturner/.PageTurnerService
settings put secure accessibility_enabled 1
sleep 3          # ★ 必须等它真的变成 enabled=1
settings put secure enabled_accessibility_services  ''
sleep 3
settings put secure enabled_accessibility_services  com.didi.pageturner/.PageTurnerService
settings put secure accessibility_enabled 1
```

**关键点**：「摘掉」这一步**必须在无障碍确实开着（`enabled=1`）的时候做** ——
只有这样 AMS 才认为这是用户主动停用，进而清空 `mCrashedServices`。
在 `enabled=0` 的时候摘掉等于白摘。

失败过的写法（别试了）：先开总开关再摘 / 摘→开→放回 / 放回→0→1。

**佐证**：
```
摘掉后： Crashed services: {PageTurnerService} → {}
放回后： Bound services:   {} → {Service[label=PTBridge ...]}
```

### 怎么确认是这个问题

```bash
dumpsys accessibility | grep -A2 "Crashed services"
dumpsys accessibility | grep -A2 "Bound services"
```

`Crashed services` 里有东西、或者 `Bound services` 是空的 → 就是它。

### 两个会触发黑名单的操作

| 操作 | 后果 |
|---|---|
| `am force-stop com.didi.pageturner`（"一键清理"就是这么干的） | 把 `enabled_accessibility_services` 直接清成 `null` + 服务进黑名单，**一次干两件事** |
| `pm install -r`（每次重装） | 同样杀进程、进黑名单 |

所以 **`pm install -r` 之后记得打开一次面板** —— 面板一打开就会自检 + 自愈。

---

## 二、墨水屏能改的和改不了的

先说结论：**墨水屏上「大面积填充控件」不要拿"外观变化"当状态反馈。**
禁用变灰、按压高亮、透明度动画 —— 在墨水屏上都是雷。

### 症状

用户反馈：「点某个按钮之后，那几个大按钮**变成纯黑**」。

### 反直觉的地方

连拍 17 帧逐像素采样，同一位置的填充色：

| 状态 | framebuffer 实测 |
|---|---|
| 常态 | `#E9E9E5` |
| `setEnabled(false)` 之后 | `#F1F1EF`（**反而更浅**） |
| 恢复 | `#E9E9E5` |

→ **黑色根本不在 framebuffer 里。`screencap` 永远拍不到，眼睛却看得到。**

真因：大填充控件的**外观一变** ⇒ 墨水屏对该区域**局部重绘** ⇒
该区域用的刷新波形**把整块反色 / 闪黑**。
（小控件面积小，所以只有那三个大按钮明显。**变浅变深一样会黑**，与方向无关。）

### 改法 A（首选）：状态反馈干脆不动外观

```java
volatile boolean sBusy = false;          // 只挂标志
void busy(boolean b) { sBusy = b; }

void actionBt() {
    if (sBusy) return;                   // 各点击入口自己挡
    ...
}
```

反馈交给一行状态文本承担。「正在忙」这件事，文字说得清楚，不需要把按钮变灰。

顺带把 `setText` 全改成「**内容真的变了才写**」：

```java
if (cur == null || !s.contentEquals(cur)) tv.setText(s);
```

墨水屏上**每一次无谓重绘都是一次闪**。

### 改法 B（必须保留按压高亮时）：别用系统 `Button`

很多墨水屏 ROM 对 `android.widget.Button` 的 `pressed` / `disabled` 有自己的处理
（定制样式，或主题挂的 ripple / `stateListAnimator`），表现为**整块反色发黑**。
换成 `TextView`，背景和按压态全部自己画：

```java
TextView b = new TextView(this);
b.setBackground(myStateListDrawable);

try { b.setStateListAnimator(null);    } catch (Throwable ignored) {}
try { b.setBackgroundTintList(null);   } catch (Throwable ignored) {}  // ★ 最容易漏
try { b.setForeground(null);           } catch (Throwable ignored) {}
b.setOnClickListener(l);
```

其中 **`setBackgroundTintList(null)` 最容易被漏掉**：
如果主题给控件配了带 `state_pressed` 的 `backgroundTint`（ColorStateList），
它会**叠加在你自绘的背景之上** —— 那样不论把底色调到多浅，按下去都会被 tint 盖深、甚至盖黑。

按下色本身取**浅灰**就行（实测 `#E0E0DB` / `#EFEFEC` 在面板上都不会被读成黑）。

### 取证：怎么证明「我画的颜色不是黑」

面板级现象拍不到，但可以证明我们画的颜色不是黑。先拿**权威坐标**，别肉眼估：

```bash
uiautomator dump /sdcard/ui.xml      # 从 xml 里读各控件的 bounds="[l,t][r,b]"
```

保持按下期间抓帧：

```bash
input motionevent DOWN <x> <y>       # Android 10+ 可用
sleep 0.35; screencap -p /sdcard/_p1.png
sleep 0.35; screencap -p /sdcard/_p2.png
input motionevent UP   <x> <y>
```

再逐点采样灰度，或用差分求**变化包围盒**（`PIL.ImageChops.difference(a,b).getbbox()`）——
包围盒 ≈ 整个按钮矩形，就说明按下态确实渲染了整块。

> ⚠️ **坑**：上一个动作还在跑的时候，下一组 `DOWN`/`UP` 会被**一起排队处理掉**，
> 抓到的按下帧和常态**逐字节完全相同**，白测一场。
> 一个动作彻底跑完再测下一个（重一点的流程可能要 10 秒）。
>
> ⚠️ **采样点要避开文字**：居中文字会把 `#E9E9E5` 采成 `#090909`，
> 看着像"黑"其实是字形。取按钮内、文字行之外的位置。

### 面板级现象：确实改不了

想从系统侧要"刷新波形控制权"是拿不到的：

| 尝试 | 结果 |
|---|---|
| `service list` | 有 `eink: [android.os.IEinkManager]` |
| `cmd -l` | 列出 `eink` |
| `cmd eink` | ❌ `No shell command implementation` |
| `content query --uri content://com.android.systemui.eink/einksettingsupdate` | ❌ NPE |
| `settings_secure.xml` 里的 `refreshmode` | 只是快捷磁贴**条目**，不是模式值 |
| 点一下按钮 | ROM 自己会跟一次 `EinkManager: sendOneFullFrame`（全屏刷新，会清残影） |

→ **刷新波形不由第三方控制。** 认了吧。

---

## 三、「点按钮冒出一条黑色长条」

**先说结论：那通常不是你的 App 弹的。**

它是 **Magisk 的 root 授权提示**。特征是：

```
I zxh_toast: display.getHeight()=1792
W NotificationService: Toast already killed. pkg=com.topjohnwu.magisk
```

窗口属 `com.topjohnwu.magisk`，**每次 App 请求 su 就弹一条**。

### 关掉（per-uid，即时生效，不用重启 magiskd）

```bash
/data/adb/magisk/magisk --sqlite "SELECT uid,policy,notification,until FROM policies"
/data/adb/magisk/magisk --sqlite "UPDATE policies SET notification=0 WHERE uid=<该App的uid>"
```

> ⚠️ `magisk` **不在 `PATH` 里**（直接敲会报 `inaccessible or not found`），用绝对路径。

### ⚠️ 排查必坑：别把账算到自己头上

**你自己从 SSH 跑 `su` 命令也会弹** —— 那是 SSH 服务端的 uid（比如 SimpleSSHD 的 10111）。
典型误判现场：**toast 的时间戳比 App 启动还早**。

干净的归因实验（一次定性）：

```bash
M=/data/adb/magisk/magisk
$M --sqlite "UPDATE policies SET notification=0 WHERE uid=2000"     # shell / adb
$M --sqlite "UPDATE policies SET notification=0 WHERE uid=10111"    # SSH 服务端
sleep 5
T=$(date "+%m-%d %H:%M:%S.000")
input motionevent DOWN <按钮x> <按钮y>; sleep 0.3; input motionevent UP <按钮x> <按钮y>
sleep 4
logcat -d -T "$T" | grep -c zxh_toast      # 0 ⇒ 被测 App 清白
# 完事记得把 2000 / 10111 改回 1
```

先查 uid：`dumpsys package <包名> | grep userId`

---

## 四、装机 / 调试的杂坑

### `pm install` 读不了 `/sdcard`

```bash
pm install -r /sdcard/x.apk                  # ❌ SELinux 拦
su -c "cp /sdcard/x.apk /data/local/tmp/ && pm install -r /data/local/tmp/x.apk"   # ✅
```

### `scp` 写不进 `/data/local/tmp`

那是 `731 shell:shell`。只能**先传 `/sdcard/`，再在设备上 `su cp` 过去**。

### `su` 不在 PATH 里

本机（RK3566 / Android 11）`su` 只在 `/system_ext/bin/su` 和 `/debug_ramdisk/su`。
脚本里写相对名 `su` 会失败，要写绝对路径。

### `logcat -c` 清不掉 system buffer

会报 `failed to clear the 'system' log`，结果读到一堆旧记录误导判断。改用时间戳基线：

```bash
T=$(date "+%m-%d %H:%M:%S.000")
# ...做你要观察的操作...
logcat -d -T "$T" | grep 关键词
```

### `ps -A` 默认列的是进程名，不是命令行

用 pid 文件判断"进程还在不在"时，要显式指定列：

```bash
ps -A -o PID,ARGS | grep xxx      # ✅
ps -A | grep xxx                  # ❌ 只能匹配进程名
```

### `dumpsys wifi` 给的 SSID 是错的

它会混进扫描结果。要拿当前连着的那个：

```bash
dumpsys connectivity | grep -o 'SSID: "[^"]*"' | head -1     # ✅
```

### `iptables -D` 的规则串必须完整

删规则时 `--to-destination` 后面**要带地址**，否则匹配不上、**静默失败**，
规则只会越叠越多：

```bash
iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination $GW   # ✅
iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT                        # ❌ 静默失败
```

---

## 五、网络守护：换网后上不了外网

### 症状

在 A 网（配了旁路由）能上外网，换到 B 网再换回来，就上不了 Google 了。
查默认路由，发现被刷回了主路由。

### 原因 1：reconcile 的触发条件写错了（★ 致命）

原来的写法是「**想要的配置变了**才重新应用」：

```sh
# ❌ 错的
if [ "$want" != "$last_want" ]; then apply; fi
```

问题：只要 SSID 还是 A，`want` 就永远是同一个值 ——
所以 WiFi 重连把默认路由刷回主路由之后，脚本**永远不会再改回去**。

正确写法是看「**实际状态** ≠ 我们设过的状态」：

```sh
# ✅ 对的：幂等
cur=$(ip route show default table $DEV | awk 'NR==1{print $3}')
[ "$cur" != "$APPLIED" ] && reconcile
# DNS 规则被清掉也要补回来（-C 只查不改，很轻）
iptables -t nat -C OUTPUT -p udp --dport 53 -j DNAT --to-destination "$APPLIED" || reconcile
```

### 原因 2：守护进程被杀，没人拉起来

"一键清理"会把守护带走。所以必须配一个监管（`scripts/net-supervisor.sh`），
装进 `/data/adb/service.d/`，每 60 秒查一次 pid。

### 附带：为什么不用 WiFi 静态配置

Android 11 的 `cmd wifi` 不支持配静态 IP；而且静态配置**管不到 IPv6 的 DNS**
（那条是路由器 RA 通告的），所以无论如何都得靠运行时规则。

## 六、「蓝牙显示连着，但按翻页器没反应」

这是**跟第一节完全不同**的另一种故障：症状很像，但根因在蓝牙栈，不在无障碍。

### 症状

- `Fix Bluetooth` 点下去**看起来是好的**（提示 `Bluetooth on · pager connected`）
- 但按键**一条都不进来** —— 翻页器像死了一样
- 隔几分钟（或按几下翻页器）它自己又好了

### 根因：HID 状态的「假在线」

`BluetoothProfile.HID_HOST` 报的 `STATE_CONNECTED`（= 2）**并不代表链路真的活着**。
底层 BLE 链路僵死时，状态不会立刻更新：

```
$ dumpsys bluetooth_manager | grep -A2 mInputDevices
  mInputDevices:
    AA:BB:CC:DD:EE:FF : 2        ← 照样是 2（CONNECTED），但按键进不来
```

系统要等到 HCI 层超时才会拆掉 HID：

```
bta_gattc_conn_cback ... reason=0x0008      # 0x0008 = Connection Timeout
btif_hh_upstreams_evt: name = ATG-SJL       # 输入设备这一刻才真正建立
```

所以老版本里这一句是错的 —— 它在这个分支**直接早退，蓝牙侧一个动作都不做**：

```java
if (st == STATE_CONNECTED) return "Bluetooth on · pager connected";   // ❌
```

### 现在的处理（v18.2 / v18.3）

状态报 CONNECTED 时**不再早退**：

| 点击情况 | 行为 | 结果文案 |
|---|---|---|
| 第 1 次 | 发一次温和重连请求 | `Bluetooth on · pager connected (if it won't page, press a pager key)` |
| 60 秒内第 2 次、状态仍是 CONNECTED | **强制重建链路**（`svc bluetooth disable/enable`） | `Bluetooth restarted · pager connected`，或 `Bluetooth on · pager offline (press a pager key to wake it)` |

### ⚠️ 三个坑

1. **两次点击的实际间隔要 12~15 秒。**
   `Fix Bluetooth` 全流程包含无障碍修复的 `sleep 3 + sleep 3`，实测约 **9 秒**；
   而 `lastFixPressAt` 是走到蓝牙分支（≈点击后第 9 秒）才置位的。
   间隔 < 9 秒时第 2 次点击会被 `sBusy` **静默丢弃**
   （日志里连一条记录都没有，很容易误判成"新逻辑没生效"）。

2. **强制重建之后翻页器不会自己回来，要按一下它身上的键。**
   BLE HID 从机被主机 `disconnect` 之后**不会主动广播**，这是蓝牙规范行为，不是 bug。
   面板提示文案已如实写成 `(press a pager key to wake it)`。

3. **`/proc/bus/input/devices` 判不了"假在线"。**
   它只能判"链路真的挂了"（`ATG-SJL` 节点消失）。假在线时旧的 input 节点**还在**
   （要等 `bta_hh_co_close` 才拆），所以"节点在 = 链路活"不成立。

### 怎么取到这个证据（本机 logd 只有 64 KiB/缓冲区）

```sh
# ⚠️ 非 root 跑 logcat 只能看到自己进程的日志，必须 su
su -c "logcat -d -b all -v time > /sdcard/lg.txt"
# ⚠️ -b all 把多个 buffer 串进同一个文件，行号不连续 → 按时间戳重新排
sort -k2 /sdcard/lg.txt
```

高峰期主缓冲区只保留约 **5 分钟**，要取证就得**当场抓**。

---

## 七、面板里按翻页器没反应，切到微信读书却是好的

**症状**：PTBridge 面板里的「Page Turner Test」区，无论怎么按翻页器都不动；
但一退出面板、回到微信读书，翻页立刻正常。蓝牙显示连着、翻页器本身也是好的。

**根因：同 UID 的全屏焦点窗口会"吃掉"自己 overlay 的触摸。**

PTBridge 有两条接收翻页器触摸的通道：

| 通道 | 窗口类型 | mBaseLayer |
|---|---|---|
| `band`（y928~944 的隐形细带） | `TYPE_APPLICATION_OVERLAY` | **121000** |
| 面板 `MainActivity` | `BASE_APPLICATION` | **21000** |

看起来 band 层号高出 10 万，触摸"应该"落到 band 上 —— **但层号高不等于能接到触摸**。
当**同一个 UID** 既持有全屏焦点窗口（面板）又挂着 overlay 时，系统给 overlay 派发触摸前
会先判断"这块区域是不是被同 UID 的焦点窗口覆盖"。面板是全屏**不透明白底**，
16px 细带完全落在面板内容里 → **触摸被面板自己吃掉，压根到不了 band**。

**实证**（清空 logcat 缓冲后重抓）：

```
# 面板在前台
20:54:20.103 I/PTBridge: test mode ON (panel foreground)
             ↓ 之后只有 su / BT batt / self-heal，一条 TOUCH 都没有

# 面板一销毁，立刻恢复
20:53:35.096 I/wm_destroy_activity: [.., com.didi.pageturner/.MainActivity, finish-imm:idle]
20:53:38.618 I/PTBridge: GEST DOWN x0=534.3896 y0=7.0649414
20:53:38.675 I/PTBridge: ATG tap #1 at x=1404.0 ...  ✅
```

**修法（v20.1 起）：不要依赖 band 去接面板上的触摸 —— 让面板自己接。**

在 `MainActivity.dispatchTouchEvent()` 里判别"虚拟设备"
（`dev=[Virtual]` 或 `deviceId < 0`）的事件，直接喂给新增的
`PageTurnerService.handleTouchFromPanel(ev)`；真人手指照常走正常流程，按钮点击不受影响。

```java
@Override
public boolean dispatchTouchEvent(MotionEvent ev) {
    if (PageTurnerService.sConnected) {
        PageTurnerService svc = PageTurnerService.sInstance;   // 同进程，直接拿实例
        if (svc != null && svc.handleTouchFromPanel(ev)) {
            return true;   // 翻页器的触摸已被消费，不再往下传（否则会误触按钮）
        }
    }
    return super.dispatchTouchEvent(ev);
}
```

`band` 保持 16px 原样不动 —— 微信读书里的行为**完全不变**。

### ⚠️ 三个坑

1. **加高 band 解决不了这个问题。**
   band 的 `top` 恒在 928，加高只会向下延伸、盖住面板里的按钮，
   而翻页器的触摸也到不了高处 —— 方向完全错了。

2. **`localY` 在两处不一样，但不影响逻辑。**
   经 band 进来时是相对 band 的局部坐标（`localY=7.06`）；
   经面板进来时面板是全屏，局部坐标正好等于屏幕坐标（`localY=935.06`）。
   `onPageTurnerTap()` 只用 `dx`（位移差）和时长，**与坐标原点无关**，两边都对。

3. **翻页器的"长按"发的不是触摸长按，是蓝牙音量键。**
   实测日志为 `key 25 KEYCODE_VOLUME_DOWN from=ATG-SJL Consumer Control`，
   走的是 `onKeyEvent()` 那条快路，跟 `handleTouch()` 里的长按分支是两回事。
   `onKeyEvent()` 返回 `true` 已消费该键，所以**不会改变系统音量**。
