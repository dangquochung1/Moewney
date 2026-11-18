@echo off
REM Build APK Meowney.
REM   build_apk.bat          -> release (da ky)
REM   build_apk.bat debug    -> debug
REM   build_apk.bat pack     -> dong goi lai asset roi build release
setlocal
cd /d "%~dp0"

set MODE=release
if /i "%1"=="debug" set MODE=debug

if /i "%1"=="pack" (
    echo === Dong goi asset ===
    python -I tools\pack_assets.py || goto :fail
)

if "%MODE%"=="debug" (
    call gradlew.bat :app:assembleDebug || goto :fail
) else (
    call gradlew.bat :app:assembleRelease || goto :fail
)

set OUT=app\build\outputs\apk\%MODE%
echo.
echo === Xong: %OUT% ===
dir /b "%OUT%\*.apk"
explorer "%OUT%"
pause
exit /b 0

:fail
echo.
echo *** BUILD LOI ***
pause
exit /b 1
