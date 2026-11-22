@echo off
rem Starts setup-fdroid.ps1 without having to change the PowerShell execution policy.
rem Usage: setup-fdroid.bat [-GitLabUser name] [-LocalOnly] [-SkipBuild] [-NoPush] [-SetupOwnRepo] [-KeepOldBuilds] [-Yes]
rem Details: docs\FDROID.md and the header of setup-fdroid.ps1
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-fdroid.ps1" %*
set "RC=%ERRORLEVEL%"
echo.
rem Keep the window open when started by double-click (cmd /c), not when run from a terminal.
echo %cmdcmdline% | find /i "/c" >nul && pause
exit /b %RC%
