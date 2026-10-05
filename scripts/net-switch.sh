#!/system/bin/sh
# ===========================================================================
# net-switch.sh —— 手动把「默认网关 + DNS」临时切到旁路由
#
# 用法（设备上，root）：
#   su -c "sh net-switch.sh apply 2"     # 默认路由 + DNS 都指向 <网段>.2
#   su -c "sh net-switch.sh status"
#   su -c "sh net-switch.sh restore"     # 还原成主路由
#
# 和「按 SSID 自动切」的 net-autoswitch.sh 是一套东西，区别只是这个要手点。
#
# 原理：
#   1) ip route replace 默认路由 -> 旁路由（per-network 表 <DEV> 和 main 表都要改）
#   2) iptables -t nat 把**所有**出站 53 端口(udp+tcp) DNAT 到旁路由
#      —— 不挑目标地址，因为设备实际在用的 DNS 是谁不好保证
#   3) 设备 DNS 列表里常有一条路由器 RA 通告的 IPv6 链路本地地址，
#      而本机内核没有 ip6tables 的 nat 表 → 没法重定向，
#      只能把 IPv6 的 53 直接 REJECT 掉，逼解析器回落到 IPv4
#   4) 清一下 netd 的 DNS 缓存
#
# ⚠️ 不改 WiFi 配置库：重启 / 重连 WiFi 后失效，重跑本脚本即可。
# ===========================================================================

# ===== 配置（按你的网络改）=================================================
DEV=wlan0                     # 无线接口名，多数机型就是 wlan0
GW_PREFIX="192.168.1."        # 旁路由所在网段（前三段 + 点）
MAIN_GW="192.168.1.1"         # 主路由，restore 时指回它
GWS="192.168.1.2 192.168.1.3" # 所有可能用到的旁路由（清规则时逐个删）
# ===========================================================================

# ⚠️ 坑：iptables -D 的规则串必须**完整**（--to-destination 后面要带地址），
#    否则匹配不上、静默失败，规则只会越叠越多。
clear_rules() {
    for G in $GWS; do
        while iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination $G 2>/dev/null; do :; done
        while iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination $G 2>/dev/null; do :; done
    done
    for i in 1 2; do
        ip6tables -D OUTPUT -p udp --dport 53 -j REJECT 2>/dev/null
        ip6tables -D OUTPUT -p tcp --dport 53 -j REJECT 2>/dev/null
    done
}

do_apply() {
    [ -n "$1" ] || { echo "用法: sh $0 apply <最后一段，如 2>"; exit 1; }
    GW="$GW_PREFIX$1"
    echo ">>> 切到 $GW"

    # 1) 默认路由
    ip route replace default via $GW dev $DEV table $DEV
    ip route replace default via $GW dev $DEV

    # 2) IPv4 DNS 全量重定向
    clear_rules
    iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination $GW
    iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination $GW

    # 3) 掐掉 IPv6 DNS（没有 nat 表，只能拒）
    ip6tables -A OUTPUT -p udp --dport 53 -j REJECT
    ip6tables -A OUTPUT -p tcp --dport 53 -j REJECT

    # 4) 清 DNS 缓存
    ndc resolver flushdefaultif        2>/dev/null
    ndc resolver clearnetdns 100       2>/dev/null
    ndc resolver clearnetdns 0         2>/dev/null

    do_status
}

do_status() {
    echo
    echo "=== 默认路由 ==="
    ip route show table $DEV | grep default
    ip route show table main  | grep default
    echo "=== IPv4 DNS 重定向 ==="
    iptables -t nat -S OUTPUT | grep 53
    echo "=== IPv6 DNS 阻断 ==="
    ip6tables -S OUTPUT | grep 53
    echo "=== 当前 SSID ==="
    # ⚠️ 必须用 dumpsys connectivity；dumpsys wifi 会混进扫描结果，返回过错的 SSID
    dumpsys connectivity 2>/dev/null | grep -o 'SSID: "[^"]*"' | head -1
}

do_restore() {
    echo ">>> 还原到主路由 $MAIN_GW"
    ip route replace default via $MAIN_GW dev $DEV table $DEV
    ip route replace default via $MAIN_GW dev $DEV
    clear_rules
    ndc resolver flushdefaultif 2>/dev/null
    do_status
}

case "$1" in
    apply)   do_apply "$2" ;;
    status)  do_status ;;
    restore) do_restore ;;
    *)       echo "用法: sh $0 apply <最后一段> | status | restore" ;;
esac
