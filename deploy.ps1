<#
.SYNOPSIS
    Builds phone-app and watch-app, then deploys whichever changed since the last
    successful deploy: phone-app via adb, watch-app through the phone's Bluetooth bridge
    (WatchAppInstallService), so the watch never needs to be on adb.

.PARAMETER Force
    Deploy both apps even if their APKs are unchanged.

.PARAMETER NoWait
    Do not wait for the watch install result in the phone's logcat.

.PARAMETER WaitTimeoutSec
    How long to wait for the bridge transfer result (classic Bluetooth, ~26MB: minutes).
#>
param(
    [switch]$Force,
    [switch]$NoWait,
    [int]$WaitTimeoutSec = 600
)

$ErrorActionPreference = 'Stop'

$RepoRoot = $PSScriptRoot
$StateFile = Join-Path $RepoRoot 'build\deploy-state.json'
$PhonePackage = 'org.sand.ticwatch.phone'
$WatchFeature = 'android.hardware.type.watch'
$DeviceTmpApk = '/data/local/tmp/watch-app-debug.apk'
$PhoneInternalApk = "/data/data/$PhonePackage/files/watch-app-debug.apk"
$InstallServiceComponent = "$PhonePackage/.datalayer.WatchAppInstallService"
$InstallServiceTag = 'WatchAppInstallService'
$SuccessMarker = 'Sent watch-app install request'
$FailureMarker = 'Failed to install watch-app'
$PollIntervalSec = 5
$PushAttempts = 3
$PushRetryDelaySec = 3

$Apps = @(
    @{ Name = 'phone'; Dir = 'phone-app'; Apk = 'phone-app\build\outputs\apk\debug\phone-app-debug.apk' },
    @{ Name = 'watch'; Dir = 'watch-app'; Apk = 'watch-app\build\outputs\apk\debug\watch-app-debug.apk' }
)

