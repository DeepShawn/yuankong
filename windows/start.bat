@echo off
chcp 936 >nul 2>&1
setlocal enabledelayedexpansion
cd /d "%~dp0"
title LanCmd 启动器
set "PIDFILE=%APPDATA%\LanCmd\agent.pid"
set "LAUNCHLOG=%APPDATA%\LanCmd\launcher.log"

echo.
echo   LanCmd 局域网命令接收端
echo   ============================
echo.

if not exist "LanCmdAgent.exe" goto :noagent
if not exist "python\pythonw.exe" goto :nopy
if not exist "lan_command_agent.py" goto :noscript

set "OLDPID="
if exist "%PIDFILE%" set /p OLDPID=<"%PIDFILE%"
if defined OLDPID (
    tasklist /FI "PID eq !OLDPID!" 2>nul | find "!OLDPID!" >nul
    if not errorlevel 1 goto :already
)

echo   正在启动接收端...
start "" "LanCmdAgent.exe"

set "NEWPID="
set /a WAITED=0
:poll
timeout /t 1 >nul
set /a WAITED+=1
if exist "%PIDFILE%" set /p NEWPID=<"%PIDFILE%"
if defined NEWPID goto :ok
if !WAITED! lss 12 goto :poll

echo   [!] 等待 12 秒仍未检测到接收端。
echo.
if exist "%LAUNCHLOG%" (
    echo   ---- 启动日志 ----
    powershell -NoProfile -Command "Get-Content -LiteralPath '%LAUNCHLOG%' -Tail 20 -Encoding UTF8" 2>nul
    echo   ------------------
    echo.
)
echo   请双击 诊断.bat 逐项检查。
goto :end

:already
echo   接收端已经在运行 ^(PID !OLDPID!^)
echo   手机打开 LanCmd App 就能连上。
goto :end

:ok
echo   [OK] 已在后台运行 ^(PID !NEWPID!^)
echo.
echo   没有窗口是正常的，接收端就是静默运行。
echo.
echo   接下来：
echo     1. 手机连同一个网络（同一 Wi-Fi、电脑开的热点都行）
echo     2. 打开 LanCmd App，会自动发现这台电脑
echo     3. 电脑不会弹确认框，手机直接连上
echo.
echo   查看状态 / 停止 / 开机自启：双击 Start.exe
goto :end

:noagent
echo   [X] 找不到 LanCmdAgent.exe
echo       请完整解压整个文件夹。
goto :end

:nopy
echo   [X] 找不到 python\pythonw.exe
echo       解压不完整，或被安全软件隔离。
echo       请重新解压，并把整个文件夹加入杀毒软件信任。
goto :end

:noscript
echo   [X] 找不到 lan_command_agent.py
echo       请完整解压整个文件夹。
goto :end

:end
echo.
pause
endlocal
