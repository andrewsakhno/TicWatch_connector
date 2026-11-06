#!/usr/bin/env bash
# Replays the same uploads and reads against the Kotlin `backend` and this server, each on a fresh PostgreSQL, and
# diffs the raw responses (status + body, only serverTime/timestamp normalised), the stored rows and the schema.
# Touches nothing that is already running. Exit status 0 = identical. Run from WSL.
set -euo pipefail

KOTLIN_IMAGE="${KOTLIN_IMAGE:-backend-cloud:latest}"
KOTLIN_DB_IMAGE=postgres:17
GO_DB_IMAGE=postgres:17-alpine
TARGET_ARCH="${TARGET_ARCH:-386}"
GO_IMAGE=ticwatch-cloud-server:contract-test
KOTLIN_PORT=18766
GO_PORT=18767
SERVER_PORT=8766
DB_NAME=ticwatch
DB_USER=ticwatch
DB_PASSWORD=ticwatch
KOTLIN_SCHEMA=public
GO_SCHEMA=ticwatch
START_ATTEMPTS=120
ZONE=Europe/Warsaw
API=/api/v1
DEVICE_HEADER="X-Device-Id: contract-test"
JSON_HEADER="Content-Type: application/json"
PAGE_SIZE=25
MAX_PAGES=40
NOT_BEFORE=0
NOT_AFTER=9999999999999

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="ticwatch-contract-$$"
NETWORK="$PREFIX"
OUT="$(mktemp -d)"
KEEP_OUTPUT=0
failures=0
expected_differences=0
case_number=0

cleanup() {
    docker rm -f "$PREFIX-kotlin" "$PREFIX-go" "$PREFIX-kotlin-db" "$PREFIX-go-db" > /dev/null 2>&1 || true
    docker network rm "$NETWORK" > /dev/null 2>&1 || true
    if [[ "$KEEP_OUTPUT" == 0 ]]; then rm -rf "$OUT"; fi
}
trap cleanup EXIT

start_db() { # name image
    docker run -d --name "$PREFIX-$1" --network "$NETWORK" --network-alias "$1" \
        -e "POSTGRES_DB=$DB_NAME" -e "POSTGRES_USER=$DB_USER" -e "POSTGRES_PASSWORD=$DB_PASSWORD" "$2" > /dev/null
}

wait_for_ping() { # port
    local attempt
    for ((attempt = 1; attempt <= START_ATTEMPTS; attempt++)); do
        if curl -sf --max-time 2 "http://127.0.0.1:$1$API/ping" > /dev/null; then return 0; fi
        sleep 1
    done
    echo "Server on port $1 did not start" >&2
    return 1
}

normalise() {
    sed -E -e 's/"serverTime":[0-9]+/"serverTime":0/' -e 's/"timestamp":"[^"]+"/"timestamp":"T"/'
}

# Writes "<status>\n<normalised body>" for one server.
request() { # server port path curl-args...
    local server="$1" port="$2" path="$3"
    shift 3
    local body="$OUT/body.$server"
    local status
    status="$(curl -s -o "$body" -w '%{http_code}' "$@" "http://127.0.0.1:$port$API$path")"
    printf '%s\n' "$status"
    if [[ "${STATUS_ONLY:-0}" == 0 ]]; then
        normalise < "$body"
        echo
    fi
}

compare() { # name
    local name="$1" kotlin="$OUT/$case_number.kotlin" go="$OUT/$case_number.go"
    if cmp -s "$kotlin" "$go"; then
        echo "same   $name"
    elif [[ "${EXPECT_DIFFERENCE:-0}" == 1 ]]; then
        echo "EXPECTED DIFFERENCE  $name"
        echo "    kotlin: $(head -c 200 "$kotlin" | tr '\n' ' ')"
        echo "    go:     $(head -c 200 "$go" | tr '\n' ' ')"
        expected_differences=$((expected_differences + 1))
    else
        echo "DIFF   $name"
        diff <(head -c 1500 "$kotlin") <(head -c 1500 "$go") || true
        failures=$((failures + 1))
    fi
}

