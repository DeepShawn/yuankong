@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo  LanCmd Agent - Windows EXE builder
echo ============================================
echo.

where python >nul 2>nul
if errorlevel 1 (
    echo [X] Python not found.
    echo     Install Python 3.8+ from https://www.python.org/downloads/
    echo     and tick "Add python.exe to PATH" during install.
    pause
    exit /b 1
)

for /f "tokens=2 delims= " %%v in ('python -V 2^>^&1') do set PYVER=%%v
echo [i] Python %PYVER%

echo.
echo [0/3] Building the Windows capture helper ...
where cl >nul 2>nul
if not errorlevel 1 (
    cl /nologo /O2 /EHsc /std:c++17 /Fe:LanCmdCapture.exe capture.cpp /link windowscodecs.lib mf.lib mfreadwrite.lib mfplat.lib mfuuid.lib ole32.lib gdi32.lib user32.lib uuid.lib
    if errorlevel 1 exit /b 1
    cl /nologo /O2 /EHsc /std:c++17 /Fe:LanCmdRecord.exe record.cpp /link mf.lib mfreadwrite.lib mfplat.lib mfuuid.lib ole32.lib gdi32.lib user32.lib winmm.lib uuid.lib
    if errorlevel 1 exit /b 1
)
if not exist LanCmdRecord.exe (
    echo [X] Build or copy LanCmdRecord.exe from the complete Windows ZIP.
    exit /b 1
)
if not exist LanCmdCapture.exe (
    echo [X] Run in an x64 Native Tools Command Prompt with the Windows SDK,
    echo     or copy LanCmdCapture.exe from the official 1.5.0 Windows ZIP here.
    pause
    exit /b 1
)

echo.
echo [1/3] Installing PyInstaller ...
python -m pip install --upgrade pyinstaller
if errorlevel 1 (
    echo [X] pip install failed. Try a mirror:
    echo     python -m pip install pyinstaller -i https://pypi.tuna.tsinghua.edu.cn/simple
    pause
    exit /b 1
)

echo.
echo [2/3] Building LanCmdAgent.exe (silent, no console) ...
python -m PyInstaller --noconfirm --clean --onefile --noconsole ^
    --name LanCmdAgent ^
    --icon icon.ico ^
    --version-file version_info.txt ^
    --hidden-import remote_features ^
    --hidden-import system_controls ^
    --hidden-import input_guard ^
    --hidden-import device_management ^
    --hidden-import touch_guard ^
    --hidden-import file_actions ^
    --hidden-import desktop_apps ^
    --hidden-import command_queue ^
    --hidden-import recordings ^
    --hidden-import gestures ^
    --hidden-import text_editor ^
    --hidden-import app_locks ^
    --hidden-import registry_editor ^
    --hidden-import task_manager ^
    --hidden-import browser_control ^
    --hidden-import script_runner ^
    --hidden-import privacy_devices ^
    --hidden-import placeholder_files ^
    --hidden-import tkinter ^
    --hidden-import tkinter.messagebox ^
    lan_command_agent.py
if errorlevel 1 (
    echo [X] Build failed.
    pause
    exit /b 1
)

echo.
echo [3/3] Building LanCmdCtl.exe (console admin tool) ...
python -m PyInstaller --noconfirm --clean --onefile --console ^
    --name LanCmdCtl ^
    --icon icon.ico ^
    --version-file version_info.txt ^
    --hidden-import remote_features ^
    --hidden-import system_controls ^
    --hidden-import input_guard ^
    --hidden-import device_management ^
    --hidden-import touch_guard ^
    --hidden-import file_actions ^
    --hidden-import desktop_apps ^
    --hidden-import command_queue ^
    --hidden-import recordings ^
    --hidden-import gestures ^
    --hidden-import text_editor ^
    --hidden-import app_locks ^
    --hidden-import registry_editor ^
    --hidden-import task_manager ^
    --hidden-import browser_control ^
    --hidden-import script_runner ^
    --hidden-import privacy_devices ^
    --hidden-import placeholder_files ^
    lan_command_agent.py
if errorlevel 1 (
    echo [!] Control exe failed, main agent is still usable.
)

echo.
echo ============================================
if exist dist\LanCmdAgent.exe copy /y LanCmdCapture.exe dist\LanCmdCapture.exe >nul
if exist dist\LanCmdAgent.exe copy /y LanCmdRecord.exe dist\LanCmdRecord.exe >nul
echo  Done.
echo ============================================
echo  dist\LanCmdAgent.exe   <- run this (no window, background)
echo  dist\LanCmdCtl.exe     <- admin tool (status / stop / autostart)
echo.
echo  First run:
echo     dist\LanCmdAgent.exe
echo  Enable autostart:
echo     dist\LanCmdCtl.exe --install-autostart
echo  Check status:
echo     dist\LanCmdCtl.exe --status
echo  Stop:
echo     dist\LanCmdCtl.exe --stop
echo.
echo  Note: LanCmdAgent.exe has no console, so its own --status / --stop
echo        produce no visible output. Use LanCmdCtl.exe for that.
echo.

if exist dist\LanCmdAgent.exe (
    for %%F in (dist\LanCmdAgent.exe) do echo [OK] LanCmdAgent.exe = %%~zF bytes
)
pause
