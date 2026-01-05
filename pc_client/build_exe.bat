@echo off
echo Starting Build Process...
cd /d "%~dp0"
python -m PyInstaller GameWatchClient.spec --noconfirm --clean
if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b %errorlevel%
)
echo Build successful! Executable is in dist folder.
pause
