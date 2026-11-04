@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0"
chcp 65001 >nul 2>&1
if not exist "python\python.exe" (
    echo Missing portable Python. Extract the entire Windows package first.
    pause
    exit /b 2
)
"python\python.exe" -I -X utf8 -B "hosts_rules.py" --settings
set "WEBSITE_RULES_EXIT=%errorlevel%"
pause
exit /b %WEBSITE_RULES_EXIT%
