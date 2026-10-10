@echo off
setlocal EnableExtensions
cd /d "%~dp0..\.." || exit /b 1
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if defined ANDROID_HOME set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not exist "%ADB%" (
    echo Android platform-tools were not found. Install them using Android Studio SDK Manager.
    pause
    exit /b 1
)
if not exist "dist\diagnostics" mkdir "dist\diagnostics"
echo Connect your phone, enable USB debugging, and unlock it.
"%ADB%" start-server
"%ADB%" devices -l
echo.
echo Approve the USB debugging prompt on your phone if one appears.
pause
"%ADB%" devices -l > "dist\diagnostics\devices.txt"
type "dist\diagnostics\devices.txt"
set "PHONE="
set "MULTIPLE="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do if "%%B"=="device" (
    if defined PHONE set "MULTIPLE=1"
    set "PHONE=%%A"
)
if not defined PHONE (
    echo No authorized phone found. Check the USB cable and approve USB debugging.
    pause
    exit /b 1
)
if defined MULTIPLE (
    echo Connect just one phone or emulator, then run this script again.
    pause
    exit /b 1
)
echo Phone connected: %PHONE%
"%ADB%" -s "%PHONE%" shell getprop ro.product.model > "dist\diagnostics\phone.txt"
"%ADB%" -s "%PHONE%" shell getprop ro.build.version.release >> "dist\diagnostics\phone.txt"
"%ADB%" -s "%PHONE%" shell dumpsys package app.xan.music | findstr /C:"versionName=" /C:"versionCode=" /C:"POST_NOTIFICATIONS" > "dist\diagnostics\xan-installed.txt"
echo.
echo Diagnostics saved in dist\diagnostics. Keep the phone connected.
echo Return to Codex and say: debugging is ready.
pause
exit /b 0
