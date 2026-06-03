@echo off
:: run.bat — starts the S202 application on Windows
::
:: Run build-all.bat first if you haven't built the project yet.

setlocal

set S202_DIR=%~dp0

where java >nul 2>&1
if errorlevel 1 (
    echo ERROR: 'java' not found. Install Java 21+ and add it to PATH.
    pause
    exit /b 1
)

where mvn >nul 2>&1
if errorlevel 1 (
    echo ERROR: 'mvn' not found. Install Maven 3.9+ and add it to PATH.
    pause
    exit /b 1
)

pushd "%S202_DIR%"
mvn javafx:run -pl analyzer
if errorlevel 1 (
    popd
    echo.
    echo ERROR: Application failed to start. Run build-all.bat first.
    pause
    exit /b 1
)
popd
