#!/system/bin/sh
# ===========================================================================
# net-supervisor.sh —— 保证 net-autoswitch.sh 一直活着
#
# 为什么需要它：
#   实测守护进程会被系统 / 清理工具干掉（比如「一键清理」）。
#   进程一死就没人再把默认路由改回旁路由了 → 换网后直接失联。
#   本脚本每 60 秒检查一次 pid，不在就用 setsid 重新拉起。
#
# 安装：放进 /data/adb/service.d/（Magisk 开机自动执行，需要可执行权限）
#   彻底复原 = 删掉本文件 + /data/adb/duo2-net.sh
# ===========================================================================

DAEMON=/data/adb/duo2-net.sh       # 即 net-autoswitch.sh 装好后的路径
PIDF=/data/adb/duo2-net.pid
SUPLOG=/data/adb/duo2-net-sup.log

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$SUPLOG"; }

# —— 单实例守卫：已经有监管在跑就直接退出（PTBridge 会重复调本脚本）——
SUPPID=/data/adb/duo2-net-sup.pid
old=$(cat "$SUPPID" 2>/dev/null)
if [ -n "$old" ] && [ -d "/proc/$old" ]; then
    case "$(cat /proc/$old/cmdline 2>/dev/null | tr '\0' ' ')" in
        *service.d/duo2-net*)
            exit 0
            ;;
    esac
fi
echo $$ > "$SUPPID"

# 被 App 拉起时继承的 oom_score_adj 可能很高，自己提到系统级保护
adj=$(cat /proc/self/oom_score_adj 2>/dev/null)
if [ -n "$adj" ] && [ "$adj" -gt -900 ] 2>/dev/null; then
    echo -900 > /proc/self/oom_score_adj 2>/dev/null
    log "oom_score_adj $adj -> -900"
fi

sleep 15

log "监管启动 (pid $$)"

while true; do
    pid=$(cat "$PIDF" 2>/dev/null)
    alive=0
    if [ -n "$pid" ] && [ -d "/proc/$pid" ]; then
        case "$(cat /proc/$pid/cmdline 2>/dev/null | tr '\0' ' ')" in
            *duo2-net*) alive=1 ;;
        esac
    fi
    if [ "$alive" = 0 ]; then
        log "daemon 未运行 (pid='$pid') -> 拉起"
        setsid sh "$DAEMON" >/dev/null 2>&1 &
    fi
    sleep 60
done
