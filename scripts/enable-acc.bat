@echo off
rem Enable the accessibility service over adb (no root).
rem IMPORTANT: 'settings put' overwrites the whole list, so we read the current
rem value first and append, otherwise other accessibility services get wiped.
setlocal EnableExtensions

set "PKG=com.luoqiang.bilipause"
set "CLS=com.luoqiang.bilipause/com.luoqiang.bilipause.AdGuardService"

set "ADB=F:/Android/Sdk/platform-tools/adb.exe"
if not exist "%ADB%" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

"%ADB%" devices | findstr /r /c:"device$" >nul
if errorlevel 1 (
  echo [x] No device. Plug in USB, enable USB debugging, allow the prompt.
  exit /b 1
)

set "CUR="
for /f "usebackq delims=" %%i in (`"%ADB%" shell settings get secure enabled_accessibility_services`) do set "CUR=%%i"
if "%CUR%"=="null" set "CUR="
if "%CUR%"=="Null" set "CUR="

echo [i] current: %CUR%

set "NEW=%CUR%"
echo %CUR% | findstr /i /c:"%PKG%" >nul
if errorlevel 1 (
  if defined CUR (set "NEW=%CUR%:%CLS%") else (set "NEW=%CLS%")
)

echo [i] target : %NEW%
"%ADB%" shell settings put secure enabled_accessibility_services "%NEW%"
"%ADB%" shell settings put secure accessibility_enabled 1

rem Android 13+: side-loaded apps get a greyed-out accessibility toggle.
"%ADB%" shell appops set %PKG% ACCESS_RESTRICTED_SETTINGS allow >nul 2>&1

echo [i] verify :
"%ADB%" shell settings get secure enabled_accessibility_services

echo [ok] done. If the system still shows it as off, open Settings - Accessibility
echo      - Downloaded services and turn on BiliPause manually.
endlocal
