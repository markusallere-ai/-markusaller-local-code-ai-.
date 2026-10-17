@echo off
setlocal
set "report=%~dp0CODE_LINES.txt"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Count-CodeLines.ps1" -ReportPath "%report%"
if errorlevel 1 (
  echo Der Codezeilen-Bericht konnte nicht erstellt werden.
  pause
  exit /b 1
)

start "" notepad.exe "%report%"
