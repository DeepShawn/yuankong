@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
cd /d "%~dp0"
title LanCmd 启动器
set "PIDFILE=%APPDATA%\LanCmd\agent.pid"
set "LAUNCHLOG=%APPDATA%\LanCmd\launcher.log"

echo.
echo   LanCmd 局域网命令接收端
echo   ============================
echo.

if not exist "LanCmdAgent.exe" (
    echo   [X] 找不到 LanCmdAgent.exe
    echo       请保持整个文件夹完整，不要只拷单个文件。
    echo.
    goto :fail
)
if not exist "python\pythonw.exe" (
    echo   [X] 找不到 python\pythonw.exe
    echo       解压可能不完整，或者被安全软件隔离了。
    echo       请重新完整解压整个文件夹，并把整个文件夹加入信任。
    echo.
    goto :fail
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

:: 轮询等待，而不是死等 2 秒。
:: 冷启动 + 首次安全检查可能要好几秒，固定睡眠会误判成"没起来"。
set "NEWPID="
set /a WAITED=0
:poll
timeout /t 1 >nul
set /a WAITED+=1
if exist "%PIDFILE%" (
    set /p NEWPID=<"%PIDFILE%"
)
if defined NEWPID goto :ok
if !WAITED! lss 12 goto :poll

echo   [!] 等待 12 秒仍未检测到接收端。
if exist "%LAUNCHLOG%" (
    echo.
    echo   ---- 启动日志 ----
    powershell -NoProfile -Command "Get-Content -Path '%LAUNCHLOG%' -Tail 15" 2>nul
    echo   ------------------
    echo.
    echo   日志文件：%LAUNCHLOG%
)
goto :fail

:ok
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
echo.
pause
exit /b 0

:fail
echo.
echo   排查步骤：
echo     1. 直接双击 LanCmdAgent.exe —— 如果弹窗，会告诉你具体原因
echo     2. 查看日志：%LAUNCHLOG%
echo     3. 确认安全软件没有隔离 python 文件夹
echo.
pause
exit /b 1
