@echo off
setlocal DisableDelayedExpansion
rem Keep JVM file/cache options scoped to this per-user installation.
set "JAVA_TOOL_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "_JAVA_OPTIONS="
if not defined LOCALAPPDATA set "LOCALAPPDATA=%USERPROFILE%\AppData\Local"
set "APP_HOME=%LOCALAPPDATA%\RatioJuris\BulkFiledSigner"
if not exist "%APP_HOME%\temp" mkdir "%APP_HOME%\temp"
if not exist "%APP_HOME%\temp" exit /b 1
set "TEMP=%APP_HOME%\temp"
set "TMP=%APP_HOME%\temp"
set "BOOT_JAVA=%~dp0jdk\bin\java.exe"
if exist "%APP_HOME%\runtime\jdk-25.0.5.0.2\bin\java.exe" set "BOOT_JAVA=%APP_HOME%\runtime\jdk-25.0.5.0.2\bin\java.exe"
if not exist "%BOOT_JAVA%" if defined JAVA_HOME set "BOOT_JAVA=%JAVA_HOME%\bin\java.exe"
if not exist "%BOOT_JAVA%" (
    echo A JDK 21 or newer is required to bootstrap. Extract a portable Microsoft JDK into jdk.
    exit /b 1
)
"%BOOT_JAVA%" "-Djava.io.tmpdir=%TEMP%" --source 21 "%~dp0tools\Build.java" "%~dp0." "%~1"
exit /b %errorlevel%
