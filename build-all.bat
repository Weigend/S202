@echo off
:: build-all.bat
::
:: Bootstraps all dependencies and builds S202 from scratch (Windows).
::
:: What it does:
::   1. Checks for git, Java 21+, and Maven
::   2. Installs WFX into the local Maven cache (%USERPROFILE%\.m2) if not present
::      (clones https://github.com/Weigend/wfx and runs mvn install -DskipTests)
::   3. Builds S202
::
:: Usage: double-click or run from cmd / PowerShell

setlocal enabledelayedexpansion

set WFX_REPO=https://github.com/Weigend/wfx.git
set WFX_VERSION=1.0.1
set WFX_JAR=%USERPROFILE%\.m2\repository\io\softwareecg\wfx\wfx-platform\%WFX_VERSION%\wfx-platform-%WFX_VERSION%.jar
set S202_DIR=%~dp0

echo.
echo ========================================
echo   S202 build-all (Windows)
echo ========================================
echo.

:: ── check git ────────────────────────────────────────────────────────────────
where git >nul 2>&1
if errorlevel 1 (
    echo   [build-all] ERROR: 'git' not found.
    echo   Install Git from https://git-scm.com and re-run.
    goto :fail
)
echo   [build-all] git  OK

:: ── check Java ───────────────────────────────────────────────────────────────
where java >nul 2>&1
if errorlevel 1 (
    echo   [build-all] ERROR: 'java' not found.
    echo   Install Java 21+ and add it to PATH.
    goto :fail
)

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set RAW_VER=%%v
)
:: strip quotes and extract major version (handles "21.0.1" and "1.8.0_xxx")
set RAW_VER=%RAW_VER:"=%
for /f "delims=." %%m in ("%RAW_VER%") do set JAVA_MAJOR=%%m
:: old-style 1.x versioning
if "%JAVA_MAJOR%"=="1" (
    for /f "tokens=2 delims=." %%m in ("%RAW_VER%") do set JAVA_MAJOR=%%m
)
if %JAVA_MAJOR% LSS 21 (
    echo   [build-all] ERROR: Java 21+ required. Detected version %JAVA_MAJOR%.
    goto :fail
)
echo   [build-all] Java %JAVA_MAJOR%  OK

:: ── check Maven ──────────────────────────────────────────────────────────────
where mvn >nul 2>&1
if errorlevel 1 (
    echo   [build-all] ERROR: 'mvn' not found.
    echo   Install Maven 3.9+ from https://maven.apache.org/download.cgi and add to PATH.
    goto :fail
)
set MVN_VER=
for /f "tokens=3" %%v in ('mvn --version 2^>^&1 ^| findstr /i "apache maven"') do set MVN_VER=%%v
if "%MVN_VER%"=="" (
    echo   [build-all] ERROR: Could not determine Maven version.
    goto :fail
)
for /f "tokens=1,2 delims=." %%a in ("%MVN_VER%") do (
    set MVN_MAJOR=%%a
    set MVN_MINOR=%%b
)
if %MVN_MAJOR% LSS 3 (
    echo   [build-all] ERROR: Maven 3.9+ required. Detected version %MVN_VER%.
    goto :fail
)
if %MVN_MAJOR% EQU 3 if %MVN_MINOR% LSS 9 (
    echo   [build-all] ERROR: Maven 3.9+ required. Detected version %MVN_VER%.
    goto :fail
)
echo   [build-all] Maven %MVN_VER%  OK
:mvn_ok

:: ── Step 1: install WFX if not cached ────────────────────────────────────────
if exist "%WFX_JAR%" (
    echo   [build-all] WFX %WFX_VERSION% already in local Maven cache -- skipping.
) else (
    echo   [build-all] WFX %WFX_VERSION% not found -- cloning and building ...
    echo   [build-all] (this happens only once^)
    echo.

    set WFX_TMP=%TEMP%\wfx-bootstrap-%RANDOM%
    git clone --depth 1 %WFX_REPO% "%WFX_TMP%"
    if errorlevel 1 (
        echo   [build-all] ERROR: git clone failed. Check network / proxy settings.
        goto :fail
    )

    pushd "%WFX_TMP%"
    mvn install -DskipTests
    if errorlevel 1 (
        popd
        echo   [build-all] ERROR: WFX build failed. See Maven output above.
        goto :fail
    )
    popd
    rmdir /s /q "%WFX_TMP%"
    echo.
    echo   [build-all] WFX installed successfully.
)

:: ── Step 2: build S202 ───────────────────────────────────────────────────────
echo.
echo   [build-all] Building S202 ...
echo.

pushd "%S202_DIR%"
mvn clean install
if errorlevel 1 (
    popd
    echo   [build-all] ERROR: S202 build failed. See Maven output above.
    goto :fail
)
popd

echo.
echo ========================================
echo   Build successful!
echo.
echo   Run the application:
echo     mvn javafx:run -pl analyzer
echo ========================================
echo.
goto :end

:fail
echo.
pause
exit /b 1

:end
pause
