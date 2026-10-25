@echo off
rem Wrapper for deploy.ps1; passes through args (-Force, -NoWait, -WaitTimeoutSec N).
rem Prefers PowerShell 7 (pwsh), falls back to built-in Windows PowerShell 5.1.
set "PS_EXE=pwsh"
where pwsh >nul 2>&1 || set "PS_EXE=powershell"
%PS_EXE% -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