check() { # name path curl-args...
    local name="$1" path="$2"
    shift 2
    case_number=$((case_number + 1))
    request kotlin "$KOTLIN_PORT" "$path" "$@" > "$OUT/$case_number.kotlin"
    request go "$GO_PORT" "$path" "$@" > "$OUT/$case_number.go"
    compare "$name"
}

upload() { # name path body
    check "$1" "$2" -X POST -H "$DEVICE_HEADER" -H "$JSON_HEADER" --data-binary "$3"
}

# Spring and net/http word these differently; the body isn't part of what the phone reads for them.
check_status() {
    STATUS_ONLY=1 check "$@"
}

known_difference() {
    EXPECT_DIFFERENCE=1 check "$@"
}

utc_ms() { echo $(($(date -u -d "$1" +%s) * 1000)); }

# A reading every 20 minutes across a DST change.
samples_across() { # type start-utc hours
    local step=$((20 * 60 * 1000)) count=$(($3 * 3)) start i separator=""
    start="$(utc_ms "$2")"
    for ((i = 0; i < count; i++)); do
        printf '%s{"type":%d,"value":%d.%d,"recordedAt":%d,"endedAt":null,"source":0,"snapshot":false}' \
            "$separator" "$1" $((60 + i % 40)) $((i % 10)) $((start + i * step))
        separator=","
    done
}

table_rows() { # container schema
    docker exec -i "$1" psql -v ON_ERROR_STOP=1 -At -U "$DB_USER" -d "$DB_NAME" <<SQL
SELECT id, type, value, recorded_at, ended_at, source, snapshot, device_id FROM $2.sensor_sample ORDER BY id;
SELECT * FROM $2.route_point ORDER BY recorded_at, source;
SELECT started_at, payload, device_id FROM $2.ecg_recording ORDER BY started_at;
SELECT uid, direction, state, started_at, ended_at, peak_bpm, reasons, detected_at, asked_at, answered_at, discarded_at, device_id FROM $2.deviation_episode ORDER BY uid;
SQL
}

schema_ddl() { # container schema excluded-table
    docker exec "$1" pg_dump --schema-only --no-owner -U "$DB_USER" -d "$DB_NAME" -n "$2" -T "$2.$3" |
        grep -vE '^(--|SET |SELECT pg_catalog|\\|CREATE SCHEMA|ALTER SCHEMA|COMMENT ON SCHEMA|$)' |
        sed -E "s/\b$2\./S./g"
}

echo "== Building the server for linux/$TARGET_ARCH"
docker build -q --provenance=false --platform "linux/$TARGET_ARCH" -t "$GO_IMAGE" "$SOURCE_DIR" > /dev/null

echo "== Starting both servers on fresh databases"
docker network create "$NETWORK" > /dev/null
start_db kotlin-db "$KOTLIN_DB_IMAGE"
start_db go-db "$GO_DB_IMAGE"
sleep 8
docker run -d --name "$PREFIX-kotlin" --network "$NETWORK" -p "127.0.0.1:$KOTLIN_PORT:$SERVER_PORT" --restart on-failure:10 \
    -e "TICWATCH_DB_URL=jdbc:postgresql://kotlin-db:5432/$DB_NAME" -e "TICWATCH_DB_USER=$DB_USER" -e "TICWATCH_DB_PASSWORD=$DB_PASSWORD" \
    "$KOTLIN_IMAGE" > /dev/null
docker run -d --name "$PREFIX-go" --network "$NETWORK" -p "127.0.0.1:$GO_PORT:$SERVER_PORT" --restart on-failure:10 \
    --platform "linux/$TARGET_ARCH" \
    -e "TICWATCH_DB_URL=postgres://go-db:5432/$DB_NAME" -e "TICWATCH_DB_USER=$DB_USER" -e "TICWATCH_DB_PASSWORD=$DB_PASSWORD" \
    "$GO_IMAGE" > /dev/null
