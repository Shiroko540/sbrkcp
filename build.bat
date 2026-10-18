@echo off
rem ============================================================
rem  sbrkcp build script (Windows / cmd)  -- ASCII only on purpose
rem    build.bat            compile + package into dist\
rem    build.bat selftest   compile + package + run baseline selftest
rem    build.bat clean      remove build output and jars
rem  Requires JDK 9+ (uses --release 8 to emit Java 8 bytecode)
rem ============================================================
setlocal enabledelayedexpansion
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

if defined JAVA_HOME (
  set "JAVAC=%JAVA_HOME%\bin\javac.exe"
  set "JAR=%JAVA_HOME%\bin\jar.exe"
  set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVAC=javac"
  set "JAR=jar"
  set "JAVA=java"
)

if /i "%~1"=="clean" goto :clean

echo [1/5] clean previous output
if exist "%ROOT%\build\bootjar" rmdir /s /q "%ROOT%\build\bootjar"
if exist "%ROOT%\build\mods"     rmdir /s /q "%ROOT%\build\mods"
if exist "%ROOT%\build\tmp"      rmdir /s /q "%ROOT%\build\tmp"
mkdir "%ROOT%\build\bootjar" 2>nul
mkdir "%ROOT%\build\tmp" 2>nul

echo [2/5] compile api / core / boot
if exist "%ROOT%\build\tmp\sources_boot.txt" del /q "%ROOT%\build\tmp\sources_boot.txt"
for /r "%ROOT%\src\api"  %%f in (*.java) do echo %%f>>"%ROOT%\build\tmp\sources_boot.txt"
for /r "%ROOT%\src\core" %%f in (*.java) do echo %%f>>"%ROOT%\build\tmp\sources_boot.txt"
for /r "%ROOT%\src\boot" %%f in (*.java) do echo %%f>>"%ROOT%\build\tmp\sources_boot.txt"

"%JAVAC%" --release 8 -encoding UTF-8 -nowarn -d "%ROOT%\build\bootjar" "@%ROOT%\build\tmp\sources_boot.txt"
if errorlevel 1 (
  echo     --release 8 unavailable, fallback to -source 8 -target 8
  "%JAVAC%" -source 8 -target 8 -encoding UTF-8 -nowarn -d "%ROOT%\build\bootjar" "@%ROOT%\build\tmp\sources_boot.txt"
)
if errorlevel 1 goto :fail

"%JAR%" --create --file "%ROOT%\dist\boot.jar" --main-class sbrkcp.boot.BootMain -C "%ROOT%\build\bootjar" .
if errorlevel 1 goto :fail

echo [3/5] compile modules and pack jars
call :build_module fuckrkw   "%ROOT%\dist\main\fuckrkw.jar"
if errorlevel 1 goto :fail
call :build_module fuckrfw   "%ROOT%\dist\main\fuckrfw.jar"
if errorlevel 1 goto :fail
call :build_module fuckrkimg "%ROOT%\dist\main\fuckrkimg.jar"
if errorlevel 1 goto :fail
call :build_module fuckbmp0  "%ROOT%\dist\other\fuckbmp0.jar"
if errorlevel 1 goto :fail

echo [4/5] check artifacts
if not exist "%ROOT%\dist\main\fuckit\rockchip" mkdir "%ROOT%\dist\main\fuckit\rockchip"
if not exist "%ROOT%\dist\main\fuckit\rockchip\.gitkeep" type nul > "%ROOT%\dist\main\fuckit\rockchip\.gitkeep"
for %%f in ("%ROOT%\dist\boot.jar" "%ROOT%\dist\main\fuckrkw.jar" "%ROOT%\dist\main\fuckrfw.jar" "%ROOT%\dist\main\fuckrkimg.jar" "%ROOT%\dist\other\fuckbmp0.jar") do (
  for %%s in (%%f) do echo     %%~nxs  %%~zs bytes
)

echo [5/5] module load check
"%JAVA%" -Dfile.encoding=UTF-8 -Dsbrkcp.base="%ROOT%\dist" -jar "%ROOT%\dist\boot.jar" --list-modules

if /i "%~1"=="selftest" (
  echo.
  echo == selftest ==
  "%JAVA%" -Dfile.encoding=UTF-8 -Dsbrkcp.base="%ROOT%\dist" -jar "%ROOT%\dist\boot.jar" --selftest --samples "%ROOT%\samples"
  if errorlevel 1 exit /b 4
)

echo.
echo build done: %ROOT%\dist
exit /b 0

:build_module
set "MOD=%~1"
set "OUTJAR=%~2"
set "SRCDIR=%ROOT%\src\modules\%MOD%"
set "RESDIR=%SRCDIR%\resources"
set "OD=%ROOT%\build\mods\%MOD%"
mkdir "%OD%" 2>nul
if exist "%ROOT%\build\tmp\sources_%MOD%.txt" del /q "%ROOT%\build\tmp\sources_%MOD%.txt"
for /r "%SRCDIR%" %%f in (*.java) do echo %%f>>"%ROOT%\build\tmp\sources_%MOD%.txt"
"%JAVAC%" --release 8 -encoding UTF-8 -nowarn -cp "%ROOT%\dist\boot.jar" -d "%OD%" "@%ROOT%\build\tmp\sources_%MOD%.txt"
if errorlevel 1 exit /b 1
if exist "%RESDIR%" xcopy /e /i /y /q "%RESDIR%\*" "%OD%" >nul
"%JAR%" --create --file "%OUTJAR%" -C "%OD%" .
if errorlevel 1 exit /b 1
echo     %MOD% -^> %OUTJAR%
exit /b 0

:clean
echo clean build\ and jars
if exist "%ROOT%\build\bootjar" rmdir /s /q "%ROOT%\build\bootjar"
if exist "%ROOT%\build\mods"     rmdir /s /q "%ROOT%\build\mods"
if exist "%ROOT%\build\tmp"      rmdir /s /q "%ROOT%\build\tmp"
del /q "%ROOT%\dist\boot.jar" 2>nul
del /q "%ROOT%\dist\main\fuckrkw.jar"   2>nul
del /q "%ROOT%\dist\main\fuckrfw.jar"   2>nul
del /q "%ROOT%\dist\main\fuckrkimg.jar" 2>nul
del /q "%ROOT%\dist\other\fuckbmp0.jar" 2>nul
echo done
exit /b 0

:fail
echo BUILD FAILED - see compiler output above.
exit /b 1
