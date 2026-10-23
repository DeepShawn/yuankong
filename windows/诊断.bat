@echo off
chcp 936 >nul 2>&1
setlocal
cd /d "%~dp0"
title LanCmd 诊断
set "APP=%APPDATA%\LanCmd"

echo ============================================
echo   LanCmd 诊断工具
echo ============================================
echo.

echo [1] 文件完整性
for %%F in (LanCmdAgent.exe LanCmdCtl.exe Start.exe 启动.vbs lan_command_agent.py) do (
    if exist "%%F" (echo     OK   %%F) else (echo     MISS %%F)
)
for %%F in (python\python.exe python\pythonw.exe python\python311.dll python\python311.zip python\python311._pth) do (
    if exist "%%F" (echo     OK   %%F) else (echo     MISS %%F)
)
echo.

echo [2] Python 运行时
if not exist "python\python.exe" (
    echo     SKIP 缺少 python\python.exe
    goto :direct
)
python\python.exe -c "import sys,json,socket,subprocess,ctypes,hashlib,base64,logging;print('    Python',sys.version.split()[0],'模块齐全 OK')"
if errorlevel 1 (
    echo     [X] Python 无法运行，上面就是原因
    echo         常见：安全软件隔离了 python 文件夹，或解压不完整
)
echo.

:direct
echo [3] 直接用 pythonw 启动一次（绕过 LanCmdAgent.exe，验证问题在哪一层）
if exist "python\pythonw.exe" (
    python\pythonw.exe lan_command_agent.py --status
    if errorlevel 1 (
        echo     [X] pythonw 直接启动失败，说明是运行时/脚本的问题
    ) else (
        echo     [OK] pythonw 能正常启动脚本，问题在 LanCmdAgent.exe 这一层
    )
) else (
    echo     SKIP 缺少 python\pythonw.exe
)
echo.

echo [4] 启动日志
if exist "%APP%\launcher.log" (
    powershell -NoProfile -Command "Get-Content -LiteralPath '%APP%\launcher.log' -Tail 30 -Encoding UTF8" 2>nul
) else (
    echo     没有 launcher.log
)
echo.

echo [5] 端口占用
netstat -ano | findstr ":47823 :47824"
if errorlevel 1 echo     47823 / 47824 都没有被占用
echo.

echo [6] 防火墙
netsh advfirewall firewall show rule name=LanCmd-TCP 2>nul | findstr /C:"47823" >nul
if errorlevel 1 (echo     LanCmd-TCP 未放行) else (echo     LanCmd-TCP 已放行)
netsh advfirewall firewall show rule name=LanCmd-UDP 2>nul | findstr /C:"47824" >nul
if errorlevel 1 (echo     LanCmd-UDP 未放行) else (echo     LanCmd-UDP 已放行)
echo.

echo ============================================
echo   诊断结束，把上面内容截图发出来即可。
echo ============================================
pause
endlocal
