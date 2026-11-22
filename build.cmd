@echo off
rem Builds JacCollector without a Gradle wrapper.
rem   build.cmd           -> builds\JacCollector-debug.apk
rem   build.cmd release   -> builds\JacCollector-<version>.apk (signed if keystore.properties exists)
rem Needs JDK 17-21 and Gradle 8.x. Set JAVA_HOME and GRADLE_BAT, or put them in local-env.cmd
rem (not in git), e.g.:
rem   set "JAVA_HOME=C:\jdk-21"
rem   set "GRADLE_BAT=C:\gradle-8.13\bin\gradle.bat"
rem The Android SDK path goes to local.properties: sdk.dir=C\:\\Android\\sdk

setlocal
set "ROOT=%~dp0"
if exist "%ROOT%local-env.cmd" call "%ROOT%local-env.cmd"
if not defined GRADLE_BAT set "GRADLE_BAT=gradle"

rem Some Windows setups block AF_UNIX sockets in %TEMP%; keep them in .tmp instead.
set "TMPDIR_OPT=-Djdk.net.unixdomain.tmpdir=%ROOT%.tmp"
set "JAVA_TOOL_OPTIONS=%TMPDIR_OPT%"
set "GRADLE_OPTS=%TMPDIR_OPT%"
if not exist "%ROOT%.tmp" mkdir "%ROOT%.tmp"

set "TASK=assembleDebug"
if /i "%~1"=="release" set "TASK=assembleRelease"

call "%GRADLE_BAT%" -p "%ROOT%." "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 %TMPDIR_OPT%" "-Dkotlin.daemon.jvmargs=-Xmx1536m %TMPDIR_OPT%" %TASK% %2 %3
if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)
if not exist "%ROOT%builds" mkdir "%ROOT%builds"
if /i "%~1"=="release" (
  for %%f in ("%ROOT%app\build\outputs\apk\release\*.apk") do copy /y "%%f" "%ROOT%builds\JacCollector-release.apk" >nul
  echo === DONE: builds\JacCollector-release.apk ===
) else (
  copy /y "%ROOT%app\build\outputs\apk\debug\app-debug.apk" "%ROOT%builds\JacCollector-debug.apk" >nul
  echo === DONE: builds\JacCollector-debug.apk ===
)
