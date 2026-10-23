@echo off
chcp 65001 >nul 2>&1
setlocal
cd /d "%~dp0"
title LanCmd 诊断
set "APP=%APPDATA%\LanCmd"

echo ============================================
echo   LanCmd 诊断工具
echo ============================================
echo.

echo [1] 检查文件是否完整
for %%F in (LanCmdAgent.exe LanCmdCtl.exe Start.exe lan_command_agent.py) do (
    if exist "%%F" (echo     OK   %%F) else (echo     MISS %%F)
)
if exist "python\python.exe"   (echo     OK   python\python.exe)   else (echo     MISS python\python.exe)
if exist "python\pythonw.exe"  (echo     OK   python\pythonw.exe)  else (echo     MISS python\pythonw.exe)
if exist "python\python311.dll" (echo     OK   python\python311.dll) else (echo     MISS python\python311.dll)
if exist "python\python311.zip" (echo     OK   python\python311.zip) else (echo     MISS python\python311.zip)
echo.

echo [2] 检查 Python 运行时能否启动
if not exist "python\python.exe" (
    echo     SKIP 缺少 python\python.exe
    goto :logs
)
python\python.exe -c "import sys,json,socket,subprocess,ctypes,hashlib;print('    Python', sys.version.split()[0], 'OK')"
if errorlevel 1 (
    echo     [X] Python 无法运行，上面就是错误原因
    echo         常见原因：安全软件隔离了 python 文件夹，或解压不完整
)
echo.

echo [3] 直接前台运行接收端（能看到全部输出）
echo     ----------------------------------------------------
python lan_command_agent.py --status
echo     ----------------------------------------------------
echo.

:logs
echo [4] 启动日志
if exist "%APP%\launcher.log" (
    echo     ---- launcher.log 末尾 ----
    powershell -NoProfile -Command "Get-Content -Path '%APP%\launcher.log' -Tail 20" 2>nul
) else (
    echo     没有 launcher.log（说明还没通过 exe 启动过）
)
echo.
if exist "%APP%\agent.log" (
    echo     ---- agent.log 末尾 ----
    powershell -NoProfile -Command "Get-Content -Path '%APP%\agent.log' -Tail 20" 2>nul
) else (
    echo     没有 agent.log
)
echo.

echo [5] 端口占用情况
netstat -ano | findstr ":47823 :47824"
if errorlevel 1 echo     47823 / 47824 都没有被占用
echo.

echo [6] 防火墙状态
netsh advfirewall firewall show rule name=LanCmd-TCP 2>nul | findstr /C:"47823" >nul
if errorlevel 1 (echo     LanCmd-TCP 规则：未放行) else (echo     LanCmd-TCP 规则：已放行)
netsh advfirewall firewall show rule name=LanCmd-UDP 2>nul | findstr /C:"47824" >nul
if errorlevel 1 (echo     LanCmd-UDP 规则：未放行) else (echo     LanCmd-UDP 规则：已放行)
echo.

echo ============================================
echo   诊断结束。把上面内容整个截图发出来即可。
echo ============================================
pause
