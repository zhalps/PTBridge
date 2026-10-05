#!/system/bin/sh
# ===========================================================================
# net-autoswitch.sh —— 按当前 WiFi 自动把「默认网关 + DNS」指到对应的旁路由
#
#   由 device/net-supervisor.sh（装在 /data/adb/service.d/ 下）负责保活，
#   进程挂了会被重新拉起；PTBridge 面板打开时也会顺手拉一次（幂等）。
#
#   ⚠️ 注意：进程一死 → 默认路由就没人维护 → WiFi 一重连就被刷回主路由。
#      所以「保活」和「本脚本」是一对，别只装一个。
#
# 为什么不用「WiFi 静态配置」：
#   Android 11 的 `cmd wifi` 不支持配静态 IP，手点界面又费劲；而且静态配置
#   **管不到 IPv6 的 DNS**（那条是路由器 RA 通告的），所以无论如何都得靠运行时规则。
#
# 原理：
#   1) ip route replace 默认路由 -> 旁路由（per-network 表 + main 表都改）
#   2) iptables -t nat 把出站 53 端口(udp+tcp)全部 DNAT 到旁路由
#   3) 本机内核没有 ip6tables 的 nat 表 → IPv6 的 DNS 只能 REJECT，逼解析器回落 IPv4
#
# ⚠️ 两个踩过的坑（症状：换了网就上不了外网）
#   bug1【致命】重应用条件如果写成「想要的配置变了」：
#        只要 SSID 没变，"想要的"就永远一样，于是 WiFi 重连把默认路由刷回主路由之后，
#        脚本**永远不会再改回去**。本版改成「**实际网关** != 我们设过的网关」就重新
#        reconcile —— 幂等，随便刷。
#   bug2 守护进程被杀后没人拉起来 → 交给 service.d 里的 net-supervisor.sh。
# ===========================================================================

# ===== 配置（按你的网络改）=================================================
DEV=wlan0                     # 无线接口名
GW_PREFIX="192.168.3."        # 旁路由所在网段（前三段 + 点）
GWS="192.168.3.2 192.168.3.3" # 所有可能用到的旁路由（清规则时逐个删）

# SSID -> 旁路由最后一段。不在表里就返回空串 = 不干预
gw_for() {
    case "$1" in
        HomeWiFi)   echo 2  ;;   # 家里：走 192.168.3.2
        OfficeWiFi) echo 3  ;;   # 办公室：走 192.168.3.3
        *)          echo "" ;;   # 其它 SSID：撤规则，走直连
    esac
}
# ===========================================================================

LOG=/data/adb/duo2-net.log
STATE=/data/adb/duo2-net.state     # PTBridge 面板会读它显示当前网关
PIDF=/data/adb/duo2-net.pid

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; }

# 若本次是被普通 App（PTBridge）拉起来的，会继承很高的 oom_score_adj，
# 容易被「一键清理」顺手清掉 —— 自己把自己提到系统级保护。
# （由 service.d 拉起时本来就是 -1000，这里会跳过）
adj=$(cat /proc/self/oom_score_adj 2>/dev/null)
if [ -n "$adj" ] && [ "$adj" -gt -900 ] 2>/dev/null; then
    echo -900 > /proc/self/oom_score_adj 2>/dev/null
    log "oom_score_adj $adj -> -900"
fi

# —— 单实例守卫：pid 文件里的进程还活着就直接退出 ——
old=$(cat "$PIDF" 2>/dev/null)
if [ -n "$old" ] && [ -d "/proc/$old" ]; then
    case "$(cat /proc/$old/cmdline 2>/dev/null | tr '\0' ' ')" in
        *duo2-net*)
            log "已有实例 $old 在跑，本次退出"
            exit 0
            ;;
    esac
fi
echo $$ > "$PIDF"

cur_ssid() {
    # ⚠️ 必须用 dumpsys connectivity；dumpsys wifi 会混进扫描结果，SSID 是错的
    dumpsys connectivity 2>/dev/null | grep -o 'SSID: "[^"]*"' | head -1 \
        | sed 's/^SSID: "//; s/"$//'
}

clear_rules() {
    # ⚠️ 坑：iptables -D 的规则串必须**完整**（--to-destination 后面要带地址），
    #    否则匹配不上、静默失败，规则只会越叠越多。
    for G in $GWS; do
        while iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination $G 2>/dev/null; do :; done
        while iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination $G 2>/dev/null; do :; done
    done
    for i in 1 2; do
        ip6tables -D OUTPUT -p udp --dport 53 -j REJECT 2>/dev/null
        ip6tables -D OUTPUT -p tcp --dport 53 -j REJECT 2>/dev/null
    done
}

APPLIED=""      # 当前已经设好的旁路由 IP（空 = 没设过）

apply() {
    GW="$GW_PREFIX$1"
    ip route replace default via $GW dev $DEV table $DEV
    ip route replace default via $GW dev $DEV
    clear_rules
    iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination $GW
    iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination $GW
    ip6tables -A OUTPUT -p udp --dport 53 -j REJECT
    ip6tables -A OUTPUT -p tcp --dport 53 -j REJECT
    APPLIED=$GW
}

reconcile() {
    ssid=$(cur_ssid)
    want=$(gw_for "$ssid")
    if [ -n "$want" ]; then
        apply "$want"
        # 只写最后一段；面板（GW_PREFIX）负责补全成完整 IP
        echo "$ssid $want" > "$STATE"
        log "OK  网关+DNS -> $APPLIED (ssid=$ssid)"
    else
        clear_rules
        APPLIED=""
        echo "$ssid -" > "$STATE"
        log "SKIP ssid='$ssid' 不在名单里，DNS 规则已撤"
    fi
}

# 等 wlan0 起来
i=0
while [ $i -lt 60 ]; do
    ip -4 addr show $DEV 2>/dev/null | grep -q "inet " && break
    sleep 5
    i=$((i+1))
done

log "start (pid $$, wlan0 up after ${i}x5s)"

while true; do
    need=0
    cur=$(ip route show default table $DEV 2>/dev/null | awk 'NR==1{print $3}')
    if [ -z "$APPLIED" ]; then
        need=1                                  # 还没设过
    else
        [ "$cur" != "$APPLIED" ] && need=1      # 默认路由被人刷走了（WiFi 重连 / 重启 netd）
        # DNS 规则被清掉也要补回来（-C 只查不改，很轻）
        iptables -t nat -C OUTPUT -p udp --dport 53 -j DNAT --to-destination "$APPLIED" 2>/dev/null || need=1
    fi
    [ "$need" = 1 ] && reconcile
    sleep 30
done
