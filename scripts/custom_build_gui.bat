@echo off
where py >nul 2>&1
if not errorlevel 1 (
  py -3 "%~dp0custom_build_gui.py"
  exit /b %errorlevel%
)
python "%~dp0custom_build_gui.py"
