@echo off
rem Grab everything needed to debug a missed ad: run it WHILE the pause ad is on screen.
rem Output goes to debug\.
setlocal EnableExtensions
set "ROOT=%~dp0.."
cd /d "%ROOT%"

set "ADB=F:/Android/Sdk/platform-tools/adb.exe"
if not exist "%ADB%" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

"%ADB%" devices | findstr /r /c:"device$" >nul
if errorlevel 1 (
  echo [x] no device
  exit /b 1
)

if not exist debug mkdir debug

echo [1/6] device info
"%ADB%" shell getprop ro.product.model > debug\device.txt
"%ADB%" shell getprop ro.build.version.release >> debug\device.txt
"%ADB%" shell dumpsys package tv.danmaku.bili | findstr /i "versionName" >> debug\device.txt

echo [2/6] accessibility node tree (uiautomator)
"%ADB%" shell uiautomator dump --compressed /sdcard/pause.xml >nul 2>&1
"%ADB%" pull /sdcard/pause.xml debug\pause.xml >nul 2>&1
"%ADB%" shell rm /sdcard/pause.xml >nul 2>&1

echo [3/6] top activity and view hierarchy
"%ADB%" shell dumpsys activity top > debug\top.txt

echo [4/6] window list
"%ADB%" shell dumpsys window windows > debug\windows.txt

echo [5/6] SurfaceFlinger layers
"%ADB%" shell dumpsys SurfaceFlinger --list > debug\layers.txt

echo [6/6] screenshot
"%ADB%" exec-out screencap -p > debug\pause.png

echo.
echo [ok] generated:
dir /b debug
echo.
echo Send pause.xml / top.txt / pause.png to the maintainer.
endlocal