wait_for_ping "$KOTLIN_PORT"
wait_for_ping "$GO_PORT"
docker exec "$PREFIX-go" /cloud-server -healthcheck && echo "same   -healthcheck flag exits 0"

echo "== Uploads"
SPRING="$(samples_across 1 '2026-03-28 20:00' 32)"
AUTUMN="$(samples_across 1 '2026-10-24 20:00' 32)"
OTHER="$(samples_across 2 '2026-03-28 22:00' 4)"
check "ping" /ping
upload "samples: spring DST" /samples "{\"samples\":[$SPRING]}"
upload "samples: autumn DST" /samples "{\"samples\":[$AUTUMN]}"
upload "samples: other type" /samples "{\"samples\":[$OTHER]}"
upload "samples: re-sent duplicate batch" /samples "{\"samples\":[$SPRING]}"
upload "samples: duplicate inside a batch, null and missing endedAt" /samples \
    '{"samples":[{"type":3,"value":1.5,"recordedAt":1790000000000,"endedAt":null,"source":0,"snapshot":false},
    {"type":3,"value":1.5,"recordedAt":1790000000000,"source":0,"snapshot":true},
    {"type":3,"value":1.5,"recordedAt":1790000000000,"endedAt":1790000060000,"source":0,"snapshot":true},
    {"type":3,"value":2,"recordedAt":1790000001000,"endedAt":1790000060000,"source":1,"snapshot":true},
    {"type":3,"value":3,"recordedAt":1790000002000,"endedAt":null,"source":0,"snapshot":false,"unknownField":"ignored"}]}'
upload "samples: double formats" /samples \
    '{"samples":[{"type":4,"value":0,"recordedAt":1,"endedAt":null,"source":0,"snapshot":false},
    {"type":4,"value":1e7,"recordedAt":2,"endedAt":null,"source":0,"snapshot":false},
    {"type":4,"value":9999999,"recordedAt":3,"endedAt":null,"source":0,"snapshot":false},
    {"type":4,"value":0.001,"recordedAt":4,"endedAt":null,"source":0,"snapshot":false},
    {"type":4,"value":0.00099,"recordedAt":5,"endedAt":null,"source":0,"snapshot":false},
    {"type":4,"value":1.7976931348623157e308,"recordedAt":6,"endedAt":null,"source":0,"snapshot":false},
    {"type":4,"value":-36.6,"recordedAt":7,"endedAt":null,"source":0,"snapshot":false},
    {"type":4,"value":0.30000000000000004,"recordedAt":8,"endedAt":null,"source":0,"snapshot":false},
    {"type":4,"value":123456789.125,"recordedAt":9,"endedAt":null,"source":0,"snapshot":false},
    {"type":4,"value":1.5e-10,"recordedAt":10,"endedAt":null,"source":0,"snapshot":false}]}'
upload "samples: empty" /samples '{"samples":[]}'
ROUTE_FIXES='{"points":[{"recordedAt":1790000000000,"source":0,"latitude":52.2297,"longitude":21.0122,"altitudeMeters":110.5,"horizontalAccuracyMeters":4.0},
    {"recordedAt":1790000004000,"source":0,"latitude":52.2298,"longitude":21.0123,"altitudeMeters":null,"horizontalAccuracyMeters":null},
    {"recordedAt":1790000004000,"source":1,"latitude":52.23,"longitude":21.0,"horizontalAccuracyMeters":3},
    {"recordedAt":1790000008000,"source":0,"latitude":-1,"longitude":0}]}'
upload "route points" /route-points "$ROUTE_FIXES"
upload "route points: re-sent" /route-points "$ROUTE_FIXES"
upload "route points: changed fix replaces itself" /route-points \
    '{"points":[{"recordedAt":1790000000000,"source":0,"latitude":1.5,"longitude":2.5,"altitudeMeters":null,"horizontalAccuracyMeters":7.25}]}'
