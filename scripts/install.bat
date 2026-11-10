@echo off
rem Install APK -> allow restricted settings (Android 13+) -> add to Doze whitelist
rem -> enable accessibility -> launch.
rem ASCII only: cmd parses .bat as the OEM code page, non-ASCII bytes can break the parser.
setlocal EnableExtensions
set "ROOT=%~dp0.."
cd /d "%ROOT%"

set "ADB=F:/Android/Sdk/platform-tools/adb.exe"
if not exist "%ADB%" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

set "APK="
for %%f in ("out\BiliPauseAd-*.apk") do set "APK=%%~ff"
if not defined APK (
  echo [x] no apk in out\ , run build.bat first
  exit /b 1
)

"%ADB%" devices | findstr /r /c:"device$" >nul
if errorlevel 1 (
  echo [x] no device. Plug in USB and allow debugging.
  exit /b 1
)

echo [1/5] install %APK%
"%ADB%" install -r "%APK%"
if errorlevel 1 exit /b 1

echo [2/5] allow restricted settings (Android 13+)
"%ADB%" shell appops set com.luoqiang.bilipause ACCESS_RESTRICTED_SETTINGS allow >nul 2>&1

echo [3/5] add to Doze whitelist (battery optimization off)
"%ADB%" shell dumpsys deviceidle whitelist +com.luoqiang.bilipause >nul 2>&1

echo [4/5] enable accessibility service
call "%~dp0enable-acc.bat"

echo [5/5] launch
"%ADB%" shell am start -n com.luoqiang.bilipause/.MainActivity >nul

echo.
echo [ok] done. For vivo/OPPO/Xiaomi also allow auto-start + background high power usage
echo      in the vendor app manager, so the service comes back after reboot.
endlocal
