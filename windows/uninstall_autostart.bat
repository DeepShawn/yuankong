@echo off
cd /d "%~dp0"
python lan_command_agent.py --uninstall-autostart
echo.
pause
