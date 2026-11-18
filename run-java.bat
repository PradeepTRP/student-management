@echo off
title Student Management - Java + MongoDB Server
color 0a

echo ========================================================
echo     STUDENT MANAGEMENT SYSTEM - JAVA HTTP SERVER
echo ========================================================
echo.

cd /d "%~dp0"

echo [1/2] Compiling Java classes with MongoDB driver...
javac -cp "lib/*;." MongoManager.java StudentHttpServer.java
if %errorlevel% neq 0 (
    echo [ERROR] Java compilation failed!
    pause
    exit /b 1
)

echo [2/2] Launching Java Server on http://localhost:8000 ...
start "" http://localhost:8000
java -cp "lib/*;." StudentHttpServer
pause
