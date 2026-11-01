#!/usr/bin/env bash
# Builds the server image here (WSL) and runs it on the netbook. Idempotent; the database volume survives redeploys.
set -euo pipefail

NETBOOK_SSH=netbook
NETBOOK_HOST=192.168.1.81
TARGET_ARCH=386
REMOTE_DIR='~/ticwatch-cloud'
IMAGE=ticwatch-cloud-server:latest
PORT=8766
PING_PATH=/api/v1/ping
PING_ATTEMPTS=90
PING_INTERVAL_SECONDS=2

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "== Installing the power policy on $NETBOOK_SSH"
ssh "$NETBOOK_SSH" "sudo tee /usr/local/sbin/battery-guard > /dev/null && sudo chmod 755 /usr/local/sbin/battery-guard" < "$SOURCE_DIR/netbook-power/battery-guard.sh"
ssh "$NETBOOK_SSH" "sudo tee /etc/cron.d/battery-guard > /dev/null && sudo chmod 644 /etc/cron.d/battery-guard" < "$SOURCE_DIR/netbook-power/battery-guard.cron"
ssh "$NETBOOK_SSH" "sudo sed -i 's/^#LID_SLEEP=true$/LID_SLEEP=true/' /etc/default/acpi-support"

echo "== Building $IMAGE for linux/$TARGET_ARCH"
docker build --provenance=false --platform "linux/$TARGET_ARCH" -t "$IMAGE" "$SOURCE_DIR"

echo "== Loading the image on $NETBOOK_SSH"
docker save "$IMAGE" | gzip | ssh "$NETBOOK_SSH" 'gunzip | sudo docker load'

echo "== Starting the containers"
ssh "$NETBOOK_SSH" "mkdir -p $REMOTE_DIR"
ssh "$NETBOOK_SSH" "cat > $REMOTE_DIR/docker-compose.yml" < "$SOURCE_DIR/docker-compose.yml"
# The admin password stays on the netbook only; created once, read it with: ssh netbook cat ~/ticwatch-cloud/.env
ssh "$NETBOOK_SSH" "cd $REMOTE_DIR && test -f .env || { umask 077; echo MYAPPLICATION_ADMIN_PASSWORD=\$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n') > .env; }"
ssh "$NETBOOK_SSH" "cd $REMOTE_DIR && sudo docker-compose up -d"

echo "== Waiting for the server"
for ((attempt = 1; attempt <= PING_ATTEMPTS; attempt++)); do
    if curl -sf --max-time 3 "http://$NETBOOK_HOST:$PORT$PING_PATH"; then
        echo
        exit 0
    fi
    sleep "$PING_INTERVAL_SECONDS"
done
echo "The server did not answer on $NETBOOK_HOST:$PORT" >&2
exit 1
