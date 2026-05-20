@echo off
REM BlePhone one-click launcher — double-click to run.
REM Optional: pass an AVD name as argument, e.g. start.bat Pixel_7
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_blephone.ps1" %*
echo.
pause
