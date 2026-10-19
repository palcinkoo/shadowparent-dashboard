@if "%DEBUG%"=="" @echo off
@setlocal enabledelayedexpansion

set APP_NAME=Gradle
set APP_BASE_NAME=%~n0

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.

set JAVA_CMD=java
if not "%JAVA_HOME%"=="" set JAVA_CMD="%JAVA_HOME%\bin\java.exe"

if not exist "%DIRNAME%\gradle\wrapper\gradle-wrapper.jar" (
    echo ERROR: gradle-wrapper.jar not found. Run 'gradle wrapper' first.
    exit /b 1
)

"%JAVA_CMD%" -Dorg.gradle.appname=%APP_BASE_NAME% -cp "%DIRNAME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
