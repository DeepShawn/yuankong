@echo off
cd /d "%~dp0"
echo ==========================================
echo  LanCmd agent - console mode
echo  Close this window to stop the agent.
echo ==========================================
python lan_command_agent.py --console %*
pause
