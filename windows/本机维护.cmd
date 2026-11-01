@echo off
setlocal
chcp 65001 >nul
"%~dp0python\python.exe" -I -B -u "%~dp0local_maintenance.py"
if errorlevel 1 pause
