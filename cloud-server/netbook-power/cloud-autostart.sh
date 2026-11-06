#!/bin/sh
# Run at boot by /etc/cron.d/cloud-autostart (@reboot): starts the server containers once Docker is up,
# unless the battery is low without mains power (then battery-guard would stop them again at once).
# Usage: cloud-autostart <compose dir>
set -eu

COMPOSE_DIR="$1"
DOCKER_WAIT_ATTEMPTS=60
DOCKER_WAIT_INTERVAL_SECONDS=5
DOCKER="${DOCKER:-docker}"
BATTERY_GUARD="${BATTERY_GUARD:-/usr/local/sbin/battery-guard}"

if ! "$BATTERY_GUARD" --check; then
    logger -t cloud-autostart "Battery low without mains, leaving the servers stopped"
    exit 0
fi

attempt=0
until $DOCKER info > /dev/null 2>&1; do
    # sysvinit/runit may not have started the daemon (yet); asking twice is harmless.
    [ "$attempt" -eq 0 ] && service docker start > /dev/null 2>&1 || true
    attempt=$((attempt + 1))
    if [ "$attempt" -ge "$DOCKER_WAIT_ATTEMPTS" ]; then
        logger -t cloud-autostart "Docker did not start, servers not started"
        exit 1
    fi
    sleep "$DOCKER_WAIT_INTERVAL_SECONDS"
done

cd "$COMPOSE_DIR"
docker-compose up -d
logger -t cloud-autostart "Servers started"
