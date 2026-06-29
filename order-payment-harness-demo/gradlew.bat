@echo off
setlocal

set "GRADLE_VERSION=8.10.2"
set "BASE_DIR=%~dp0"
set "GRADLE_HOME=%BASE_DIR%.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin\gradle-%GRADLE_VERSION%"
set "GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat"
set "GRADLE_ZIP=%BASE_DIR%.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if not exist "%GRADLE_BIN%" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '%BASE_DIR%.gradle\wrapper\dists' | Out-Null; Invoke-WebRequest -Uri '%GRADLE_URL%' -OutFile '%GRADLE_ZIP%'; Expand-Archive -Force -Path '%GRADLE_ZIP%' -DestinationPath '%BASE_DIR%.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin'"
    if errorlevel 1 exit /b %errorlevel%
)

call "%GRADLE_BIN%" %*
exit /b %errorlevel%
