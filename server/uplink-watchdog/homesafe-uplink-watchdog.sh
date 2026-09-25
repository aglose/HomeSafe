#!/bin/bash
# HomeSafe uplink watchdog: keeps the box talking to the home router, whichever way it is connected.
#
# History. On Wi-Fi (wlo1), the box twice got stuck "associated" with no traffic (2026-09-24: the
# Deco dropped it and never told it; a reassociate fixed it in a second), and after a reboot it
# couldn't finish authenticating to a far node at all. Since 2026-09-25 the camera switch is
# uplinked to the Deco, so eno2 carries the home network (DHCP) as well as the cameras (static
# 192.168.1.250), and Wi-Fi is the spare.
#
# Every minute (homesafe-uplink-watchdog.timer) it pings the default route's gateway 3 times; the
# check fails only when all 3 do. By consecutive failed checks:
#   uplink on wlo1:   2 reassociate · 4 ifdown/ifup wlo1 · 7 reload iwlwifi · then ifdown/ifup every 10
#   uplink on eno2 (or no default route at all):
#                     2 ask dhcpcd to rebind eno2 (no link drop: the cameras share the port)
#                     4 bring wlo1 up as a spare route · then both again every 10
# It never takes eno2 down and never reboots: recording carries on without a network.
#   journalctl -t homesafe-uplink-watchdog
set -u
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
WIFI=wlo1
WIRED=eno2
TAG=homesafe-uplink-watchdog
STATE=/run/homesafe-uplink-watchdog.fails

route=$(ip -4 route show default | head -1)
gw=$(awk '{print $3}' <<<"$route")
dev=$(awk '{for (i = 1; i < NF; i++) if ($i == "dev") print $(i + 1)}' <<<"$route")
gw=${gw:-192.168.68.1}
fails=$(cat "$STATE" 2>/dev/null || echo 0)

if ping -c 3 -W 2 -q "$gw" >/dev/null 2>&1; then
    [ "$fails" -gt 0 ] && logger -t "$TAG" "router $gw reachable again via $dev after $fails failed check(s)"
    echo 0 > "$STATE"
    exit 0
fi

fails=$((fails + 1))
echo "$fails" > "$STATE"

wifi_restart() {
    ifdown --force "$WIFI" >/dev/null 2>&1
    sleep 2
    ifup "$WIFI" >/dev/null 2>&1
}

if [ "$dev" = "$WIFI" ]; then
    link=$(wpa_cli -i "$WIFI" status 2>/dev/null | grep -E '^(wpa_state|bssid|freq)=' | tr '\n' ' ')
    signal=$(iw dev "$WIFI" link 2>/dev/null | awk '/signal/ {print $2 " dBm"}')
    logger -t "$TAG" "router $gw unreachable via $WIFI (check $fails): ${link}signal=${signal:-none}"
    if [ "$fails" -eq 2 ]; then
        logger -t "$TAG" "reassociating $WIFI"
        wpa_cli -i "$WIFI" reassociate >/dev/null 2>&1
    elif [ "$fails" -eq 4 ]; then
        logger -t "$TAG" "restarting $WIFI"
        wifi_restart
    elif [ "$fails" -eq 7 ]; then
        logger -t "$TAG" "reloading the iwlwifi driver"
        ifdown --force "$WIFI" >/dev/null 2>&1
        modprobe -r iwlmvm iwlwifi && sleep 3 && modprobe iwlwifi
        sleep 5
        ifup "$WIFI" >/dev/null 2>&1
    elif [ "$fails" -gt 7 ] && [ $((fails % 10)) -eq 0 ]; then
        logger -t "$TAG" "restarting $WIFI (still down after $fails checks)"
        wifi_restart
    fi
else
    carrier=$(cat "/sys/class/net/$WIRED/carrier" 2>/dev/null || echo "?")
    logger -t "$TAG" "router $gw unreachable via ${dev:-no default route} (check $fails): $WIRED carrier=$carrier"
    if [ "$fails" -eq 2 ] || { [ "$fails" -gt 4 ] && [ $((fails % 10)) -eq 0 ]; }; then
        logger -t "$TAG" "asking dhcpcd to rebind $WIRED"
        dhcpcd -n "$WIRED" >/dev/null 2>&1
    fi
    if [ "$fails" -eq 4 ] || { [ "$fails" -gt 4 ] && [ $((fails % 10)) -eq 0 ]; }; then
        if [ "$(cat /sys/class/net/$WIFI/operstate 2>/dev/null)" != "up" ]; then
            logger -t "$TAG" "bringing $WIFI up as a spare route"
            ifup "$WIFI" >/dev/null 2>&1
        else
            logger -t "$TAG" "restarting $WIFI (the spare route)"
            wifi_restart
        fi
    fi
fi
