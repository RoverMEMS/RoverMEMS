@echo off
setlocal
title RoverMEMS - Arduino IDE setup for Windows user names with Japanese characters
echo.
echo ================================================================
echo  RoverMEMS: Arduino IDE setup
echo  (for Windows user names that contain Japanese characters)
echo ================================================================
echo.

rem --- 1. Is this setup needed? (user folder = English letters/digits only?) ---
powershell -NoProfile -Command "if ($env:USERPROFILE -match '^[A-Za-z0-9_:\\.\-]+$') { exit 0 } else { exit 1 }"
if not %errorlevel%==0 goto needed
echo Your user folder name uses only English letters and numbers:
echo   %USERPROFILE%
echo.
echo You do NOT need this setup. Nothing was changed.
echo Please go back to the README and continue with the next step.
echo.
pause
exit /b 0

:needed
echo Your user folder name contains Japanese characters, spaces or symbols.
echo This tool moves the Arduino IDE work folders to C:\Arduino15
echo so that the build can finish.
echo.

rem --- 2. Arduino IDE must be closed ---
:checkide
tasklist /FI "IMAGENAME eq Arduino IDE.exe" 2>nul | find /I "Arduino IDE.exe" >nul
if not %errorlevel%==0 goto ideclosed
echo Arduino IDE is running. Please close it completely
echo (also check the taskbar), then press any key.
pause >nul
goto checkide

:ideclosed
rem --- 3. Create folders ---
if not exist "C:\Arduino15" mkdir "C:\Arduino15"
if not exist "C:\Arduino15" goto mkfail
set "CFGDIR=%USERPROFILE%\.arduinoIDE"
if not exist "%CFGDIR%" mkdir "%CFGDIR%"
set "CFG=%CFGDIR%\arduino-cli.yaml"

rem --- 4. Already done? ---
if not exist "%CFG%" goto writecfg
findstr /C:"C:\Arduino15" "%CFG%" >nul
if %errorlevel%==0 goto already

rem --- 5. Back up the old settings (only the first time) ---
if not exist "%CFG%.bak" copy "%CFG%" "%CFG%.bak" >nul
echo The old settings were saved as arduino-cli.yaml.bak

:writecfg
> "%CFG%" echo board_manager:
>> "%CFG%" echo     additional_urls: []
>> "%CFG%" echo directories:
>> "%CFG%" echo     data: C:\Arduino15
>> "%CFG%" echo     downloads: C:\Arduino15\staging
>> "%CFG%" echo     user: C:\Arduino15\sketchbook
>> "%CFG%" echo build_cache:
>> "%CFG%" echo     path: C:\Arduino15\build_cache
echo.
echo Done. The following settings were written:
echo ----------------------------------------
type "%CFG%"
echo ----------------------------------------
echo.
echo Next: start Arduino IDE and continue with the README.
echo.
pause
exit /b 0

:already
echo.
echo The settings are already in place. Nothing was changed.
echo.
pause
exit /b 0

:mkfail
echo.
echo Could not create C:\Arduino15. Nothing was changed.
echo Please create the folder by hand and run this file again.
echo.
pause
exit /b 1
