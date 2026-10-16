@echo off
REM Markusaller launcher: startet das fat JAR, wenn vorhanden
setlocal
if exist "%~dp0Markusaller.jar" (
  java -jar "%~dp0Markusaller.jar"
  goto :eof
)
if exist "%~dp0build\libs\Markusaller.jar" (
  java -jar "%~dp0build\libs\Markusaller.jar"
  goto :eof
)
echo Markusaller.jar nicht gefunden.
echo Baue zuerst das JAR mit: gradle shadowJar
pause
endlocal
