@echo off
cd /d "%~dp0"
python lan_command_agent.py --stop
timeout /t 2 >nul
