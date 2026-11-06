#!/bin/sh
# Run every minute by /etc/cron.d/battery-guard: stops the server containers cleanly when the battery
# drops below MIN_BATTERY_PERCENT without mains power. The netbook itself stays on.
# With --check it only answers: exit 0 when the power supply is fine, 1 when it is low (used by cloud-autostart).
set -eu

MIN_BATTERY_PERCENT=10
MAINS_ONLINE=1
DB_STOP_TIMEOUT_SECONDS=60
SERVER_CONTAINER=ticwatch-cloud_cloud_1
DB_CONTAINER=ticwatch-cloud_db_1

AC_ONLINE_PATH="${AC_ONLINE_PATH:-/sys/class/power_supply/AC0/online}"
BATTERY_CAPACITY_PATH="${BATTERY_CAPACITY_PATH:-/sys/class/power_supply/BAT0/capacity}"
DOCKER="${DOCKER:-docker}"

# An unreadable supply counts as fine: never stop the servers on a guess.
ac_online=$(cat "$AC_ONLINE_PATH" 2>/dev/null || echo "$MAINS_ONLINE")
capacity=$(cat "$BATTERY_CAPACITY_PATH" 2>/dev/null || echo "$MIN_BATTERY_PERCENT")

if [ "$ac_online" = "$MAINS_ONLINE" ] || [ "$capacity" -ge "$MIN_BATTERY_PERCENT" ]; then
    exit 0
fi

if [ "${1:-}" = "--check" ]; then
    exit 1
fi

if [ -z "$($DOCKER ps -q --filter "name=^$DB_CONTAINER\$")" ]; then
    exit 0
fi

logger -t battery-guard "Battery at $capacity%, stopping the servers"
# The server first, so Postgres has no clients left when it shuts down.
$DOCKER stop "$SERVER_CONTAINER"
$DOCKER stop -t "$DB_STOP_TIMEOUT_SECONDS" "$DB_CONTAINER"
