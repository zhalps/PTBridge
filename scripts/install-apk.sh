#!/system/bin/sh
# ===========================================================================
# install-apk.sh —— 在设备本机上安装 / 覆盖安装 APK（SSH 里用，不需要 adb）
#
# 用法（设备上，root）：
#   su -c "sh install-apk.sh /sdcard/pageturner.apk"
#
# ⚠️ 两个必踩的坑：
#   1) **`pm install` 读不了 /sdcard**（SELinux 限制）—— 必须先 cp 到 /data/local/tmp。
#   2) **`scp` 也不能直接写 /data/local/tmp**（权限 731 shell:shell）——
#      所以电脑侧要「先传到 /sdcard/，再在设备上 su cp 过去」。
#
# ⚠️ 重装会影响无障碍服务：`pm install -r` 会杀掉进程，AMS 把
#    PageTurnerService 记进 Crashed services 黑名单，之后 re-bind 会被跳过。
#    **装完记得打开一次 PTBridge 面板**，它会自动自愈（见 docs/troubleshooting.md）。
# ===========================================================================

APK="${1:-/sdcard/pageturner.apk}"
TMP=/data/local/tmp/_ptbridge_install.apk

[ -f "$APK" ] || { echo "✗ 找不到 APK: $APK"; exit 1; }

echo ">>> 暂存到 $TMP"
cp "$APK" "$TMP" || { echo "✗ 复制失败（需要 root）"; exit 1; }

echo ">>> pm install -r"
pm install -r "$TMP"
rc=$?
rm -f "$TMP"

if [ $rc -eq 0 ]; then
    echo "✓ 安装完成 —— 请打开一次 PTBridge 面板让无障碍服务重新绑定"
else
    echo "✗ 安装失败 (rc=$rc)。常见原因："
    echo "   · 签名不一致 —— 覆盖安装必须用同一把 keystore"
    echo "   · versionCode 没提高"
fi
exit $rc