upload "route points: empty" /route-points '{"points":[]}'
ECG='{"recordings":[{"startedAt":1790000000000,"payload":{"beats":[{"t":0,"rr":812,"ok":true},{"t":812,"rr":790.5,"ok":false}],"wave":{"t":[0,31],"v":[-1.5,2.25]},"note":"пульс \"ok\"","none":null,"deep":{"a":{"b":{"c":[[1],[2,[3]]]}}}}},
    {"startedAt":1790000100000,"payload":[1,"two",null,true]},
    {"startedAt":1790000200000,"payload":"text"},
    {"startedAt":1790000300000,"payload":null}]}'
upload "ecg: nested JSON" /ecg-recordings "$ECG"
upload "ecg: re-sent" /ecg-recordings "$ECG"
upload "ecg: replaced payload" /ecg-recordings '{"recordings":[{"startedAt":1790000100000,"payload":{"replaced":true}}]}'
upload "ecg: empty" /ecg-recordings '{"recordings":[]}'
DEVIATIONS='{"episodes":[{"uid":"a","direction":0,"state":1,"startedAt":1790000000000,"endedAt":1790000600000,"peakBpm":118.0,"reasons":[],"detectedAt":1790000700000},
    {"uid":"b","direction":1,"state":0,"startedAt":1790001000000,"endedAt":1790001600000,"peakBpm":44.5,"reasons":[20,50],"detectedAt":1790001700000,"askedAt":1790001800000,"answeredAt":1790001900000}]}'
upload "deviations: new" /deviation-episodes "$DEVIATIONS"
upload "deviations: re-sent" /deviation-episodes "$DEVIATIONS"
upload "deviations: answered later, then discarded" /deviation-episodes \
    '{"episodes":[{"uid":"a","direction":0,"state":1,"startedAt":1790000000000,"endedAt":1790000900000,"peakBpm":125.5,"reasons":[60],"detectedAt":1790000700000,"askedAt":1790000800000,"answeredAt":1790000950000,"discardedAt":1790001000000}]}'
upload "deviations: empty" /deviation-episodes '{"episodes":[]}'

