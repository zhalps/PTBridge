# PTBridge

**把蓝牙翻页器的按键，翻译成阅读 App 认得的翻页动作。**

给墨水屏阅读器写的一个小无障碍服务：让一个便宜的蓝牙翻页器（自拍杆那一类 HID 遥控器）
在**微信读书墨水屏版**和 **KOReader** 里都能翻页，并附一个纯代码绘制的控制面板。

---

## 它解决什么问题

蓝牙翻页器发的是标准的 HID 按键（音量键 / 方向键一类）。这在 KOReader 里通常能直接用，
但在**微信读书墨水屏版**里往往失灵 —— 因为微信读书不等这些键，手势直接被漏给了系统。

PTBridge 用**无障碍服务**在中间截一层：

| 翻页器动作 | 转换成的动作 |
|---|---|
| 短按 | 下一页 |
| 双击 | 上一页 |
| 长按 | 全屏刷新（清残影） |

同时它把按键事件**吞掉**，不再漏给下层 App。

## 功能

- **按键桥接**：短按 / 双击 / 长按 → 下一页 / 上一页 / 全刷，两个阅读 App 通用
- **控制面板**（纯代码布局，无图片、无动画，墨水屏上最清晰）
  - `Fix Bluetooth` —— 一键自愈：修无障碍绑定 + 重连翻页器
  - `Restart WeRead` —— 微信读书吃下去的内存它自己吐不掉，直接重启
  - `Start / Stop SSH` —— 开关键，探端口决定行为
  - `BT Settings` / `Refresh` / `Close`
  - 左上角常显 `SSH` 状态与当前网关
- **无障碍自愈**：面板一打开就检查服务有没有真被系统绑上，没绑上就自动修
  （这是「蓝牙明明连着、按键却失灵」的真正病根，见 [排障文档](docs/troubleshooting.md)）
- **随网切换网关 / DNS**（可选）：按 SSID 自动把默认路由指到旁路由，带守护保活

## 环境要求

- **设备**：RK3566 墨水屏阅读器 / Android 11（API 30），屏幕 1404×1872。
  其他机型大概率也能用，但下面的坐标、包名、DPI 得自己调。
- **Root**：面板里的「修复蓝牙」「重启微信读书」「SSH 开关」需要 root（Magisk）。
  没有 root 也能装，但只有按键桥接那一部分能用。
- **JDK 17 + Android SDK**（build-tools 34 / platform 30 / cmdline-tools 里的 r8）。
  不需要 Gradle，也用不上 Android Studio。
- 可选：SimpleSSHD（`org.galexander.sshd`），用于面板上的 SSH 开关。

## 快速开始

```bash
# 1) 构建（改好 build.env 之后）
cd app
cp build.env.example build.env     # 填 SDK / JDK / 签名信息
bash build.sh                      # 产出 app/pageturner.apk

# 2) 装机（电脑侧）
scp -P 2222 pageturner.apk user@设备IP:/sdcard/
ssh -p 2222 user@设备IP
# 设备侧
su -c "sh /sdcard/install-apk.sh /sdcard/pageturner.apk"
```

装完**打开一次 PTBridge 面板**，让它把无障碍服务绑上。

首次使用还要在系统设置里允许 PTBridge 的**无障碍服务**，并允许 **Magisk** 给它 root。

> 工具链细节、签名注意事项、换机型怎么调，见 [docs/build-and-deploy.md](docs/build-and-deploy.md)。

## 仓库结构

```
PTBridge/
├── app/                      Android 应用（手工工具链，无 Gradle）
│   ├── build.sh              构建脚本：javac → d8 → aapt → zipalign → apksigner
│   ├── build.env.example     配置模板（真实配置写 build.env，已被 gitignore）
│   ├── AndroidManifest.xml
│   ├── res/values/strings.xml
│   ├── res/xml/pageturner_config.xml   无障碍服务声明
│   └── src/com/didi/pageturner/
│       ├── MainActivity.java        控制面板
│       ├── PageTurnerService.java   无障碍服务：按键桥接的核心
│       ├── BtHelper.java            蓝牙连接 / 重连 / 配对状态
│       ├── BtBattery.java           读翻页器电量
│       ├── RadioWakeReceiver.java   开机 / 解锁后自动把蓝牙拉起来
│       └── Su.java                  跑 root 命令的小封装
├── scripts/                  设备侧配套脚本
│   ├── install-apk.sh        设备本机安装（绕开 pm 读不了 /sdcard 的坑）
│   ├── net-switch.sh         手动切网关 + DNS 到旁路由
│   ├── net-autoswitch.sh     按 SSID 自动切（带幂等 reconcile）
│   └── net-supervisor.sh     守护保活（装在 /data/adb/service.d/）
└── docs/
    ├── architecture.md       工作原理
    ├── build-and-deploy.md   构建、签名与部署
    └── troubleshooting.md    ★ 踩坑手册（最值钱的一份）
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 无障碍服务怎么截按键、浮窗细带是什么、网关守护的两层结构 |
| [docs/build-and-deploy.md](docs/build-and-deploy.md) | 手工工具链、签名、装机、改包名 / 坐标 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | **建议先读**：无障碍崩溃黑名单、墨水屏不能做的几件事、Magisk 授权提示误判…… |

## 已知限制

- 墨水屏**面板级**的渲染行为（局部重绘时的瞬时反色）无法通过代码控制 ——
  ROM 没把这个能力开放给第三方。详见 troubleshooting 的「二、墨水屏能改的和改不了的」。
- 按键映射的键值和浮窗坐标是按 1404×1872 的机器量的，换机型要重新量。

## 说明

个人自用项目，按现状提供，没做通用化适配。刷机、root、改系统设置有风险，请自行判断。

代码里的中文注释保留了大量「当时为什么这么写」的现场记录 —— 那些踩过的坑才是这个仓库的主要价值。
