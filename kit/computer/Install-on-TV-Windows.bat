@echo off
title MoLakaz TV - install from a computer
cd /d "%~dp0"
set ADB=%~dp0tools\windows\platform-tools\adb.exe
set KIT=%~dp0..
echo.
echo  MoLakaz TV - install from a computer
echo  ------------------------------------
echo  The TV box and this computer must be on the same Wi-Fi,
echo  and USB debugging must be on (see the guide, step 3).
echo.
set /p IP=  Type the TV box's IP address (TV: Settings ^> Network ^> Wi-Fi) and press Enter: 
"%ADB%" connect %IP%:5555
echo.
echo  Look at the TV: if it asks "Allow USB debugging?", tick "Always allow" and choose OK.
pause
"%ADB%" -s %IP%:5555 install -r "%KIT%\MoLakaz-TV.apk"
if exist "%KIT%\apps" for %%f in ("%KIT%\apps\*.apk") do "%ADB%" -s %IP%:5555 install -r "%%f"
echo  Copying the films (a few minutes)...
"%ADB%" -s %IP%:5555 shell mkdir -p /sdcard/Android/data/com.lagoon.home/files
for %%d in (clips blur ambient) do "%ADB%" -s %IP%:5555 push "%KIT%\films\%%d" /sdcard/Android/data/com.lagoon.home/files/
if exist "%KIT%\extra_apps.json" "%ADB%" -s %IP%:5555 push "%KIT%\extra_apps.json" /sdcard/Android/data/com.lagoon.home/files/
"%ADB%" -s %IP%:5555 push "%KIT%\films\scenes.json" /sdcard/Android/data/com.lagoon.home/files/
"%ADB%" -s %IP%:5555 shell am start -n com.lagoon.home/.HomeActivity
echo.
echo  Done here. Finish on the TV: MoLakaz TV is open, press OK to start the set-up.
pause
