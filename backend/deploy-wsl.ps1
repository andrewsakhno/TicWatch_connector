# Rebuilds and restarts the cloud history server (backend + PostgreSQL) in WSL's Docker.
# E: isn't mounted inside WSL, so the build context is copied to ~/ticwatch-cloud first.
# The database lives in the `ticwatch-db` volume and survives redeploys.
param([string]$Distro = 'Debian')

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$wslHome = (wsl -d $Distro -e bash -lc 'echo $HOME').Trim()
$target = "\\wsl.localhost\$Distro" + ($wslHome -replace '/', '\') + '\ticwatch-cloud'

if (Test-Path $target) { Remove-Item $target -Recurse -Force }
New-Item -ItemType Directory "$target\backend" | Out-Null
foreach ($file in 'gradlew', 'settings.gradle.kts', 'build.gradle.kts') { Copy-Item (Join-Path $repo $file) $target }
Copy-Item (Join-Path $repo 'gradle') $target -Recurse
foreach ($file in 'build.gradle.kts', 'Dockerfile', 'docker-compose.yml') { Copy-Item (Join-Path $PSScriptRoot $file) "$target\backend" }
Copy-Item (Join-Path $PSScriptRoot 'src') "$target\backend" -Recurse

Push-Location C:\
try {
    wsl -d $Distro -e bash -lc "cd ~/ticwatch-cloud && sed -i 's/\r$//' gradlew && cd backend && docker compose up -d --build"
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed ($LASTEXITCODE)" }
    wsl -d $Distro -e bash -lc 'for i in $(seq 1 30); do curl -sf localhost:8766/api/v1/ping && exit 0; sleep 2; done; exit 1'
    if ($LASTEXITCODE -ne 0) { throw 'Server did not answer /api/v1/ping' }
} finally {
    Pop-Location
}
