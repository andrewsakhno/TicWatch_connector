# Opens the cloud server port to the LAN (run elevated once). WSL runs in mirrored networking with its
# firewall on, so the port needs both a Windows inbound rule and a Hyper-V (WSL VM) inbound rule.
$ErrorActionPreference = 'Stop'
$Port = 8766
$WslVmCreatorId = '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}'
$RuleName = "TicWatchCloud$Port"

if (-not (Get-NetFirewallRule -Name $RuleName -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name $RuleName -DisplayName "TicWatch cloud $Port" -Direction Inbound `
        -Protocol TCP -LocalPort $Port -Action Allow -Profile Any | Out-Null
}
if (-not (Get-NetFirewallHyperVRule -Name $RuleName -ErrorAction SilentlyContinue)) {
    New-NetFirewallHyperVRule -Name $RuleName -DisplayName "WSL TicWatch cloud $Port" -Direction Inbound `
        -VMCreatorId $WslVmCreatorId -Protocol TCP -LocalPorts $Port -Action Allow | Out-Null
}
Write-Host "Port $Port open for Windows and the WSL VM."
