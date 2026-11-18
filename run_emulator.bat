@echo off
REM Chay Meowney tren may ao de thu (ban debug).
REM   run_emulator.bat         -> bat may ao (neu chua bat), build debug, cai, mo app
REM   run_emulator.bat fast    -> bo qua build, chi cai lai APK da co va mo app
REM Sua mau o: app\src\main\java\app\meowney\core\ui\Theme.kt (object Pal)
setlocal
cd /d "%~dp0"

set SDK=%LOCALAPPDATA%\Android\Sdk
set ADB=%SDK%\platform-tools\adb.exe
set EMU=%SDK%\emulator\emulator.exe
set AVD=meowney_test
set APK=app\build\outputs\apk\debug\app-debug.apk

REM 1. Bat may ao neu chua co thiet bi nao
"%ADB%" start-server >nul 2>&1
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo === Bat may ao %AVD% ===
    start "" "%EMU%" -avd %AVD% -gpu host -memory 4096 -cores 4 -no-snapshot-save -no-boot-anim
)

REM 2. Build trong luc may ao khoi dong
if /i not "%1"=="fast" (
    echo === Build debug ===
    call gradlew.bat :app:assembleDebug || goto :fail
)

REM 3. Doi may ao khoi dong xong
echo === Doi may ao san sang ===
"%ADB%" wait-for-device
:waitboot
for /f "delims=" %%b in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%b
if not "%BOOT%"=="1" (
    timeout /t 2 /nobreak >nul
    goto :waitboot
)

REM 4. Cai va mo app
echo === Cai app ===
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo.
    echo Cai that bai. Neu bao "signatures do not match": may ao dang co ban release.
    echo Go app tren may ao ^(mat du lieu thu^) bang lenh:
    echo     "%ADB%" uninstall app.meowney
    echo roi chay lai file nay.
    goto :fail
)
"%ADB%" shell am force-stop app.meowney
"%ADB%" shell monkey -p app.meowney -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo.
echo === Xong: app dang mo tren may ao ===
pause
exit /b 0

:fail
echo.
echo *** LOI ***
pause
exit /b 1
