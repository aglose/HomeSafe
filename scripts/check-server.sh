#!/bin/sh
# Checks whether the HomeSafe server answers on every address and port the app uses, and
# reports which route the app would pick. Run it from a machine on the house Wi-Fi (which
# exercises both routes) or from anywhere on the tailnet (which exercises the Tailscale one).
#
#   sh scripts/check-server.sh
#
# Exits 0 if at least one route would let the app sign in, 1 otherwise.

LAN_HOST=192.168.68.55          # keep in step with LOCAL_SERVER_URL in LocalNetworkConfig.kt
TS_HOST=debian-surveillance.tail4c441a.ts.net
API_PORT=8971                   # Frigate authenticated API — the port the app signs in against
GO2RTC_PORT=1984                # live streams, plain HTTP by design
RELAY_PORT=8787                 # push relay
TIMEOUT=5

lan_ok=0
ts_ok=0

# probe SCHEME HOST PORT PATH LABEL -> prints one result line, returns 0 when the port answers HTTP
probe() {
    scheme=$1 host=$2 port=$3 path=$4 label=$5
    out=$(curl -sk -o /dev/null -w '%{http_code} %{time_total}' \
          --max-time "$TIMEOUT" "$scheme://$host:$port$path" 2>/dev/null)
    rc=$?
    if [ "$rc" -eq 0 ]; then
        # 401/403 still prove the server is there and speaking Frigate; it just wants a login.
        printf '  %-34s HTTP %s in %ss\n' "$label" "${out%% *}" "${out#* }"
        return 0
    fi
    case $rc in
        6)  reason="DNS: host does not resolve" ;;
        7)  reason="connection refused / no route" ;;
        28) reason="timed out after ${TIMEOUT}s" ;;
        35) reason="TLS handshake failed (is this port plain HTTP?)" ;;
        52) reason="empty reply (wrong protocol for this port?)" ;;
        56) reason="connection reset" ;;
        60) reason="certificate not trusted" ;;
        *)  reason="curl exit $rc" ;;
    esac
    printf '  %-34s %s\n' "$label" "$reason"
    return 1
}

# tls_info HOST PORT -> says whether the cert is self-signed, which Android rejects
tls_info() {
    host=$1 port=$2
    if ! command -v openssl >/dev/null 2>&1; then
        printf '  %-34s (openssl not installed, skipped)\n' "cert on $port"
        return
    fi
    cert=$(echo | timeout "$TIMEOUT" openssl s_client -connect "$host:$port" 2>/dev/null)
    [ -z "$cert" ] && return
    subject=$(echo "$cert" | openssl x509 -noout -subject 2>/dev/null | sed 's/^subject= *//')
    issuer=$(echo "$cert" | openssl x509 -noout -issuer 2>/dev/null | sed 's/^issuer= *//')
    [ -z "$subject" ] && return
    if [ "$subject" = "$issuer" ]; then
        printf '  %-34s SELF-SIGNED (%s)\n' "cert on $port" "$subject"
        printf '  %-34s Android will refuse this unless the cert is trusted\n' ''
    else
        printf '  %-34s issued by %s\n' "cert on $port" "$issuer"
    fi
}

echo "LAN route  ($LAN_HOST)"
if probe http "$LAN_HOST" "$API_PORT" /api/version "http  :$API_PORT /api/version"; then
    lan_ok=1 lan_scheme=http
fi
if probe https "$LAN_HOST" "$API_PORT" /api/version "https :$API_PORT /api/version"; then
    lan_ok=1
    [ -z "$lan_scheme" ] && lan_scheme=https
    tls_info "$LAN_HOST" "$API_PORT"
fi
probe http "$LAN_HOST" "$GO2RTC_PORT" /api/streams "http  :$GO2RTC_PORT streams" || true
probe http "$LAN_HOST" "$RELAY_PORT" /health "http  :$RELAY_PORT /health" || true

echo
echo "Tailscale route ($TS_HOST)"
if probe https "$TS_HOST" "$API_PORT" /api/version "https :$API_PORT /api/version"; then
    ts_ok=1 ts_scheme=https
    tls_info "$TS_HOST" "$API_PORT"
fi
if probe http "$TS_HOST" "$API_PORT" /api/version "http  :$API_PORT /api/version"; then
    ts_ok=1
    [ -z "$ts_scheme" ] && ts_scheme=http
fi
probe http "$TS_HOST" "$RELAY_PORT" /health "http  :$RELAY_PORT /health" || true

echo
echo "Verdict"
if [ "$lan_ok" = 1 ]; then
    echo "  LAN answers over $lan_scheme."
    if [ "$lan_scheme" != http ]; then
        echo "  !! LOCAL_SERVER_URL is hardcoded http://$LAN_HOST:$API_PORT, so the app's LAN"
        echo "     probe fails and it falls back to Tailscale even while you are at home."
    fi
else
    echo "  LAN does not answer. Expected if you are not on the house Wi-Fi;"
    echo "  otherwise check the server is up and $API_PORT is open to the LAN, not just to Tailscale."
fi
if [ "$ts_ok" = 1 ]; then
    echo "  Tailscale answers over $ts_scheme."
else
    echo "  Tailscale does not answer. Check 'tailscale status' on both the server and the phone."
fi
if [ "$lan_ok" = 1 ] || [ "$ts_ok" = 1 ]; then
    echo "  => the server is reachable from this machine; the app should be able to sign in."
    exit 0
fi
echo "  => no route answered. The app cannot connect from this network."
exit 1
