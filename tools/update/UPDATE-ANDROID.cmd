@echo off
setlocal EnableExtensions
cd /d "%~dp0..\.." || exit /b 1
if not defined JAVA_HOME for /d %%J in ("%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21*") do set "JAVA_HOME=%%~fJ"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0update_android.ps1" %*
set "UPDATE_EXIT=%ERRORLEVEL%"
echo.
echo Android toolchain reports are in dist\updates\android.
pause
exit /b %UPDATE_EXIT%