function Invoke-Checked([string]$What, [scriptblock]$Command) {
    # Windows PowerShell 5.1 turns redirected native stderr (adb progress) into error
    # records that 'Stop' would throw on; rely on the exit code instead.
    $ErrorActionPreference = 'Continue'
    $output = & $Command 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit $LASTEXITCODE):`n$($output | Out-String)" }
    return $output
}

function Build-App($App) {
    Write-Host "==> Building $($App.Dir)" -ForegroundColor Cyan
    Push-Location (Join-Path $RepoRoot $App.Dir)
    try {
        & .\gradlew.bat assembleDebug --quiet
        if ($LASTEXITCODE -ne 0) { throw "$($App.Dir) build failed" }
    } finally { Pop-Location }
    $apk = Join-Path $RepoRoot $App.Apk
    if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
    return (Get-FileHash $apk -Algorithm SHA256).Hash
}

function Read-State {
    # Manual conversion: -AsHashtable is unavailable in Windows PowerShell 5.1.
    $state = @{}
    if (Test-Path $StateFile) {
        (Get-Content $StateFile -Raw | ConvertFrom-Json).PSObject.Properties |
            ForEach-Object { $state[$_.Name] = $_.Value }
    }
    return $state
}

function Save-State($State) {
    New-Item -ItemType Directory -Force (Split-Path $StateFile) | Out-Null
    $State | ConvertTo-Json | Set-Content $StateFile
}

# The watch may also be on adb (wireless debugging); pick the one non-Wear OS device.
function Find-PhoneSerial {
    $serials = Invoke-Checked 'adb devices' { adb devices } |
        Select-Object -Skip 1 |
        Where-Object { $_ -match '^(\S+)\s+device$' } |
        ForEach-Object { $Matches[1] }
    if (-not $serials) { throw 'No adb devices connected' }
    foreach ($serial in $serials) {
        $features = Invoke-Checked "pm list features on $serial" { adb -s $serial shell pm list features }
        if (-not ($features -match $WatchFeature)) { return $serial }
    }
    throw "No non-Wear OS device among adb devices: $($serials -join ', ')"
}

function Install-PhoneApp([string]$Serial, $App) {
    Write-Host "==> Installing phone-app on $Serial" -ForegroundColor Cyan
    Invoke-Checked 'adb install' { adb -s $Serial install -r (Join-Path $RepoRoot $App.Apk) } | Write-Host
}

# Staged in the phone app's internal storage via run-as: an adb-created
# Android/data dir is owned by shell and the app gets EACCES reading it.
function Stage-WatchApk([string]$Serial, $App) {
    Write-Host '==> Staging watch-app APK on the phone' -ForegroundColor Cyan
    $apk = Join-Path $RepoRoot $App.Apk
    # A large push over wireless adb sometimes loses the final ack ("failed to read copy
    # response: EOF") even though the bytes landed; retry, the size check below verifies.
    for ($attempt = 1; ; $attempt++) {
        try {
            Invoke-Checked 'adb push' { adb -s $Serial push $apk $DeviceTmpApk } | Out-Null
            break
        } catch {
            if ($attempt -ge $PushAttempts) { throw }
            Write-Host "adb push failed (attempt $attempt/$PushAttempts), retrying..." -ForegroundColor Yellow
            Start-Sleep -Seconds $PushRetryDelaySec
            & adb -s $Serial wait-for-device
        }
    }
    Invoke-Checked 'run-as copy' {
        adb -s $Serial shell "chmod 644 $DeviceTmpApk && run-as $PhonePackage mkdir -p files && run-as $PhonePackage cp $DeviceTmpApk $PhoneInternalApk && rm $DeviceTmpApk"
    } | Out-Null
    $remoteSize = [long](Invoke-Checked 'size check' { adb -s $Serial shell run-as $PhonePackage stat -c %s $PhoneInternalApk } | Select-Object -First 1)
    $localSize = (Get-Item $apk).Length
    if ($remoteSize -ne $localSize) { throw "Staged APK size mismatch: local $localSize, phone $remoteSize" }
}

function Start-WatchInstall([string]$Serial) {
    Write-Host '==> Starting watch-app install over the phone bridge' -ForegroundColor Cyan
    $since = (Invoke-Checked 'device time' { adb -s $Serial shell "date '+%m-%d %H:%M:%S.000'" } | Select-Object -First 1).Trim()
    Invoke-Checked 'start install service' {
        adb -s $Serial shell am start-foreground-service -n $InstallServiceComponent --es apkPath $PhoneInternalApk
    } | Write-Host
    return $since
}

function Wait-WatchInstallResult([string]$Serial, [string]$Since) {
    Write-Host "==> Waiting up to $WaitTimeoutSec s for the transfer (silent for minutes is normal)" -ForegroundColor Cyan
    $deadline = (Get-Date).AddSeconds($WaitTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds $PollIntervalSec
        $log = Invoke-Checked 'logcat' { adb -s $Serial logcat -d -T $Since -s "${InstallServiceTag}:*" }
        if ($log -match $SuccessMarker) {
            Write-Host 'Watch-app sent. Confirm the install dialog on the watch (tap its notification).' -ForegroundColor Green
            return $true
        }
        if ($log -match $FailureMarker) {
            Write-Host ($log | Out-String) -ForegroundColor Red
            return $false
        }
        Write-Host '.' -NoNewline
    }
    Write-Host ''
    Write-Warning "No result within $WaitTimeoutSec s; check: adb logcat -s ${InstallServiceTag}:* PhoneMessageRouter:I"
    return $false
}

# --- Flow ---
$state = Read-State
$changed = @{}
foreach ($app in $Apps) {
    $hash = Build-App $app
    $changed[$app.Name] = $Force -or ($state[$app.Name] -ne $hash)
    $app.Hash = $hash
}

if (-not ($changed.Values -contains $true)) {
    Write-Host 'No changes since last deploy. Use -Force to redeploy.' -ForegroundColor Yellow
    exit 0
}

$serial = Find-PhoneSerial
$phone, $watch = $Apps

if ($changed.phone) {
    Install-PhoneApp $serial $phone
    $state.phone = $phone.Hash
    Save-State $state
} else { Write-Host 'phone-app unchanged, skipping install' }

if ($changed.watch) {
    Stage-WatchApk $serial $watch
    $since = Start-WatchInstall $serial
    if ($NoWait) {
        Write-Host 'Not waiting for result (-NoWait); watch-app state not recorded as deployed.'
    } elseif (Wait-WatchInstallResult $serial $since) {
        $state.watch = $watch.Hash
        Save-State $state
    } else { exit 1 }
} else { Write-Host 'watch-app unchanged, skipping install' }
