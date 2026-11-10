@echo off
rem =====================================================================
rem  BiliPause - build script (no Gradle, no Android Studio required)
rem  aapt2 compile -> aapt2 link -> javac(JDK8) -> d8(JRE17) -> jar
rem  -> zipalign -> apksigner
rem =====================================================================
setlocal
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
cd /d "%ROOT%"

rem ---- version ----
set "VC=5"
set "VN=1.4"
set "APKNAME=BiliPauseAd-%VN%.apk"

rem ---- toolchain ----
set "SDK=F:\Android\Sdk"
if not exist "%SDK%\platforms\android-34\android.jar" set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "BT=%SDK%\build-tools\34.0.0"
set "AJAR=%SDK%\platforms\android-34\android.jar"
set "D8JAR=%BT%\lib\d8.jar"
set "SIGNER=%BT%\lib\apksigner.jar"
set "JRE=%ROOT%\tools\jre17"
set "JAVA=%JRE%\bin\java.exe"
set "JDK8=D:\Java\jdk-1.8"
if not exist "%JDK8%\bin\javac.exe" set "JDK8=%JAVA_HOME%"
set "KS=%ROOT%\keystore\bilipause.jks"
set "KSPASS=bilipause123"

echo.
echo ==== [0/7] check toolchain ====
if not exist "%AJAR%" ( echo MISSING: %AJAR% & goto fail )
if not exist "%JAVA%" ( echo MISSING: %JAVA% & goto fail )
if not exist "%JDK8%\bin\javac.exe" ( echo MISSING: javac & goto fail )
echo SDK  = %SDK%
echo JDK8 = %JDK8%
echo JRE  = %JRE%

echo.
echo ==== [1/7] clean ====
if exist build rmdir /s /q build
mkdir build\gen 2>nul
mkdir build\classes 2>nul
mkdir build\dex 2>nul
if not exist out mkdir out

echo.
echo ==== [2/7] aapt2 compile ====
"%BT%\aapt2.exe" compile --dir res -o build\res.zip
if errorlevel 1 goto fail

echo.
echo ==== [3/7] aapt2 link ====
"%BT%\aapt2.exe" link -o build\base.apk -I "%AJAR%" ^
  --manifest AndroidManifest.xml ^
  -R build\res.zip ^
  --java build\gen ^
  --min-sdk-version 24 ^
  --target-sdk-version 34 ^
  --version-code %VC% ^
  --version-name %VN% ^
  --auto-add-overlay
if errorlevel 1 goto fail

echo.
echo ==== [4/7] javac ====
dir /s /b src\*.java > build\src.txt
dir /s /b build\gen\*.java > build\gen.txt
type build\src.txt build\gen.txt > build\allsrc.txt
"%JDK8%\bin\javac.exe" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn ^
  -bootclasspath "%AJAR%" -d build\classes @build\allsrc.txt
if errorlevel 1 goto fail

echo.
echo ==== [5/7] d8 (JRE17) ====
"%JDK8%\bin\jar.exe" cf build\classes.jar -C build\classes .
if errorlevel 1 goto fail
"%JAVA%" -cp "%D8JAR%" com.android.tools.r8.D8 --release --min-api 24 ^
  --lib "%AJAR%" --output build\dex build\classes.jar
if errorlevel 1 goto fail
if not exist build\dex\classes.dex ( echo MISSING classes.dex & goto fail )

echo.
echo ==== [6/7] package + zipalign ====
copy /y build\base.apk build\unsigned.apk >nul
"%JDK8%\bin\jar.exe" uf build\unsigned.apk -C build\dex classes.dex
if errorlevel 1 goto fail
"%BT%\zipalign.exe" -f -p 4 build\unsigned.apk build\aligned.apk
if errorlevel 1 goto fail

echo.
echo ==== [7/7] sign ====
if not exist "%KS%" (
  echo generating keystore ...
  "%JDK8%\bin\keytool.exe" -genkeypair -keystore "%KS%" -alias bilipause ^
    -keyalg RSA -keysize 2048 -validity 10000 ^
    -storepass %KSPASS% -keypass %KSPASS% -storetype JKS ^
    -dname "CN=BiliPause,OU=Personal,O=Personal,L=Chengdu,ST=Sichuan,C=CN"
  if errorlevel 1 goto fail
)
"%JAVA%" -jar "%SIGNER%" sign --ks "%KS%" --ks-pass pass:%KSPASS% --key-pass pass:%KSPASS% ^
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true ^
  --min-sdk-version 24 --out "out\%APKNAME%" build\aligned.apk
if errorlevel 1 goto fail

"%JAVA%" -jar "%SIGNER%" verify --min-sdk-version 24 "out\%APKNAME%"
if errorlevel 1 goto fail

echo.
echo ==== DONE ====
for %%F in ("out\%APKNAME%") do echo APK: %%~fF  (%%~zF bytes)
goto end

:fail
echo.
echo **** BUILD FAILED ****
exit /b 1

:end
endlocal
