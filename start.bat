@echo off
title Student Management System (Java + MongoDB)
color 0b

echo =======================================================
echo     STUDENT MANAGEMENT SYSTEM - JAVA WEB LAUNCHER
echo =======================================================
echo.

cd /d "%~dp0"

echo [1/3] Checking Java Development Kit (JDK)...
where javac >nul 2>nul
if %errorlevel% equ 0 (
    echo [OK] Java JDK detected.
    echo [2/3] Compiling MongoManager.java and StudentHttpServer.java with MongoDB driver...
    javac -cp "lib/*;." MongoManager.java StudentHttpServer.java
    if %errorlevel% neq 0 (
        echo [ERROR] Compilation failed.
        pause
        exit /b 1
    )
    echo [3/3] Starting Java Web Server on http://localhost:8000 ...
    start "" http://localhost:8000
    java -cp "lib/*;." StudentHttpServer
    goto end
)

where py >nul 2>nul
if %errorlevel% equ 0 (
    echo [INFO] Java compiler not in PATH, falling back to Python server...
    echo [2/2] Launching Web Server on http://localhost:8000 ...
    start "" http://localhost:8000
    py server.py
    goto end
)

where python >nul 2>nul
if %errorlevel% equ 0 (
    echo [INFO] Java compiler not in PATH, falling back to Python server...
    echo [2/2] Launching Web Server on http://localhost:8000 ...
    start "" http://localhost:8000
    python server.py
    goto end
)

echo [ERROR] Neither Java JDK nor Python was found in your PATH.
echo Please ensure Java JDK or Python is installed.
echo.
pause

:end
