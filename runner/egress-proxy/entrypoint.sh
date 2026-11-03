#!/bin/sh
# Writes the proxy's config from ALLOWED_HOSTS and ALLOWED_PORTS, then runs it.
# With no hosts it refuses to start, so a mistake can never mean "allow everything".
set -eu

: "${ALLOWED_HOSTS:?ALLOWED_HOSTS is required}"
PORTS="${ALLOWED_PORTS:-443}"
FILTER=/tmp/filter
CONFIG=/tmp/tinyproxy.conf

: > "$FILTER"
for host in $(echo "$ALLOWED_HOSTS" | tr ',' ' '); do
    case "$host" in
        *[!A-Za-z0-9.-]*) echo "Invalid host: $host" >&2; exit 1 ;;
    esac
    # Matches the host and its subdomains, and nothing that merely ends in the same letters.
    echo "(^|\\.)$(echo "$host" | sed 's/\./\\./g')\$" >> "$FILTER"
done
[ -s "$FILTER" ] || { echo "ALLOWED_HOSTS is empty" >&2; exit 1; }

{
    echo "Port 3128"
    echo "Timeout 900"
    echo "MaxClients 200"
    echo "LogLevel Connect"
    echo "DisableViaHeader Yes"
    echo "XTinyproxy No"
    echo "FilterDefaultDeny Yes"
    echo "FilterURLs Off"
    echo "FilterType ere"
    echo "FilterCaseSensitive Off"
    echo "Filter \"$FILTER\""
    for port in $(echo "$PORTS" | tr ',' ' '); do
        case "$port" in
            *[!0-9]*) echo "Invalid port: $port" >&2; exit 1 ;;
        esac
        echo "ConnectPort $port"
    done
} > "$CONFIG"

exec tinyproxy -d -c "$CONFIG"
