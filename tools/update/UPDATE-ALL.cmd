@echo off
setlocal EnableExtensions
cd /d "%~dp0..\.." || exit /b 1
if not defined JAVA_HOME for /d %%J in ("%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21*") do set "JAVA_HOME=%%~fJ"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if exist ".gradle-local" set "GRADLE_USER_HOME=%CD%\.gradle-local"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0update_all.ps1" %*
set "UPDATE_EXIT=%ERRORLEVEL%"
echo.
if not "%UPDATE_EXIT%"=="0" echo Update verification failed. Check dist\updates for the report.
pause
exit /b %UPDATE_EXIT%
