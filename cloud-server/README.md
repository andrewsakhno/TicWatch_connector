# cloud-server

Go port of `backend/` (cloud history for the TicWatch phone app), wire-compatible with it, running on the home
netbook (32-bit antiX, Docker, no JVM available). The phone needs no change: it finds the server by scanning its /24
for port 8766 and checking `GET /api/v1/ping`.

## Layout

- `cmd/cloud-server` - composition root, env config, `-healthcheck` flag (the scratch image has no curl).
- `internal/platform/{auth,db,migrate,httpx}` - shared by all apps. `platform/app` is the `App` interface an app
  implements (routes, migrations, public paths; optionally `JobProvider`), `platform/jobs` a small scheduler with no
  jobs registered yet.
- `internal/apps/ticwatch` - everything TicWatch: contract constants, DTOs, handlers, repositories, migrations.
- `internal/apps/myapplication` - parent -> child exchange of the MyApplication (MyLibrus) Android apps, under
  `/myapplication`, schema `myapplication`. Admin page: `/myapplication/admin` (HTTP Basic, user `admin`, password
  `MYAPPLICATION_ADMIN_PASSWORD`; blank = locked). Devices appear there on their first hello and can do nothing until
  given a role; a child also needs an account. Payloads are stored and sent unencrypted (home LAN, plain HTTP).

To add an app: create `internal/apps/<name>`, implement `app.App` and add it to the `apps` list in `main.go`. One
PostgreSQL, one schema per app; `migrate` tracks files in `<schema>.schema_migrations` and runs them with that schema
as the search path. Per-app keys would be another `auth.KeySource`. Jobs must be cheap and incremental, and request
paths must not scan full history.

## Configuration

| Variable | Default |
| --- | --- |
| `TICWATCH_CLOUD_PORT` | `8766` |
| `TICWATCH_DB_URL` | `postgres://localhost:5432/ticwatch` (pgx DSN) |
| `TICWATCH_DB_USER` / `TICWATCH_DB_PASSWORD` | `ticwatch` / `ticwatch` (override the DSN's) |
| `TICWATCH_CLOUD_API_KEY` | empty = open; set = `Authorization: Bearer <key>` on everything except ping and `/myapplication` |
| `MYAPPLICATION_ADMIN_PASSWORD` | empty = admin page locked (`deploy-netbook.sh` creates one in the netbook's `.env`) |

## Commands (from WSL, `/mnt/e/projects/TicWatch_connector/cloud-server`)

There is no Go on the host; everything runs in Docker.

```sh
# vet + unit tests
docker run --rm -v "$PWD":/src -v ticwatch-gomod:/go/pkg/mod -w /src golang:1.27 sh -c 'go vet ./... && go test ./...'
scripts/contract-diff.sh          # diff against the Kotlin server's image (KOTLIN_IMAGE, default backend-cloud:latest)
./deploy-netbook.sh               # build for linux/386, load on the netbook, docker-compose up, wait for ping
scripts/migrate-from-wsl.sh       # row counts only; --apply copies the WSL database to the netbook
```

For `amd64`, change `TARGET_ARCH` in `deploy-netbook.sh`.

## Known differences from `backend/`

Nothing the phone can send, all checked by `contract-diff.sh` except the last two:

- Jackson's lenient coercions aren't ported: `"value":"1.5"`, `"recordedAt":200.5` and `types=0x1` are 400 here.
- 404/405 bodies are net/http's plain text rather than Spring's JSON (same statuses).
- The key must carry the `Bearer ` prefix; the Kotlin filter also accepts the bare key.
- Bodies over 32 MiB get a 413; an ECG payload is stored as sent, where Jackson re-serialised it first (`2.50`
  stays `2.50`, compared to `2.5`).
