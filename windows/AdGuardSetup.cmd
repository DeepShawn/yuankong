@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0"
chcp 65001 >nul 2>&1
if not exist "python\python.exe" (
    echo Missing portable Python. Extract the entire Windows package first.
    pause
    exit /b 2
)
"python\python.exe" -I -X utf8 -B "adguard_service.py" --setup
set "ADGUARD_SETUP_EXIT=%errorlevel%"
pause
exit /b %ADGUARD_SETUP_EXIT%
