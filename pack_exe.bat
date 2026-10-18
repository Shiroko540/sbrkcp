@echo off
rem ===========================================================================
rem  sbrkcp  ->  Windows self-contained test build (app-image + zip)
rem
rem  usage:
rem      pack_exe.bat            build jars, bundle a trimmed runtime, zip it
rem      pack_exe.bat samples    same, but also copy samples\ into the app image
rem
rem  requires: JDK 21+ (jlink / jpackage / jar). Set JAVA_HOME to that JDK,
rem            or edit JDK_DEFAULT below.
rem  note:     close any running sbrkcp GUI first. build.bat cannot overwrite
rem            dist\boot.jar while a window is open (this script rebuilds
rem            boot.jar from build\bootjar, so the packaging itself is fine,
rem            but the build step will fail).
rem ===========================================================================
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "JDK_DEFAULT=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
set "NAME=sbrkcp"
set "APPVER=0.0.1"
set "E=%ROOT%\build\exe"
set "OUT=%ROOT%\dist\exe"

set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jlink.exe" if exist "%JAVA_HOME%\bin\jpackage.exe" set "JDK=%JAVA_HOME%"
if not defined JDK if exist "%JDK_DEFAULT%\bin\jlink.exe" set "JDK=%JDK_DEFAULT%"
if not defined JDK (
    echo [ERROR] no JDK with jlink/jpackage found. Set JAVA_HOME to a JDK 21+.
    exit /b 1
)
echo using JDK: %JDK%

set "WITH_SAMPLES=0"
if /i "%~1"=="samples" set "WITH_SAMPLES=1"

echo.
echo [1/6] build jars
set "JAVA_HOME=%JDK%"
call "%ROOT%\build.bat"
if errorlevel 1 (
    echo [ERROR] build.bat failed - if a sbrkcp window is open, close it and retry.
    exit /b 1
)

echo.
echo [2/6] jlink runtime
if exist "%E%\runtime\bin\java.exe" (
    echo   reuse existing runtime in build\exe\runtime
) else (
    "%JDK%\bin\jlink.exe" --add-modules java.base,java.desktop,java.logging ^
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 ^
        --output "%E%\runtime"
    if errorlevel 1 goto fail
)

echo.
echo [3/6] stage layout
if exist "%E%\pkg" rd /s /q "%E%\pkg"
mkdir "%E%\pkg\input" 2>nul
mkdir "%E%\pkg\content" 2>nul
rem rebuild boot.jar from classes: avoids the file lock on dist\boot.jar
"%JDK%\bin\jar.exe" --create --file "%E%\pkg\input\boot.jar" --main-class sbrkcp.boot.BootMain -C "%ROOT%\build\bootjar" .
if errorlevel 1 goto fail
robocopy "%ROOT%\dist\main"  "%E%\pkg\content\main"  /E /NFL /NDL /NJH /NJS /NP >nul
robocopy "%ROOT%\dist\other" "%E%\pkg\content\other" /E /NFL /NDL /NJH /NJS /NP >nul

rem launcher properties: extra console launcher for CLI / selftest output
>  "%E%\cli.properties" echo main-jar=boot.jar
>> "%E%\cli.properties" echo main-class=sbrkcp.boot.BootMain
>> "%E%\cli.properties" echo win-console=true

echo.
echo [4/6] jpackage app-image
if not exist "%OUT%" mkdir "%OUT%"
if exist "%OUT%\%NAME%" rd /s /q "%OUT%\%NAME%"
"%JDK%\bin\jpackage.exe" --type app-image --name %NAME% --app-version %APPVER% ^
    --vendor "sbrkcp" --description "Rockchip firmware unpacker (test build)" ^
    --input "%E%\pkg\input" --app-content "%E%\pkg\content\main","%E%\pkg\content\other" ^
    --main-jar boot.jar --main-class sbrkcp.boot.BootMain ^
    --runtime-image "%E%\runtime" --dest "%OUT%" ^
    --add-launcher sbrkcp-cli="%E%\cli.properties"
if errorlevel 1 goto fail

echo.
echo [5/6] docs and samples
copy /y "%ROOT%\packaging\README_EXE.txt" "%OUT%\%NAME%\README_EXE.txt" >nul
if "%WITH_SAMPLES%"=="1" (
    if exist "%ROOT%\samples" robocopy "%ROOT%\samples" "%OUT%\%NAME%\samples" /E /NFL /NDL /NJH /NJS /NP >nul
)

echo.
echo [6/6] zip
if exist "%OUT%\%NAME%-%APPVER%Beta-win-x64.zip" del /q "%OUT%\%NAME%-%APPVER%Beta-win-x64.zip"
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "Compress-Archive -Path '%OUT%\%NAME%' -DestinationPath '%OUT%\%NAME%-%APPVER%Beta-win-x64.zip' -CompressionLevel Optimal -Force"
if errorlevel 1 goto fail

echo.
echo OK - app image : %OUT%\%NAME%
echo      zip       : %OUT%\%NAME%-%APPVER%Beta-win-x64.zip
echo quick check (no system Java needed):
echo      %OUT%\%NAME%\sbrkcp-cli.exe --list-modules
exit /b 0

:fail
echo.
echo [ERROR] packaging failed at the step above.
exit /b 1
