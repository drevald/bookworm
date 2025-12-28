@echo off
setlocal enabledelayedexpansion

REM Load environment variables from .env file and run the Spring Boot application

echo Loading environment variables from .env file...

REM Check if .env file exists
if not exist .env (
    echo Error: .env file not found!
    echo Please copy .env.example to .env and configure it.
    exit /b 1
)

REM Read .env file and set environment variables
for /f "usebackq tokens=*" %%a in (".env") do (
    set "line=%%a"
    
    REM Skip empty lines
    if not "!line!"=="" (
        REM Check if line starts with #
        set "firstchar=!line:~0,1!"
        if not "!firstchar!"=="#" (
            REM Parse the line as KEY=VALUE
            for /f "tokens=1,* delims==" %%b in ("!line!") do (
                set "%%b=%%c"
                echo Loading %%b=%%c
            )
        )
    )
)

echo.
echo Starting Spring Boot application...
echo Database: %DB_HOST%:%DB_PORT%/%DB_NAME%
echo Username: %DB_USERNAME%
echo Tessdata: %TESSDATA_PREFIX%
echo.

REM Run Gradle bootRun
gradle bootRun
