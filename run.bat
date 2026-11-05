@echo off
setlocal DisableDelayedExpansion
rem Do not inherit machine-specific JVM log/temp destinations.
set "JAVA_TOOL_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "_JAVA_OPTIONS="
if not defined LOCALAPPDATA set "LOCALAPPDATA=%USERPROFILE%\AppData\Local"
set "APP_HOME=%LOCALAPPDATA%\RatioJuris\BulkFiledSigner"
set "JAVA_CMD=%APP_HOME%\runtime\jdk-25.0.5.0.2\bin\javaw.exe"
if not exist "%JAVA_CMD%" (
    echo Run build.bat to prepare the per-user Microsoft OpenJDK runtime.
    exit /b 1
)
set "APP_JAR=%APP_HOME%\app\BulkFiledSigner.jar"
if not exist "%APP_HOME%\app\current.txt" goto :check_jar
set "RELEASE="
set /p "RELEASE="<"%APP_HOME%\app\current.txt"
call :validate_release
if errorlevel 1 (
    echo Invalid application release pointer. Run build.bat again.
    exit /b 1
)
set "APP_JAR=%APP_HOME%\app\releases\%RELEASE%\BulkFiledSigner.jar"
:check_jar
if not exist "%APP_JAR%" (
    echo Run build.bat to build the application.
    exit /b 1
)
set "TEMP=%APP_HOME%\temp"
set "TMP=%APP_HOME%\temp"
if not exist "%TEMP%" mkdir "%TEMP%"
if not exist "%APP_HOME%\logs" mkdir "%APP_HOME%\logs"
cd /d "%APP_HOME%"
if errorlevel 1 exit /b 1
start "" /b "%JAVA_CMD%" --enable-native-access=ALL-UNNAMED "-Djava.io.tmpdir=%TEMP%" "-Duser.home=%APP_HOME%" "-XX:ErrorFile=%APP_HOME%\logs\hs_err_pid%%p.log" -jar "%APP_JAR%" >> "%APP_HOME%\logs\application.log" 2>&1
exit /b %errorlevel%

:validate_release
setlocal EnableDelayedExpansion
set "CHECK=!RELEASE!"
for %%C in (0 1 2 3 4 5 6 7 8 9 a b c d e f) do set "CHECK=!CHECK:%%C=!"
if defined CHECK goto :invalid_release
if "!RELEASE:~63,1!"=="" goto :invalid_release
if not "!RELEASE:~64,1!"=="" goto :invalid_release
endlocal
exit /b 0
:invalid_release
endlocal
exit /b 1
