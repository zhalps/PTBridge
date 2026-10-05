# 构建与部署

## 为什么不用 Gradle

这个项目只有一个 Activity 加一个 Service，总共 6 个 `.java`。为了它拉一整套 Gradle +
Android Studio 不划算 —— 用 SDK 自带的几个命令行工具直连，构建只要 **1 秒**，
而且产物是什么、签名怎么加的，每一步都看得见。

流程就是 Android 打包最原始的那条链：

```
javac  →  d8  →  aapt package  →  aapt add  →  zipalign  →  apksigner
 .java    .class   .dex          素材资源      打成一个    对齐       签名
                                 .apk(无dex)   .apk
```

---

## 1. 准备工具链

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | 只需 `javac` + `java` |
| Android SDK | platform **android-30** | 提供 `android.jar`（编译期用） |
| build-tools | **34.0.0** | `aapt` / `zipalign` / `lib/apksigner.jar` |
| r8 | cmdline-tools 里的 `lib/r8.jar` | 提供 `com.android.tools.r8.D8` |

SDK 用 sdkmanager 装：

```bash
sdkmanager "platforms;android-30" "build-tools;34.0.0" "cmdline-tools;latest"
```

## 2. 配置

```bash
cd app
cp build.env.example build.env
```

编辑 `build.env`：

```sh
ANDROID_SDK="/c/Android/sdk"
JDK17="/c/Program Files/Java/jdk-17"
KEYSTORE="/path/to/your-release.keystore"
KS_ALIAS="release"
KS_PASS="你的密码"
KEY_PASS="$KS_PASS"
```

`build.env` 已在 `.gitignore` 里，**不会被提交**。

## 3. 构建

```bash
bash build.sh
```

产物 `app/pageturner.apk`。脚本末尾会自己跑一遍 `apksigner verify`。

> **Windows / Git Bash 提示**：脚本会自动给可执行文件补 `.exe`，
> 用 `pick()` 函数探测，不用手改。

---

## 4. 关于签名（最重要的坑）

### 必须沿用同一把 keystore

Android 按**签名**判定"是不是同一个 App"。换钥匙的后果：
**覆盖安装会被拒**，只能卸载重装 —— 那意味着无障碍授权、Magisk 授权、所有设置全要重配。

所以 `app/build.env` 里指的 keystore **一旦定了就别换**。

### 还没有 keystore 的话

```bash
keytool -genkeypair -v \
  -keystore release.keystore -storetype PKCS12 \
  -alias release -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Your Name, OU=dev, O=dev, L=City, ST=State, C=CN"
```

### 查当前 APK 是谁签的

```bash
java -jar "$BUILD_TOOLS/lib/apksigner.jar" verify --print-certs pageturner.apk
```

> ⚠️ **Git Bash 下 `apksigner.bat` 会静默无输出**（不是没执行，是它的输出丢了）。
> 要么按上面那样显式 `java -jar lib/apksigner.jar`，要么去 CMD 里跑。

### 覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

签名不一致。查两边指纹：

```bash
# 已装的
adb shell dumpsys package com.didi.pageturner | grep -i signatures -A2
# 要装的
java -jar lib/apksigner.jar verify --print-certs 新的.apk
```

---

## 5. 装机

设备上**不需要 adb**，SSH 就够（配合 SimpleSSHD 之类的 root SSH 服务端）。
电脑侧：

```bash
scp -P 2222 pageturner.apk user@192.168.x.x:/sdcard/
ssh -p 2222 user@192.168.x.x
```

设备侧：

```bash
su -c "sh /sdcard/install-apk.sh /sdcard/pageturner.apk"
```

`install-apk.sh` 会把 APK 复制到 `/data/local/tmp` 再 `pm install -r`。

> ⚠️ **为什么要多这一步**：
> 1. **`pm install` 读不了 `/sdcard`**（SELinux 限制）—— 必须放到 `/data/local/tmp`。
> 2. **`scp` 也写不进 `/data/local/tmp`**（权限 731 `shell:shell`）——
>    所以只能"先传 `/sdcard`，再 `su cp` 过去"。

### 手动走一遍的话

```bash
su -c "cp /sdcard/pageturner.apk /data/local/tmp/ && pm install -r /data/local/tmp/pageturner.apk"
```

### 装完必做的一步

**打开一次 PTBridge 面板。** `pm install -r` 会杀掉进程，
AMS 会把无障碍服务记进 `Crashed services` 黑名单，此后自动重绑会被跳过。
面板一打开会自愈（见 [troubleshooting.md](troubleshooting.md#一蓝牙连着但按键映射没了)）。

---

## 6. 首次授权

装好后需要两项授权，都不用重装：

1. **无障碍**：设置 → 无障碍 → 已下载的服务 → 打开 PTBridge
   （Settings → Accessibility → Downloaded apps）
2. **Magisk root**：打开面板点一次 `Fix Bluetooth`，Magisk 会弹授权框 → 允许

Magisk 的授权可以顺手把提示关掉（每次 su 都弹一条黑条会烦）：

```bash
# ⚠️ magisk 不在 PATH 里，要用绝对路径
/data/adb/magisk/magisk --sqlite "UPDATE policies SET notification=0 WHERE uid=<PTBridge 的 uid>"
```

查 uid：`dumpsys package com.didi.pageturner | grep userId`

---

## 7. 换机型要改什么

| 要改的 | 位置 | 怎么定 |
|---|---|---|
| 包名 | 全项目 `com.didi.pageturner` | 换成你自己的，注意 Manifest 与目录结构一起改 |
| 浮窗细带 Y 坐标 | `PageTurnerService.BAND_TOP` | 实测翻页器触摸的 Y 值（见 architecture.md） |
| 滑动参数 | `PageTurnerService.swipe()` 调用处 | y 值、时长要按目标 App 试 |
| 按键识别 | `handleTouch()` 里的 `name.contains("Virtual")` | `logcat -s PTBridge` 看 `TOUCH ... dev=[...]` |
| 长按键码 | `onKeyEvent()` 里的 `KEYCODE_VOLUME_DOWN` | 同上，看 `key <code>` 日志 |
| 网关显示前缀 | `MainActivity.GW_PREFIX` | 改成你网段的前三段，要和脚本里的 `GW_PREFIX` 一致 |
| 旁路由地址 | `scripts/net-autoswitch.sh` 顶部的 `GW_PREFIX` / `GWS` | 改成你自己的网段与旁路由地址 |
| 微信读书包名 | `PageTurnerService.WEREAD_PKG` | 一般不用改 |

调这些的时候 `logcat` 是最好的工具 —— 服务把**每一个**触摸和按键都无条件记下来了：

```bash
logcat -s PTBridge
```

---

## 8. 一键排查脚本

```bash
# 看服务有没有被系统绑上
dumpsys accessibility | grep -A3 "Bound services"
# 看有没有进崩溃黑名单
dumpsys accessibility | grep -A3 "Crashed services"
# 看版本
dumpsys package com.didi.pageturner | grep -i versionName
```
