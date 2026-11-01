#!/usr/bin/env bash
# Copies the history from the WSL `backend` database (schema public) into the netbook's (schema ticwatch).
#
# Without --apply it only prints the row counts on both sides. Anything the phone uploads to the WSL server after the
# dump starts does not reach the netbook, so stop the WSL `cloud` container first. The netbook's server is stopped
# during the copy. The restore is one transaction and refuses a non-empty target, so a failed or repeated run changes
# nothing.
set -euo pipefail

SOURCE_CONTAINER=backend-db-1
NETBOOK_SSH=netbook
REMOTE_DIR='~/ticwatch-cloud'
DB_USER=ticwatch
DB_NAME=ticwatch
SOURCE_SCHEMA=public
TARGET_SCHEMA=ticwatch
TABLES=(sensor_sample route_point ecg_recording)

source_psql() {
    docker exec -i "$SOURCE_CONTAINER" psql -v ON_ERROR_STOP=1 -At -U "$DB_USER" -d "$DB_NAME" "$@"
}

# The compose file's db service, looked up by id: `docker-compose exec` isn't reliable with piped stdin on 1.29.
target_psql() {
    local args
    printf -v args '%q ' "$@"
    ssh "$NETBOOK_SSH" "cd $REMOTE_DIR && sudo docker exec -i \$(sudo docker-compose ps -q db) psql -v ON_ERROR_STOP=1 -At -U $DB_USER -d $DB_NAME $args"
}

print_counts() {
    local table
    printf '%-16s %12s %12s\n' table wsl netbook
    for table in "${TABLES[@]}"; do
        printf '%-16s %12s %12s\n' "$table" \
            "$(source_psql -c "SELECT count(*) FROM $SOURCE_SCHEMA.$table")" \
            "$(target_psql -c "SELECT count(*) FROM $TARGET_SCHEMA.$table")"
    done
}

print_counts
if [[ "${1:-}" != "--apply" ]]; then
    echo "Dry run: nothing copied. Re-run with --apply."
    exit 0
fi

# A phone upload during the restore would take ids from the sequence that the copied rows already use.
ssh "$NETBOOK_SSH" "cd $REMOTE_DIR && sudo docker-compose stop cloud"
trap 'ssh "$NETBOOK_SSH" "cd $REMOTE_DIR && sudo docker-compose start cloud"' EXIT

for table in "${TABLES[@]}"; do
    if [[ "$(target_psql -c "SELECT count(*) FROM $TARGET_SCHEMA.$table")" != "0" ]]; then
        echo "$TARGET_SCHEMA.$table on the netbook is not empty; refusing to copy." >&2
        exit 1
    fi
done

dump_args=()
for table in "${TABLES[@]}"; do
    dump_args+=(-t "$SOURCE_SCHEMA.$table")
done

echo "== Copying"
# Only statement lines are rewritten: data lines start with a number. The sequence is fixed separately below.
docker exec "$SOURCE_CONTAINER" pg_dump --data-only --no-owner -U "$DB_USER" -d "$DB_NAME" "${dump_args[@]}" |
    sed -e '/^\\restrict /d' -e '/^\\unrestrict /d' -e '/^SELECT pg_catalog\.setval(/d' \
        -e "s/^COPY $SOURCE_SCHEMA\./COPY $TARGET_SCHEMA./" |
    target_psql --single-transaction

target_psql -c "SELECT setval('$TARGET_SCHEMA.sensor_sample_id_seq', COALESCE((SELECT MAX(id) FROM $TARGET_SCHEMA.sensor_sample), 1))"

echo "== Result"
print_counts
