@echo off
cd /d "%~dp0"
python lan_command_agent.py --install-autostart
echo.
echo Added to HKCU\Software\Microsoft\Windows\CurrentVersion\Run\LanCmdAgent
pause
