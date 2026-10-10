@echo off
setlocal EnableExtensions

for %%I in ("%~dp0..\..") do set "REPO_ROOT=%%~fI"
cd /d "%REPO_ROOT%"

title XAN Repository Updater

echo.
echo ============================================
echo          XAN REPOSITORY UPDATER
echo ============================================
echo.

where git >nul 2>&1
if errorlevel 1 (
    echo ERROR: Git was not found in PATH.
    echo Install Git for Windows, then try again.
    goto :fail
)

git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not find the XAN Git repository.
    echo Expected repository root:
    echo %REPO_ROOT%
    goto :fail
)

git remote get-url origin >nul 2>&1
if errorlevel 1 (
    echo ERROR: This clone has no "origin" remote.
    goto :fail
)

echo Repository:
git remote get-url origin
echo.

for /f "delims=" %%C in ('git rev-parse --short HEAD 2^>nul') do set "OLD_COMMIT=%%C"
echo Current commit: %OLD_COMMIT%
echo.

echo Checking for local tracked changes...
git diff --quiet --ignore-submodules HEAD --
if errorlevel 1 (
    echo.
    echo ERROR: You have local changes to tracked files.
    echo Commit or stash them before updating so nothing is overwritten.
    echo.
    git status --short
    goto :fail
)

echo Fetching latest main...
git fetch origin main --prune
if errorlevel 1 (
    echo.
    echo ERROR: Could not fetch origin/main.
    goto :fail
)

echo Switching to main...
git switch main
if errorlevel 1 (
    echo.
    echo ERROR: Could not switch to the main branch.
    goto :fail
)

echo Updating main...
git pull --ff-only origin main
if errorlevel 1 (
    echo.
    echo ERROR: main could not be fast-forwarded.
    echo Your local branch may contain commits that are not on origin/main.
    goto :fail
)

echo Updating submodules...
git submodule sync --recursive
if errorlevel 1 goto :submodule_fail

git submodule update --init --recursive
if errorlevel 1 goto :submodule_fail

for /f "delims=" %%C in ('git rev-parse --short HEAD 2^>nul') do set "NEW_COMMIT=%%C"

echo.
echo ============================================
echo UPDATE COMPLETE
echo ============================================
echo.
echo Before: %OLD_COMMIT%
echo After:  %NEW_COMMIT%
echo.

if /I "%OLD_COMMIT%"=="%NEW_COMMIT%" (
    echo XAN was already up to date.
) else (
    echo XAN is now updated to the latest main branch.
)

echo.
pause
exit /b 0

:submodule_fail
echo.
echo ERROR: The main repository updated, but one or more submodules failed.
echo Run this command after fixing Git access:
echo   git submodule update --init --recursive
goto :fail

:fail
echo.
echo Update did not complete.
echo.
pause
exit /b 1