echo "== Reads"
for resolution in FIVE_MINUTES HOUR DAY; do
    for window in "2026-03-28 00:00|2026-03-31 00:00" "2026-10-24 00:00|2026-10-27 00:00"; do
        from="$(utc_ms "${window%|*}")"
        to="$(utc_ms "${window#*|}")"
        check "rollups $resolution $window $ZONE" "/rollups?type=1&resolution=$resolution&from=$from&to=$to&zone=$ZONE"
    done
done
check "rollups: double formats" "/rollups?type=4&resolution=DAY&from=$NOT_BEFORE&to=$NOT_AFTER&zone=UTC"
check "rollups: unused zone may be anything" "/rollups?type=1&resolution=HOUR&from=$NOT_BEFORE&to=1&zone=nonsense"
check "rollups: empty" "/rollups?type=99&resolution=DAY&from=$NOT_BEFORE&to=$NOT_AFTER&zone=$ZONE"
check "rollups: type beyond smallint" "/rollups?type=70000&resolution=HOUR&from=$NOT_BEFORE&to=$NOT_AFTER&zone=UTC"
check "rollups: unknown zone on DAY" "/rollups?type=1&resolution=DAY&from=$NOT_BEFORE&to=$NOT_AFTER&zone=Mars/Olympus"

ALL="from=$NOT_BEFORE&to=$NOT_AFTER"
check "samples: comma types" "/samples?types=1,2&$ALL&limit=5000"
check "samples: repeated types" "/samples?types=1&types=2&$ALL&limit=5000"
check "samples: spaced types" "/samples?types=1,%202&$ALL&limit=3"
check "samples: comma inside repeated types" "/samples?types=1,2&types=3&$ALL&limit=3"
check "samples: empty types" "/samples?types=&$ALL&limit=5"
check "samples: type beyond smallint" "/samples?types=70000&$ALL&limit=5"
check "samples: null and non-null endedAt" "/samples?types=3&$ALL&limit=10"
check "samples: double formats" "/samples?types=4&$ALL&limit=10"
check "samples: from inclusive, to exclusive" "/samples?types=4&from=2&to=5&limit=10"
check "samples: limit 0 clamps to 1" "/samples?types=1&$ALL&limit=0"
check "samples: negative limit clamps to 1" "/samples?types=1&$ALL&limit=-5"
check "samples: limit above the maximum clamps" "/samples?types=1,2&$ALL&limit=5001"
check "samples: only afterRecordedAt is ignored" "/samples?types=1&$ALL&limit=2&afterRecordedAt=1774728000000"
check "samples: only afterId is ignored" "/samples?types=1&$ALL&limit=2&afterId=50"
check "samples: empty cursor parts" "/samples?types=1&$ALL&limit=2&afterRecordedAt=&afterId="

cursor=""
for ((page = 1; page <= MAX_PAGES; page++)); do
    check "samples: page $page" "/samples?types=1,2&$ALL&limit=$PAGE_SIZE$cursor"
    last_id="$(tail -n +2 "$OUT/$case_number.kotlin" | grep -o '"id":[0-9]*' | tail -1 | cut -d: -f2 || true)"
    last_time="$(tail -n +2 "$OUT/$case_number.kotlin" | grep -o '"recordedAt":[0-9]*' | tail -1 | cut -d: -f2 || true)"
    if [[ -z "$last_id" ]]; then break; fi
    cursor="&afterRecordedAt=$last_time&afterId=$last_id"
done

check "route points: all" "/route-points?$ALL&limit=10"
check "route points: limit 1" "/route-points?$ALL&limit=1"
check "route points: limit 0 clamps" "/route-points?$ALL&limit=0"
check "route points: empty range" "/route-points?from=1&to=2&limit=5"

echo "== Errors"
check "no device header: samples" /samples -X POST -H "$JSON_HEADER" --data-binary '{"samples":[]}'
check "no device header: route points" /route-points -X POST -H "$JSON_HEADER" --data-binary '{"points":[]}'
check "no device header: ecg" /ecg-recordings -X POST -H "$JSON_HEADER" --data-binary '{"recordings":[]}'
check "no device header: deviations" /deviation-episodes -X POST -H "$JSON_HEADER" --data-binary '{"episodes":[]}'
check "empty device header is accepted" /samples -X POST -H "X-Device-Id;" -H "$JSON_HEADER" --data-binary '{"samples":[]}'
upload "malformed JSON" /samples '{"samples":['
upload "trailing tokens" /samples '{"samples":[]} {}'
upload "no list" /samples '{}'
upload "null list" /samples '{"samples":null}'
upload "missing value" /samples '{"samples":[{"type":1,"recordedAt":1,"endedAt":null,"source":0,"snapshot":false}]}'
upload "null value" /samples '{"samples":[{"type":1,"value":null,"recordedAt":1,"endedAt":null,"source":0,"snapshot":false}]}'
upload "missing snapshot" /samples '{"samples":[{"type":1,"value":1.0,"recordedAt":1,"endedAt":null,"source":0}]}'
upload "route point without latitude" /route-points '{"points":[{"recordedAt":1,"source":0,"longitude":1.0}]}'
upload "ecg without payload" /ecg-recordings '{"recordings":[{"startedAt":1}]}'
upload "deviation without reasons" /deviation-episodes '{"episodes":[{"uid":"c","direction":0,"state":1,"startedAt":1,"endedAt":2,"peakBpm":1.0,"detectedAt":3}]}'
upload "deviation without uid" /deviation-episodes '{"episodes":[{"direction":0,"state":1,"startedAt":1,"endedAt":2,"peakBpm":1.0,"reasons":[],"detectedAt":3}]}'
upload "type out of smallint range" /samples '{"samples":[{"type":70000,"value":1.0,"recordedAt":1,"endedAt":null,"source":0,"snapshot":false}]}'
check "wrong content type" /samples -X POST -H "$DEVICE_HEADER" -H "Content-Type: text/plain" --data-binary '{"samples":[]}'
check "charset in content type" /samples -X POST -H "$DEVICE_HEADER" -H "$JSON_HEADER; charset=UTF-8" --data-binary '{"samples":[]}'
check "no content type, no body" /samples -X POST -H "$DEVICE_HEADER"
check "missing types" "/samples?$ALL&limit=1"
check "missing from" "/samples?types=1&to=1&limit=1"
check "missing to" "/samples?types=1&from=1&limit=1"
check "missing limit" "/samples?types=1&$ALL"
check "empty from" "/samples?types=1&from=&to=1&limit=1"
check "bad from" "/samples?types=1&from=x&to=1&limit=1"
check "bad types" "/samples?types=x&$ALL&limit=1"
check "bad limit" "/samples?types=1&$ALL&limit=x"
check "limit overflow" "/samples?types=1&$ALL&limit=99999999999"
check "bad cursor" "/samples?types=1&$ALL&limit=1&afterId=x"
check "rollups: unknown resolution" "/rollups?type=1&resolution=WEEK&$ALL&zone=UTC"
check "rollups: lowercase resolution" "/rollups?type=1&resolution=day&$ALL&zone=UTC"
check "rollups: missing resolution" "/rollups?type=1&$ALL&zone=UTC"
check "rollups: missing type" "/rollups?resolution=DAY&$ALL&zone=UTC"
check "rollups: missing zone" "/rollups?type=1&resolution=HOUR&$ALL"
check "route points: missing limit" "/route-points?$ALL"
check_status "unknown path" /nope
check_status "trailing slash" /ping/
check_status "wrong method: ping" /ping -X POST
check_status "wrong method: ecg GET" /ecg-recordings
check_status "wrong method: DELETE samples" /samples -X DELETE

echo "== Jackson leniency, deliberately not ported (the phone's Gson never sends these)"
upload_lenient() { EXPECT_DIFFERENCE=1 upload "$@"; }
upload_lenient "number as a string" /samples '{"samples":[{"type":"5","value":"1.5","recordedAt":100,"endedAt":null,"source":0,"snapshot":false}]}'
upload_lenient "fraction for an integer" /samples '{"samples":[{"type":5,"value":1.5,"recordedAt":200.5,"endedAt":null,"source":0,"snapshot":false}]}'
known_difference "hex in a query integer" "/samples?types=0x1&$ALL&limit=1"

echo "== Stored data"
# These leniency uploads stored rows only on the Kotlin side.
docker exec "$PREFIX-kotlin-db" psql -q -U "$DB_USER" -d "$DB_NAME" -c \
    "DELETE FROM $KOTLIN_SCHEMA.sensor_sample WHERE type = 5" > /dev/null
case_number=$((case_number + 1))
table_rows "$PREFIX-kotlin-db" "$KOTLIN_SCHEMA" > "$OUT/$case_number.kotlin"
table_rows "$PREFIX-go-db" "$GO_SCHEMA" > "$OUT/$case_number.go"
compare "all stored rows ($(wc -l < "$OUT/$case_number.go") lines)"

case_number=$((case_number + 1))
schema_ddl "$PREFIX-kotlin-db" "$KOTLIN_SCHEMA" flyway_schema_history > "$OUT/$case_number.kotlin"
schema_ddl "$PREFIX-go-db" "$GO_SCHEMA" schema_migrations > "$OUT/$case_number.go"
compare "schema DDL ($(wc -l < "$OUT/$case_number.go") lines)"

echo
echo "cases: $case_number, unexpected differences: $failures, expected differences: $expected_differences"
if [[ "$failures" != 0 ]]; then
    KEEP_OUTPUT=1
    echo "Responses kept in $OUT"
    exit 1
fi
