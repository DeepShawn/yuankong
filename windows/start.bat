@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
cd /d "%~dp0"
title LanCmd 启动器
set "PIDFILE=%APPDATA%\LanCmd\agent.pid"

echo.
echo   LanCmd 局域网命令接收端
echo   ============================
echo.

if not exist "LanCmdAgent.exe" (
    echo   [X] 找不到 LanCmdAgent.exe
    echo       请保持整个文件夹完整，不要只拷单个文件。
    echo.
    pause
    exit /b 1
)

set "OLDPID="
if exist "%PIDFILE%" (
    set /p OLDPID=<"%PIDFILE%"
)
if defined OLDPID (
    tasklist /FI "PID eq !OLDPID!" 2>nul | find "!OLDPID!" >nul
    if not errorlevel 1 (
        echo   接收端已经在运行 ^(PID !OLDPID!^)
        echo.
        echo   手机打开 LanCmd App 就能连上。
        echo.
        pause
        exit /b 0
    )
)

echo   正在启动接收端...
start "" "LanCmdAgent.exe"
timeout /t 2 >nul

set "NEWPID="
if exist "%PIDFILE%" (
    set /p NEWPID=<"%PIDFILE%"
)
if defined NEWPID (
    echo   [OK] 已在后台运行 ^(PID !NEWPID!^)
    echo.
    echo   没有窗口是正常的，接收端就是静默运行。
    echo.
    echo   接下来：
    echo     1. 手机连同一个网络 ^(同一 Wi-Fi、电脑开的热点都行^)
    echo     2. 打开 LanCmd App，会自动发现这台电脑
    echo     3. 首次连接时电脑会弹确认框，点「是」
    echo.
    echo   查看状态 / 停止 / 开机自启：双击 Start.exe
) else (
    echo   [!] 启动似乎没有成功。
    echo       直接双击 LanCmdAgent.exe 试试，
    echo       或用 Start.exe 打开面板看具体提示。
)

echo.
pause
endlocal
