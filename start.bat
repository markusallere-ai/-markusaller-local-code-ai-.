@echo off
setlocal
pushd "%~dp0"
if errorlevel 1 (
  echo Projektordner konnte nicht geoeffnet werden.
  pause
  exit /b 1
)

echo Baue und starte Markusaller aus diesem Projektordner...
if not exist "%~dp0gradlew.bat" (
  echo Gradle-Wrapper fehlt: "%~dp0gradlew.bat"
  popd
  pause
  exit /b 1
)
if not exist "build" mkdir "build"
if not exist "build" (
  echo Build-Ordner konnte nicht angelegt werden.
  popd
  pause
  exit /b 1
)
set "logFile=%~dp0build\start.log"
call "%~dp0gradlew.bat" runnableJar --no-daemon > "%logFile%" 2>&1
if errorlevel 1 (
  type "%logFile%"
  echo Der Build ist fehlgeschlagen. Markusaller wurde nicht gestartet.
  popd
  pause
  exit /b 1
)
type "%logFile%"

if not exist "%~dp0Markusaller.jar" (
  echo Build erfolgreich, aber die aktuelle Markusaller.jar wurde nicht gefunden.
  popd
  pause
  exit /b 1
)

java -jar "%~dp0Markusaller.jar" >> "%logFile%" 2>&1
set "exitCode=%ERRORLEVEL%"
popd
if not "%exitCode%"=="0" (
  echo Markusaller wurde mit Exitcode %exitCode% beendet. Diagnose:
  type "%logFile%"
  pause
  exit /b %exitCode%
)
findstr /C:"MARKUSALLER_STARTUP_FAILURE" "%logFile%" >nul
if not errorlevel 1 (
  echo Das Fenster konnte nicht gestartet werden. Diagnose:
  type "%logFile%"
  pause
)
exit /b %exitCode%
